@echo off
chcp 65001 > nul
setlocal EnableDelayedExpansion

echo ============================================
echo Запуск оптимизированной сборки проекта
echo ============================================

REM Проверяем наличие Docker
where docker >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [ОШИБКА] Docker не установлен или не добавлен в PATH
    echo Пожалуйста, установите Docker Desktop
    pause
    exit /b 1
)

REM Проверяем, запущен ли Docker
docker info >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [ОШИБКА] Docker не запущен
    echo Пожалуйста, запустите Docker Desktop
    pause
    exit /b 1
)

REM Установка переменных окружения для ускорения сборки
set DOCKER_BUILDKIT=1
set COMPOSE_DOCKER_CLI_BUILD=1

echo.
echo [1/4] Очистка предыдущих контейнеров проекта...
docker-compose down
if %ERRORLEVEL% NEQ 0 (
    echo [ПРЕДУПРЕЖДЕНИЕ] Не удалось остановить предыдущие контейнеры
)

echo.
echo [2/4] Очистка неиспользуемых томов проекта...
docker volume prune -f --filter "label=com.docker.compose.project=rmp-backend"
if %ERRORLEVEL% NEQ 0 (
    echo [ПРЕДУПРЕЖДЕНИЕ] Не удалось очистить неиспользуемые тома проекта
)

echo.
echo [3/4] Параллельная сборка микросервисов...
docker-compose build --parallel
if %ERRORLEVEL% NEQ 0 (
    echo [ОШИБКА] Сборка не удалась
    pause
    exit /b 1
)

echo.
echo [4/4] Запуск сервисов...
docker-compose up -d
if %ERRORLEVEL% NEQ 0 (
    echo [ОШИБКА] Запуск сервисов не удался
    pause
    exit /b 1
)

echo.
echo ============================================
echo Сборка и запуск успешно завершены!
echo ============================================
echo.
echo Полезные команды:
echo - docker-compose logs -f    ^| Просмотр логов всех сервисов
echo - docker-compose ps         ^| Список запущенных контейнеров
echo - docker stats             ^| Мониторинг ресурсов контейнеров
echo.
echo Для разработки используйте:
echo docker-compose -f docker-compose.yml -f docker-compose.dev.yml up -d
echo.
pause 