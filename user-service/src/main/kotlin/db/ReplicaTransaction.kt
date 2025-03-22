package ru.polyZoj.db

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.sql.Connection

object ReplicaTransaction {
    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(
            db = DatabaseFactory.replicaDb,
            // Для чтения можно взять REPEATABLE_READ или READ_COMMITTED
            transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        ) {
            block()
        }
}
