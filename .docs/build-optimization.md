# Руководство по сборке и развертыванию проекта

## Содержание
1. [Быстрый старт](#быстрый-старт)
2. [Структура проекта](#структура-проекта)
3. [Оптимизации сборки](#оптимизации-сборки)
4. [Скрипты для разработки](#скрипты-для-разработки)
5. [Устранение неполадок](#устранение-неполадок)

## Быстрый старт

### Предварительные требования
- Установленный Docker
- Установленный Docker Compose
- Git для клонирования репозитория
- JDK 17 или выше

### Первый запуск
1. Клонируйте репозиторий:
```bash
git clone <url-репозитория>
cd rmp-backend
```

2. Настройте переменные окружения:
```bash
# Windows (PowerShell)
$env:DOCKER_BUILDKIT=1
$env:COMPOSE_DOCKER_CLI_BUILD=1

# Linux/Mac
export DOCKER_BUILDKIT=1
export COMPOSE_DOCKER_CLI_BUILD=1
```

3. Запустите проект:
```bash
# Windows
.\build-fast.bat

# Linux/Mac
./build-fast.sh
```

## Структура проекта
Проект состоит из нескольких микросервисов:
- `auth-service`: Аутентификация и авторизация
- `user-service`: Управление пользователями
- `notification-service`: Система уведомлений
- `challenges-service`: Управление заданиями
- И другие сервисы...

## Оптимизации сборки

### 1. Оптимизация Docker
- **Использование Alpine-образов**
  - Легковесные базовые образы
  - Быстрая загрузка и развертывание
  - Повышенная безопасность

- **Кэширование сборки**
  - Двухуровневое кэширование Gradle
  - Оптимизированные слои Docker
  - Быстрые повторные сборки

### 2. Оптимизация Gradle
```kotlin
// build.gradle.kts
tasks.withType<JavaCompile> {
    options.fork = true
    options.isIncremental = true
}

tasks.withType<Test> {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).takeIf { it > 0 } ?: 1
}
```

### 3. Конфигурация Docker Compose
- Версия 3.8 с современными возможностями
- Оптимизированная работа с сетью
- Эффективное управление ресурсами

## Скрипты для разработки

### build-fast.bat/sh
- Быстрая пересборка всего проекта
- Удаление старых контейнеров
- Параллельная сборка сервисов
```bash
# Использование
.\build-fast.bat  # Windows
./build-fast.sh   # Linux/Mac
```

### Полезные команды
```bash
# Запуск отдельных сервисов
docker-compose up -d auth-service user-service

# Просмотр логов
docker-compose logs -f [сервис]

# Перезапуск сервиса
docker-compose restart [сервис]
```

## Устранение неполадок

### Частые проблемы и решения

1. **Конфликты портов**
   ```bash
   # Остановка всех контейнеров
   docker-compose down --remove-orphans
   ```

2. **Проблемы с кэшем**
   ```bash
   # Очистка неиспользуемых ресурсов
   docker system prune -f
   ```

3. **Проблемы с памятью**
   - Проверьте настройки Docker Desktop
   - Увеличьте доступную память в настройках

### Мониторинг
```bash
# Просмотр использования ресурсов
docker stats

# Статус сервисов
docker-compose ps

# Подробные логи
docker-compose logs -f --tail=100
```

