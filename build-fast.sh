#!/bin/bash
# Скрипт быстрой сборки и запуска проекта

echo "============================================"
echo "Запуск оптимизированной сборки проекта"
echo "============================================"

# Проверка наличия Docker
if ! command -v docker &> /dev/null; then
    echo "[ОШИБКА] Docker не установлен. Пожалуйста, установите Docker"
    exit 1
fi

# Проверка статуса Docker
if ! docker info &> /dev/null; then
    echo "[ОШИБКА] Docker не запущен. Пожалуйста, запустите Docker"
    exit 1
fi

# Установка переменных окружения для ускорения сборки
export DOCKER_BUILDKIT=1
export COMPOSE_DOCKER_CLI_BUILD=1

echo
echo "[1/5] Очистка контейнеров проекта..."
echo

# Остановка контейнеров проекта
echo "Останавливаем контейнеры проекта..."
docker-compose down --remove-orphans

# Удаление только контейнеров проекта (без томов)
echo "Удаляем контейнеры проекта..."
docker ps -a --filter "name=rmp-backend" -q | xargs -r docker rm -f

# Проверка и удаление сети проекта
echo "Проверяем и удаляем сеть проекта..."
if docker network ls | grep -q "backend_net"; then
    if ! docker network rm backend_net &>/dev/null; then
        echo "Принудительное удаление контейнеров, подключенных к сети..."
        docker ps -a --filter "network=backend_net" -q | xargs -r docker rm -f &>/dev/null
        docker network rm backend_net &>/dev/null
    fi
    # Добавляем задержку, чтобы сеть успела полностью очиститься
    sleep 3
fi

echo
echo "[2/5] Очистка образов проекта..."
echo

# Удаление только образов проекта
echo "Удаляем образы проекта..."
docker images --filter "label=com.docker.compose.project=rmp-backend" -q | xargs -r docker rmi -f

echo
echo "[3/5] Проверка портов проекта..."
echo

# Проверка и освобождение портов проекта
for port in {9080..9090}; do
    if lsof -i :$port > /dev/null; then
        echo "Порт $port занят. Пытаемся освободить..."
        lsof -ti :$port | xargs kill -9 2>/dev/null || true
        sleep 2
    fi
done

echo
echo "[4/5] Сборка проекта с использованием кэша..."
echo

# Сборка проекта с использованием кэша
docker-compose build --parallel
if [ $? -ne 0 ]; then
    echo "[ОШИБКА] Сборка проекта не удалась"
    exit 1
fi

echo
echo "[5/5] Запуск сервисов..."
echo

# Запуск сервисов
docker-compose up -d
if [ $? -ne 0 ]; then
    echo "[ОШИБКА] Запуск сервисов не удался"
    exit 1
fi

echo
echo "============================================"
echo "Сборка и запуск проекта успешно завершены"
echo "============================================"
echo
echo "Полезные команды:"
echo "docker-compose logs -f    - просмотр логов всех сервисов"
echo "docker-compose ps         - статус контейнеров"
echo "docker-compose down       - остановка всех сервисов"
echo
echo "Для разработки:"
echo "docker-compose up -d      - запуск в фоновом режиме"
echo "docker-compose up         - запуск с выводом логов"
echo
echo "Для мониторинга:"
echo "docker stats             - мониторинг ресурсов"
echo "docker system df         - использование диска"
echo