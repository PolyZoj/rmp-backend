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
echo [1/5] Очистка контейнеров проекта...
echo.

REM Остановка контейнеров проекта
echo Останавливаем контейнеры проекта...
docker-compose down --remove-orphans
if %ERRORLEVEL% NEQ 0 (
    echo [ПРЕДУПРЕЖДЕНИЕ] Не удалось остановить предыдущие контейнеры
)

REM Удаление только контейнеров проекта
echo Удаляем контейнеры проекта...
for /f "tokens=*" %%i in ('docker ps -a --filter "name=rmp-backend" -q') do (
    docker rm -f %%i
)

echo.
echo [2/5] Очистка образов проекта...
echo.

REM Удаление только образов проекта
echo Удаляем образы проекта...
for /f "tokens=*" %%i in ('docker images --filter "label=com.docker.compose.project=rmp-backend" -q') do (
    docker rmi -f %%i
)

echo.
echo [3/5] Проверка портов проекта...
echo.

REM Проверка и освобождение портов проекта
for /l %%p in (9080,1,9090) do (
    netstat -ano ^| find "%%p" >nul
    if !ERRORLEVEL! EQU 0 (
        echo Порт %%p занят. Пытаемся освободить...
        for /f "tokens=5" %%a in ('netstat -aon ^| find "%%p"') do (
            taskkill /F /PID %%a >nul 2>nul
        )
        timeout /t 2 >nul
    )
)

echo.
echo [4/5] Сборка проекта с использованием кэша...
echo.

REM Сборка проекта с использованием кэша
docker-compose build --parallel
if %ERRORLEVEL% NEQ 0 (
    echo [ОШИБКА] Сборка не удалась
    pause
    exit /b 1
)

echo.
echo [5/5] Запуск сервисов...
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
echo - docker stats             ^| Мониторинг ресурсов контейнеров
echo.
echo Для разработки используйте:
echo docker-compose -f docker-compose.yml -f docker-compose.dev.yml up -d
echo.
pause 