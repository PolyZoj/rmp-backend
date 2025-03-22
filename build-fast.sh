#!/bin/bash
# Скрипт быстрой сборки и запуска проекта

# Установка переменных окружения для ускорения сборки
export DOCKER_BUILDKIT=1
export COMPOSE_DOCKER_CLI_BUILD=1

echo "Начинаем оптимизированную сборку проекта..."

# Очистка только контейнеров и томов текущего проекта
echo "Очистка предыдущих контейнеров проекта..."
docker-compose down

echo "Очистка неиспользуемых томов проекта..."
docker volume prune -f --filter "label=com.docker.compose.project=rmp-backend"

echo "Запускаем параллельную сборку микросервисов..."
docker-compose build --parallel

echo "Сборка завершена. Запускаем сервисы..."
docker-compose up -d

echo "Проект запущен!"
echo "Используйте 'docker-compose logs -f' для просмотра логов."
echo "Для разработки можно использовать 'docker-compose -f docker-compose.yml -f docker-compose.dev.yml up -d'"