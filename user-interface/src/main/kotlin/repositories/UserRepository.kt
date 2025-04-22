package ru.polyZoj.repositories

import at.favre.lib.crypto.bcrypt.BCrypt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.slf4j.LoggerFactory
import ru.polyZoj.db.*
import ru.polyZoj.exceptions.UserNotFoundException
import ru.polyZoj.exceptions.ValidationException
import ru.polyZoj.models.*
import java.time.LocalDateTime

class UserRepository {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val bcryptHasher = BCrypt.withDefaults()
    private val bcryptVerifier = BCrypt.verifyer()
    
    // Регистрация нового пользователя
    suspend fun registerUser(userReg: UserRegistration): Int = DatabaseFactory.dbWriteQuery {
        // Проверка на существующий email
        val existingUser = UserEmails.select { UserEmails.email eq userReg.email }.firstOrNull()
        if (existingUser != null) {
            throw ValidationException("Пользователь с таким email уже существует")
        }
        
        // Хеширование пароля (используем виртуальные потоки JDK 21 для CPU-интенсивной операции)
        val hashedPassword = withContext(Dispatchers.Default) {
            bcryptHasher.hashToString(12, userReg.password.toCharArray())
        }
        
        // Создание пользователя в транзакции
        var userId = 0
        
        // Создание пользователя
        userId = (Users.insert {
            it[firstName] = userReg.firstName
            it[lastName] = userReg.lastName
        } get Users.id).value
        
        // Добавление email
        UserEmails.insert {
            it[UserEmails.userId] = userId
            it[email] = userReg.email
        }
        
        // Добавление пароля
        UserPasswords.insert {
            it[UserPasswords.userId] = userId
            it[password] = hashedPassword
        }
        
        // Сохранение параметров пользователя
        UserParameters.insert {
            it[UserParameters.userId] = userId
            it[weight] = userReg.weight
            it[height] = userReg.height
            it[birthDate] = java.time.LocalDate.parse(userReg.birthDate)
            it[unitSystemId] = userReg.unitSystemId
        }
        
        // Сохранение выбранной системы единиц измерения
        UserUnitSystems.insert {
            it[UserUnitSystems.userId] = userId
            it[unitSystemId] = userReg.unitSystemId
        }
        
        // Сохранение выбранной системы измерения энергии
        UserEnergySystems.insert {
            it[UserEnergySystems.userId] = userId
            it[energySystemId] = userReg.energySystemId
        }
        
        // Сохранение предпочтений пользователя
        UserGoals.insert {
            it[UserGoals.userId] = userId
            it[healthGoalId] = userReg.healthGoalId
            it[dailySteps] = userReg.dailySteps
            it[waterIntake] = userReg.waterIntake
            it[energyIntake] = userReg.energyIntake
            it[sleepHours] = userReg.sleepHours
        }
        
        logger.info("Успешно зарегистрирован новый пользователь с ID: $userId")
        userId
    }

    fun findByUsername(username: String): User? {
        return null!!
    }

    // Получение пользователя по ID
    suspend fun getUserById(userId: Int): UserResponse? = DatabaseFactory.dbReadQuery {
        val user = Users
            .select { Users.id eq userId }
            .firstOrNull()
            ?.let { userRow ->
                // Получаем email пользователя
                val email = UserEmails
                    .select { UserEmails.userId eq userId }
                    .firstOrNull()
                    ?.get(UserEmails.email)
                    ?: ""
                    
                UserResponse(
                    userId = userRow[Users.id].value,
                    firstName = userRow[Users.firstName],
                    lastName = userRow[Users.lastName],
                    email = email,
                    createdAt = userRow[Users.createdAt].toString(),
                    parameters = null,
                    preferences = null
                )
            } ?: return@dbReadQuery null

        // Загрузка параметров
        val parameters = UserParameters
            .select { UserParameters.userId eq userId }
            .firstOrNull()
            ?.let { row ->
                UserParametersDTO(
                    userId = row[UserParameters.userId],
                    weight = row[UserParameters.weight],
                    height = row[UserParameters.height],
                    birthDate = row[UserParameters.birthDate].toString(),
                    unitSystemId = row[UserParameters.unitSystemId]
                )
            }

        // Загрузка предпочтений
        val goals = UserGoals
            .select { UserGoals.userId eq userId }
            .firstOrNull()
            
        val unitSystem = UserUnitSystems
            .select { UserUnitSystems.userId eq userId }
            .firstOrNull()
            
        val energySystem = UserEnergySystems
            .select { UserEnergySystems.userId eq userId }
            .firstOrNull()
            
        val preferences = if (goals != null && unitSystem != null && energySystem != null) {
            UserPreferences(
                unitSystemId = unitSystem[UserUnitSystems.unitSystemId],
                energySystemId = energySystem[UserEnergySystems.energySystemId],
                healthGoalId = goals[UserGoals.healthGoalId],
                dailySteps = goals[UserGoals.dailySteps],
                waterIntake = goals[UserGoals.waterIntake],
                energyIntake = goals[UserGoals.energyIntake],
                sleepHours = goals[UserGoals.sleepHours]
            )
        } else null

        user.copy(parameters = parameters, preferences = preferences)
    }
    
    // Получение списка всех пользователей
    suspend fun getAllUsers(): List<User> = DatabaseFactory.dbReadQuery {
        Users
            .join(UserEmails, JoinType.INNER, Users.id, UserEmails.userId)
            .selectAll()
            .map { row ->
                User(
                    userId = row[Users.id].value,
                    firstName = row[Users.firstName],
                    lastName = row[Users.lastName],
                    email = row[UserEmails.email],
                    createdAt = row[Users.createdAt].toString()
                )
            }
    }
    
    // Обновление информации о пользователе
    suspend fun updateUser(userId: Int, user: User): Boolean = DatabaseFactory.dbWriteQuery {
        val userExists = Users.select { Users.id eq userId }.count() > 0
        if (!userExists) {
            throw UserNotFoundException("Пользователь с ID $userId не найден")
        }

        // Обновление основной информации
        Users.update({ Users.id eq userId }) {
            if (user.firstName.isNotBlank()) it[firstName] = user.firstName
            if (user.lastName.isNotBlank()) it[lastName] = user.lastName
        }
        
        // Обновление email, если он предоставлен
        if (user.email.isNotBlank()) {
            // Проверка уникальности email
            val existingUser = UserEmails
                .select { (UserEmails.email eq user.email) and (UserEmails.userId neq userId) }
                .firstOrNull()
                
            if (existingUser != null) {
                throw ValidationException("Пользователь с таким email уже существует")
            }
            
            UserEmails.update({ UserEmails.userId eq userId }) {
                it[email] = user.email
            }
        }
        
        logger.info("Обновлена информация пользователя с ID: $userId")
        true
    }
    
    // Обновление параметров пользователя
    suspend fun updateUserParameters(userId: Int, params: UserParametersDTO): Boolean = DatabaseFactory.dbWriteQuery {
        // Проверяем существование пользователя
        val userExists = Users.select { Users.id eq userId }.count() > 0
        if (!userExists) {
            throw UserNotFoundException("Пользователь с ID $userId не найден")
        }
        
        val updateCount = UserParameters.update({ UserParameters.userId eq userId }) {
            it[weight] = params.weight
            it[height] = params.height
            it[birthDate] = java.time.LocalDate.parse(params.birthDate)
            it[unitSystemId] = params.unitSystemId
        }
        
        if (updateCount == 0) {
            // Если записи нет, создаем новую
            UserParameters.insert {
                it[UserParameters.userId] = userId
                it[weight] = params.weight
                it[height] = params.height
                it[birthDate] = java.time.LocalDate.parse(params.birthDate)
                it[unitSystemId] = params.unitSystemId
            }
            logger.info("Созданы параметры пользователя с ID: $userId")
            return@dbWriteQuery true
        }
        
        // Обновляем систему единиц измерения
        UserUnitSystems.update({ UserUnitSystems.userId eq userId }) {
            it[unitSystemId] = params.unitSystemId
        }
        
        logger.info("Обновлены параметры пользователя с ID: $userId")
        true
    }
    
    // Обновление предпочтений пользователя
    suspend fun updateUserPreferences(userId: Int, prefs: UserPreferences): Boolean = DatabaseFactory.dbWriteQuery {
        // Проверка существования пользователя
        val userExists = Users.select { Users.id eq userId }.count() > 0
        if (!userExists) {
            throw UserNotFoundException("Пользователь с ID $userId не найден")
        }
        
        // Обновление системы единиц измерения
        val unitUpdated = UserUnitSystems.update({ UserUnitSystems.userId eq userId }) {
            it[unitSystemId] = prefs.unitSystemId
        }
        
        if (unitUpdated == 0) {
            UserUnitSystems.insert {
                it[UserUnitSystems.userId] = userId
                it[unitSystemId] = prefs.unitSystemId
            }
        }
        
        // Обновление системы измерения энергии
        val energyUpdated = UserEnergySystems.update({ UserEnergySystems.userId eq userId }) {
            it[energySystemId] = prefs.energySystemId
        }
        
        if (energyUpdated == 0) {
            UserEnergySystems.insert {
                it[UserEnergySystems.userId] = userId
                it[energySystemId] = prefs.energySystemId
            }
        }
        
        // Обновление целей
        val goalsUpdated = UserGoals.update({ UserGoals.userId eq userId }) {
            it[healthGoalId] = prefs.healthGoalId
            it[dailySteps] = prefs.dailySteps
            it[waterIntake] = prefs.waterIntake
            it[energyIntake] = prefs.energyIntake
            it[sleepHours] = prefs.sleepHours
        }
        
        if (goalsUpdated == 0) {
            // Запись не найдена, создаем новую
            UserGoals.insert {
                it[UserGoals.userId] = userId
                it[healthGoalId] = prefs.healthGoalId
                it[dailySteps] = prefs.dailySteps
                it[waterIntake] = prefs.waterIntake
                it[energyIntake] = prefs.energyIntake
                it[sleepHours] = prefs.sleepHours
            }
        }
        
        logger.info("Обновлены предпочтения пользователя с ID: $userId")
        true
    }
    
    // Удаление пользователя и всех связанных данных
    suspend fun deleteUser(userId: Int): Boolean = DatabaseFactory.dbWriteQuery {
        logger.info("Удаление пользователя с ID: $userId")
        
        // Удаляем все связанные записи в таблицах
        UserGoals.deleteWhere { UserGoals.userId eq userId }
        UserEnergySystems.deleteWhere { UserEnergySystems.userId eq userId }
        UserUnitSystems.deleteWhere { UserUnitSystems.userId eq userId }
        UserParameters.deleteWhere { UserParameters.userId eq userId }
        UserPasswords.deleteWhere { UserPasswords.userId eq userId }
        UserEmails.deleteWhere { UserEmails.userId eq userId }
        
        // Удаляем самого пользователя
        val deletedCount = Users.deleteWhere { Users.id eq userId }
        
        deletedCount > 0
    }
    
    // Проверка учетных данных пользователя (для авторизации)
    suspend fun checkCredentials(credentials: UserCredentials): Int? = DatabaseFactory.dbReadQuery {
        val userEmail = UserEmails
            .select { UserEmails.email eq credentials.email }
            .firstOrNull() ?: return@dbReadQuery null
            
        val userId = userEmail[UserEmails.userId]
        
        val userPassword = UserPasswords
            .select { UserPasswords.userId eq userId }
            .firstOrNull() ?: return@dbReadQuery null
            
        val hashedPassword = userPassword[UserPasswords.password]
        
        // Проверка пароля с использованием виртуальных потоков JDK 21 для CPU-интенсивной операции
        val result = withContext(Dispatchers.Default) {
            bcryptVerifier.verify(
                credentials.password.toCharArray(),
                hashedPassword.toCharArray()
            )
        }
        
        if (result.verified) {
            userId
        } else {
            null
        }
    }
    
    // Смена пароля пользователя
    suspend fun changePassword(userId: Int, oldPassword: String, newPassword: String): Boolean = DatabaseFactory.dbWriteQuery {
        // Получаем текущий пароль
        val currentPasswordRecord = UserPasswords
            .select { UserPasswords.userId eq userId }
            .firstOrNull() ?: return@dbWriteQuery false
            
        val currentHashedPassword = currentPasswordRecord[UserPasswords.password]
        
        // Проверяем старый пароль
        val isOldPasswordCorrect = withContext(Dispatchers.Default) {
            bcryptVerifier.verify(
                oldPassword.toCharArray(),
                currentHashedPassword.toCharArray()
            ).verified
        }
        
        if (!isOldPasswordCorrect) {
            throw ValidationException("Неверный текущий пароль")
        }
        
        // Генерируем новый хеш
        val newHashedPassword = withContext(Dispatchers.Default) {
            bcryptHasher.hashToString(12, newPassword.toCharArray())
        }
        
        // Обновляем пароль
        val updateCount = UserPasswords.update({ UserPasswords.userId eq userId }) {
            it[password] = newHashedPassword
        }
        
        logger.info("Изменен пароль пользователя с ID: $userId")
        updateCount > 0
    }
    
    // Методы для совместимости с ReferenceRepository
    suspend fun getUnitSystems(): List<UnitSystem> = DatabaseFactory.dbReadQuery {
        UnitSystems.selectAll()
            .map { row -> 
                UnitSystem(
                    unitSystemId = row[UnitSystems.id].value,
                    systemName = row[UnitSystems.systemName]
                )
            }
    }
    
    suspend fun getEnergySystems(): List<EnergySystem> = DatabaseFactory.dbReadQuery {
        EnergySystems.selectAll()
            .map { row -> 
                EnergySystem(
                    energySystemId = row[EnergySystems.id].value,
                    systemName = row[EnergySystems.systemName]
                )
            }
    }
    
    suspend fun getHealthGoals(): List<HealthGoal> = DatabaseFactory.dbReadQuery {
        HealthGoals.selectAll()
            .map { row -> 
                HealthGoal(
                    healthGoalId = row[HealthGoals.id].value,
                    goalName = row[HealthGoals.goalName]
                )
            }
    }
}