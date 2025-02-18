package io.horizontalsystems.bitcoincore.transactions

import io.horizontalsystems.bitcoincore.WatchedTransactionManager
import io.horizontalsystems.bitcoincore.blocks.IBlockchainDataListener
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.core.inTopologicalOrder
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.managers.IIrregularOutputFinder
import io.horizontalsystems.bitcoincore.managers.IrregularOutputFinder
import io.horizontalsystems.bitcoincore.managers.PublicKeyManager
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.storage.FullTransaction

class TransactionProcessor(
        private val storage: IStorage,
        private val extractor: TransactionExtractor,
        private val outputsCache: OutputsCache,
        private val publicKeyManager: PublicKeyManager,
        private val irregularOutputFinder: IIrregularOutputFinder,
        private val dataListener: IBlockchainDataListener) {

    var listener: WatchedTransactionManager? = null

    fun processOutgoing(transaction: FullTransaction) {
        if (storage.getTransaction(transaction.header.hash) != null) {
            throw TransactionCreator.TransactionAlreadyExists("hash = ${transaction.header.hash.toReversedHex()}")
        }

        process(transaction)

        storage.addTransaction(transaction)
        dataListener.onTransactionsUpdate(listOf(transaction.header), listOf(), null)

        if (irregularOutputFinder.hasIrregularOutput(transaction.outputs)) {
            throw BloomFilterManager.BloomFilterExpired
        }
    }

    @Throws(BloomFilterManager.BloomFilterExpired::class)
    fun processIncoming(transactions: List<FullTransaction>, block: Block?, skipCheckBloomFilter: Boolean) {
        var needToUpdateBloomFilter = false

        val inserted = mutableListOf<Transaction>()
        val updated = mutableListOf<Transaction>()

        // when the same transaction came in merkle block and from another peer's mempool we need to process it serial
        synchronized(this) {
            for ((index, transaction) in transactions.inTopologicalOrder().withIndex()) {
                val transactionInDB = storage.getTransaction(transaction.header.hash)
                if (transactionInDB != null) {

                    if (transactionInDB.blockHash != null && block == null) {
                        continue
                    }
                    relay(transactionInDB, index, block)
                    storage.updateTransaction(transactionInDB)

                    updated.add(transactionInDB)
                    continue
                }

                process(transaction)

                listener?.onTransactionReceived(transaction)

                if (transaction.header.isMine) {
                    relay(transaction.header, index, block)

                    storage.addTransaction(transaction)
                    inserted.add(transaction.header)

                    if (!skipCheckBloomFilter) {
                        needToUpdateBloomFilter = needToUpdateBloomFilter || publicKeyManager.gapShifts() || irregularOutputFinder.hasIrregularOutput(transaction.outputs)
                    }
                }
            }
        }

        if (inserted.isNotEmpty() || updated.isNotEmpty()) {
            dataListener.onTransactionsUpdate(inserted, updated, block)
        }

        if (needToUpdateBloomFilter) {
            throw BloomFilterManager.BloomFilterExpired
        }
    }

    private fun process(transaction: FullTransaction) {
        extractor.extractOutputs(transaction)

        if (outputsCache.hasOutputs(transaction.inputs)) {
            transaction.header.isMine = true
            transaction.header.isOutgoing = true
        }

        if (transaction.header.isMine) {
            outputsCache.add(transaction.outputs)
            extractor.extractAddress(transaction)
            extractor.extractInputs(transaction)
        }
    }

    private fun relay(transaction: Transaction, order: Int, block: Block?) {
        transaction.status = Transaction.Status.RELAYED
        transaction.order = order
        transaction.blockHash = block?.headerHash
        transaction.timestamp = block?.timestamp ?: (System.currentTimeMillis() / 1000)

        if (block != null && !block.hasTransactions) {
            block.hasTransactions = true
            storage.updateBlock(block)
        }
    }

}
