@echo off
chcp 65001 > nul
setlocal EnableDelayedExpansion

REM Обработка параметров командной строки
set CLEAN_ALL=0
if "%1"=="--full-clean" (
    set CLEAN_ALL=1
    echo Запускается сборка с полной очисткой...
)

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
set COMPOSE_HTTP_TIMEOUT=300

echo.
echo [1/4] Очистка контейнеров...
echo.

REM Останавливаем контейнеры проекта
echo Останавливаем контейнеры проекта...
docker-compose down --remove-orphans
if %ERRORLEVEL% NEQ 0 (
    echo [ПРЕДУПРЕЖДЕНИЕ] Не удалось остановить предыдущие контейнеры
)

REM Удаление оставшихся контейнеров
for /f "tokens=*" %%i in ('docker ps -a --filter "name=rmp-backend" -q') do (
    echo Удаляем оставшиеся контейнеры...
    docker rm -f %%i >nul 2>nul
)

REM Проверка и удаление сети проекта для избежания ошибок сети
echo Проверяем и удаляем сеть проекта...
docker network ls | find "backend_net" > nul
if %ERRORLEVEL% EQU 0 (
    docker network rm backend_net >nul 2>nul
    if %ERRORLEVEL% NEQ 0 (
        echo [ПРЕДУПРЕЖДЕНИЕ] Не удалось удалить сеть backend_net, пробуем принудительно удалить контейнеры
        docker ps -a --filter "network=backend_net" -q | docker rm -f >nul 2>nul
        docker network rm backend_net >nul 2>nul
    )
    timeout /t 3 >nul
)

echo.
echo [2/4] Подготовка к сборке...
echo.

REM Полная очистка, если запрошена
if %CLEAN_ALL%==1 (
    echo Удаляем все образы проекта...
    for /f "tokens=*" %%i in ('docker images --filter "label=com.docker.compose.project=rmp-backend" -q') do (
        docker rmi -f %%i >nul 2>nul
    )
) else (
    echo Используем кэширование для ускорения сборки...
)

REM Создаем .dockerignore для оптимизации
if not exist ".dockerignore" (
    echo Создаем .dockerignore файл...
    echo .git> .dockerignore
    echo .idea>> .dockerignore
    echo .vscode>> .dockerignore
    echo */build>> .dockerignore
    echo */.gradle>> .dockerignore
)

echo.
echo [3/4] Сборка проекта...
echo.

REM Сборка проекта
echo Запускаем сборку (это может занять некоторое время)...
if %CLEAN_ALL%==1 (
    docker-compose build --parallel --no-cache
) else (
    docker-compose build --parallel
)

if %ERRORLEVEL% NEQ 0 (
    echo [ОШИБКА] Сборка не удалась!
    pause
    exit /b 1
)

echo.
echo [4/4] Запуск сервисов...
echo.

REM Запуск сервисов
docker-compose up -d
if %ERRORLEVEL% NEQ 0 (
    echo [ОШИБКА] Запуск сервисов не удался
    pause
    exit /b 1
)

echo.
echo ============================================
echo Сборка и запуск проекта успешно завершены!
echo ============================================
echo.
echo Полезные команды:
echo - docker-compose logs -f    ^| Просмотр логов всех сервисов
echo - docker-compose ps         ^| Список запущенных контейнеров
echo - docker stats              ^| Мониторинг ресурсов
echo.
echo Для разработки:
echo - .\build-fast.bat                 ^| Обычная сборка
echo - .\build-fast.bat --full-clean    ^| Полная пересборка
echo.
pause 