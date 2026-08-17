#!/usr/bin/env bash
# Поднять кластер, загрузить в него образы и поставить библиотеку.
#
# Образы кладутся в кластер напрямую (kind load), а не тянутся из хранилища образов:
# своего хранилища у стенда нет, а публиковать туда сборки ради проверки незачем.
set -euo pipefail

cd "$(dirname "$0")"
CLUSTER="${CLUSTER:-bookcase}"
VERSION="${VERSION:-0.1.0}"

if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
    echo "── создаём кластер"
    kind create cluster --config cluster.yaml
fi
kubectl config use-context "kind-${CLUSTER}"

echo "── загружаем образы в кластер"
for image in catalog storage ingester enricher web; do
    kind load docker-image "bookcase/${image}:${VERSION}" --name "$CLUSTER"
done

echo "── ставим библиотеку"
../helm/install.sh

cat <<'NOTE'

Чтобы открыть библиотеку в браузере, нужен адрес для служб типа LoadBalancer:
в kind его выдаёт cloud-provider-kind — он запускается рядом с кластером
и в репозиторий попадает только этой строкой:

    cloud-provider-kind &

Так задумано: манифесты не подстраиваются под kind, служба шлюза остаётся
LoadBalancer, как и в настоящем кластере.
NOTE
