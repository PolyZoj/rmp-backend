# Оптимизация сборки проекта

## Содержание
1. [Внесенные изменения](#внесенные-изменения)
2. [Рекомендации по использованию](#рекомендации-по-использованию)
3. [Дополнительные оптимизации](#дополнительные-оптимизации)
4. [Устранение неполадок](#устранение-неполадок)

## Внесенные изменения

### 1. Оптимизация Dockerfile
- **Использование Alpine-образов**
  - Уменьшенный размер базовых образов
  - Ускоренная загрузка и развертывание
  - Меньше уязвимостей безопасности

- **Улучшенное кэширование Gradle**
  - Двойное кэширование для пользователей root и gradle
  - Предотвращение проблем с правами доступа
  - Ускорение повторных сборок

- **Оптимизация JVM**
  - Настроены параметры для работы в контейнерах
  - Оптимизировано использование памяти
  - Улучшена производительность

### 2. Оптимизация docker-compose.yml
- **Современные возможности**
  - Использование версии 3.8
  - Поддержка BuildKit
  - Улучшенная работа с сетью

- **Улучшенная структура**
  - Использование YAML-якорей
  - Уменьшение дублирования кода
  - Более чистая конфигурация

## Рекомендации по использованию

### 1. Переменные окружения
```bash
# Windows (PowerShell)
$env:DOCKER_BUILDKIT=1
$env:COMPOSE_DOCKER_CLI_BUILD=1

# Linux/Mac
export DOCKER_BUILDKIT=1
export COMPOSE_DOCKER_CLI_BUILD=1
```

### 2. Команды для сборки
```bash
# Полная сборка всех сервисов
docker-compose build --parallel

# Запуск в режиме разработки
docker-compose -f docker-compose.yml -f docker-compose.dev.yml up -d

# Запуск отдельных сервисов
docker-compose up -d auth-service user-service
```

## Дополнительные оптимизации

### 1. Настройка Gradle
Добавьте в `build.gradle.kts` каждого сервиса:

```kotlin
// Оптимизация компиляции
tasks.withType<JavaCompile> {
    options.fork = true
    options.isIncremental = true
}

// Параллельное выполнение тестов
tasks.withType<Test> {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).takeIf { it > 0 } ?: 1
}

// Дополнительные проверки при компиляции
gradle.projectsEvaluated {
    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-Xlint:unchecked")
        options.compilerArgs.add("-Xlint:deprecation")
    }
}
```

### 2. Настройка локального кэша
Добавьте в `docker-compose.dev.yml`:
```yaml
volumes:
  - ~/.gradle:/home/gradle/.gradle
```

### 3. Оптимизация Gradle Daemon
Создайте или отредактируйте `~/.gradle/gradle.properties`:
```properties
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configureondemand=true
org.gradle.jvmargs=-Xmx3g -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8
```

## Устранение неполадок

### 1. Мониторинг ресурсов
```bash
# Просмотр использования ресурсов контейнерами
docker stats

# Просмотр логов
docker-compose logs -f
```

### 2. Частые проблемы
- **Конфликты портов**: Используйте `docker-compose down` перед запуском
- **Проблемы с кэшем**: Выполните `docker system prune -f`
- **Проблемы с правами**: Проверьте права доступа к `.gradle` директории

### 3. Дополнительные команды
```bash
# Очистка всех контейнеров и томов
docker-compose down -v

# Пересборка конкретного сервиса
docker-compose build --no-cache service-name

# Просмотр статуса сервисов
docker-compose ps
``` 