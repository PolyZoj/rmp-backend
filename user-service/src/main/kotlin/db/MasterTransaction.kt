package ru.polyZoj.db

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.sql.Connection

object MasterTransaction {
    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(
            // Указываем базу
            db = DatabaseFactory.masterDb,
            // Для записи используем более строгую изоляцию
            transactionIsolation = Connection.TRANSACTION_SERIALIZABLE
        ) {
            block()
        }
}
