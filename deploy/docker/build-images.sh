#!/usr/bin/env bash
# Сборка образов всех сервисов и страниц.
#
# Перед запуском нужен собранный код: ./gradlew assemble. Разделение намеренное —
# образ упаковывает то, что уже прошло тесты и анализаторы, а не собирает заново.
set -euo pipefail

cd "$(dirname "$0")/../.."
VERSION="${VERSION:-$(grep '^version=' gradle.properties | cut -d= -f2)}"
REGISTRY="${REGISTRY:-bookcase}"

for service in catalog storage ingester enricher; do
    jar="services/${service}/build/libs/${service}-${VERSION}.jar"
    [ -f "$jar" ] || { echo "нет архива $jar — сначала ./gradlew assemble"; exit 1; }
    echo "── ${service}"
    docker build -f deploy/docker/service.Dockerfile \
        --build-arg "JAR_FILE=${jar}" \
        --build-arg "SERVICE=${service}" \
        --build-arg "VERSION=${VERSION}" \
        -t "${REGISTRY}/${service}:${VERSION}" .
done

echo "── web"
docker build -f deploy/docker/web.Dockerfile --build-arg "VERSION=${VERSION}" \
    -t "${REGISTRY}/web:${VERSION}" .

echo
docker images --filter "reference=${REGISTRY}/*:${VERSION}" \
    --format '{{.Repository}}:{{.Tag}}\t{{.Size}}'
