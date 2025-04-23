package ru.polyZoj.db

object DatabaseFactory {
    private lateinit var config: DataSourceConfig

    internal fun init(cfg: DataSourceConfig) {
        config = cfg
    }

    fun <T> read(block: org.jetbrains.exposed.sql.Transaction.() -> T) = config.withRead(block)
    fun <T> write(block: org.jetbrains.exposed.sql.Transaction.() -> T) = config.withWrite(block)
}