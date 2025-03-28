package ru.polyZoj.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.*
import kotlinx.coroutines.Dispatchers
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import ru.polyZoj.exceptions.ConnectionException
import ru.polyZoj.exceptions.DatabaseException
import ru.polyZoj.tables.*

/**
 * Фабрика для работы с базой данных
 */
object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(this::class.java)
    
    private lateinit var writerDataSource: HikariDataSource
    private lateinit var readerDataSource: HikariDataSource
    
    /**
     * Инициализация базы данных
     * @param config конфигурация приложения
     */
    fun init(config: ApplicationConfig) {
        try {
            // Инициализация источников данных для записи и чтения
            initWriterDataSource(config)
            initReaderDataSource(config)
            
            // Применяем миграции только к основной БД (writer)
            migrateDatabase(writerDataSource)
            
            // Создаем необходимые таблицы, если их нет
            transaction(Database.connect(writerDataSource)) {
                SchemaUtils.create(
                    Users,
                    UserEmails,
                    UserPasswords,
                    UserParameters, 
                    UserUnitSystems,
                    UserEnergySystems,
                    UserGoals,
                    UnitSystemsTable,
                    EnergySystemsTable,
                    HealthGoalsTable
                )
                
                // Создаем стандартные записи справочников, если они пусты
                insertDefaultData()
            }
            
            logger.info("Database initialized successfully")
        } catch (e: Exception) {
            val errorMessage = "Failed to initialize database: ${e.message}"
            logger.error(errorMessage, e)
            throw ConnectionException(errorMessage)
        }
    }
    
    /**
     * Инициализирует источник данных для записи
     */
    private fun initWriterDataSource(config: ApplicationConfig) {
        logger.info("Initializing writer data source")
        val hikariConfig = createHikariConfig(config.config("database.writer"))
        writerDataSource = HikariDataSource(hikariConfig)
    }
    
    /**
     * Инициализирует источник данных для чтения
     */
    private fun initReaderDataSource(config: ApplicationConfig) {
        logger.info("Initializing reader data source")
        val hikariConfig = createHikariConfig(config.config("database.reader"))
        readerDataSource = HikariDataSource(hikariConfig)
    }
    
    /**
     * Создает конфигурацию HikariCP из настроек приложения
     */
    private fun createHikariConfig(config: ApplicationConfig): HikariConfig {
        return HikariConfig().apply {
            jdbcUrl = config.property("jdbcUrl").getString()
            username = config.property("username").getString()
            password = config.property("password").getString()
            driverClassName = config.property("driverClassName").getString()
            maximumPoolSize = config.property("maximumPoolSize").getString().toInt()
            minimumIdle = config.propertyOrNull("minimumIdle")?.getString()?.toInt() ?: 5
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
    }
    
    /**
     * Применяет миграции базы данных
     * @param dataSource источник данных
     */
    private fun migrateDatabase(dataSource: HikariDataSource) {
        logger.info("Applying database migrations")
        
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(System.getenv("FLYWAY_LOCATIONS") ?: "classpath:db/migration")
            .load()
            
        try {
            flyway.migrate()
            logger.info("Database migrations applied successfully")
        } catch (e: Exception) {
            val errorMessage = "Failed to apply database migrations: ${e.message}"
            logger.error(errorMessage, e)
            throw DatabaseException(errorMessage)
        }
    }
    
    /**
     * Создает стандартные записи в справочниках
     */
    private fun insertDefaultData() {
        // Вставляем системы единиц измерения, если таблица пуста
        if (UnitSystemsTable.selectAll().count() == 0L) {
            logger.info("Inserting default unit systems")
            
            UnitSystemsTable.insert {
                it[systemName] = "Метрическая"
            }
            
            UnitSystemsTable.insert {
                it[systemName] = "Британская"
            }
        }
        
        // Вставляем системы измерения энергии, если таблица пуста
        if (EnergySystemsTable.selectAll().count() == 0L) {
            logger.info("Inserting default energy systems")
            
            EnergySystemsTable.insert {
                it[systemName] = "Калории"
            }
            
            EnergySystemsTable.insert {
                it[systemName] = "Джоули"
            }
        }
        
        // Вставляем цели по здоровью, если таблица пуста
        if (HealthGoalsTable.selectAll().count() == 0L) {
            logger.info("Inserting default health goals")
            
            HealthGoalsTable.insert {
                it[goalName] = "Снижение веса"
            }
            
            HealthGoalsTable.insert {
                it[goalName] = "Поддержание веса"
            }
            
            HealthGoalsTable.insert {
                it[goalName] = "Набор веса"
            }
            
            HealthGoalsTable.insert {
                it[goalName] = "Улучшение физической формы"
            }
        }
    }
    
    /**
     * Тестирует соединение с базой данных для чтения
     * @return true, если соединение установлено
     */
    fun testReaderConnection(): Boolean {
        if (!::readerDataSource.isInitialized) {
            logger.warn("Reader data source is not initialized")
            return false
        }
        
        return try {
            readerDataSource.connection.use { connection ->
                connection.isValid(5)
            }
        } catch (e: Exception) {
            logger.error("Reader database connection test failed", e)
            false
        }
    }
    
    /**
     * Тестирует соединение с базой данных для записи
     * @return true, если соединение установлено
     */
    fun testWriterConnection(): Boolean {
        if (!::writerDataSource.isInitialized) {
            logger.warn("Writer data source is not initialized")
            return false
        }
        
        return try {
            writerDataSource.connection.use { connection ->
                connection.isValid(5)
            }
        } catch (e: Exception) {
            logger.error("Writer database connection test failed", e)
            false
        }
    }
    
    /**
     * Выполняет SQL запрос на чтение в корутине
     * @param block блок кода для выполнения
     * @return результат выполнения блока
     */
    suspend fun <T> dbReadQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, Database.connect(readerDataSource)) { 
            block() 
        }
    
    /**
     * Выполняет SQL запрос на запись в корутине
     * @param block блок кода для выполнения
     * @return результат выполнения блока
     */
    suspend fun <T> dbWriteQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO, Database.connect(writerDataSource)) { 
            block() 
        }
}