#!/usr/bin/env bash
# Установка библиотеки в кластер.
#
# Порядок не случаен и не сводится к одному вызову helm:
#   1. Операторы и шлюз — кластерные, живут в своих пространствах имён и переживают
#      переустановку приложения. Они же приносят описания ресурсов (CRD), без которых
#      объекты базы и очереди просто не с чем сопоставить.
#   2. Платформа — окружение: база, очередь, хранилище, учётные записи, вход.
#   3. Сервисы — пять отдельных релизов одного чарта. Единица выката здесь сервис,
#      а не приложение целиком: откатывать каталог, не трогая хранилище, нужно уметь.
#
# Внешний адрес известен не заранее: его выдаёт балансировщик после того, как шлюз
# создан. А он нужен и в токенах (издатель), и в адресе возврата после входа —
# поэтому платформа ставится дважды: сначала чтобы шлюз получил адрес, потом с ним.
# В настоящем окружении этого шага нет: там адрес — заранее известное имя.
set -euo pipefail

cd "$(dirname "$0")"
NAMESPACE="${NAMESPACE:-bookcase}"
VERSION="${VERSION:-0.1.0}"
TIMEOUT="${TIMEOUT:-10m}"
EXTERNAL_URL="${EXTERNAL_URL:-}"

echo "── 1. Операторы и шлюз"
helm upgrade --install envoy-gateway oci://docker.io/envoyproxy/gateway-helm \
    --version v1.8.3 --namespace envoy-gateway-system --create-namespace --wait --timeout "$TIMEOUT"
helm upgrade --install cnpg cnpg/cloudnative-pg \
    --version 0.29.0 --namespace cnpg-system --create-namespace --wait --timeout "$TIMEOUT"
helm upgrade --install strimzi strimzi/strimzi-kafka-operator \
    --version 1.1.0 --namespace strimzi-system --create-namespace --wait --timeout "$TIMEOUT" \
    --set watchAnyNamespace=true

echo "── 2. Описания ресурсов на месте?"
# Объекты базы и очереди появятся, только когда кластер знает их вид. Ожидание явное:
# без него первая же установка платформы падает на «no matches for kind».
kubectl wait --for=condition=established --timeout=120s \
    crd/clusters.postgresql.cnpg.io crd/kafkas.kafka.strimzi.io \
    crd/gateways.gateway.networking.k8s.io >/dev/null

echo "── 3. Окружение"
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
helm dependency build platform >/dev/null
helm upgrade --install platform ./platform \
    --namespace "$NAMESPACE" --wait --timeout "$TIMEOUT" \
    ${GOOGLE_BOOKS_API_KEY:+--set secrets.googleBooksApiKey="$GOOGLE_BOOKS_API_KEY"}

if [ -z "$EXTERNAL_URL" ]; then
    echo "── 4. Ждём адрес от балансировщика"
    for _ in $(seq 60); do
        address=$(kubectl get svc -n envoy-gateway-system \
            -l "gateway.envoyproxy.io/owning-gateway-name=bookcase" \
            -o jsonpath='{.items[0].status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)
        [ -n "$address" ] && break
        sleep 5
    done
    if [ -z "$address" ]; then
        echo "Адрес не выдан. В kind это делает cloud-provider-kind — запустите его рядом:"
        echo "    cloud-provider-kind &"
        exit 1
    fi
    EXTERNAL_URL="http://${address}"
fi
echo "   внешний адрес: $EXTERNAL_URL"

echo "── 5. Окружение с внешним адресом"
helm upgrade --install platform ./platform \
    --namespace "$NAMESPACE" --wait --timeout "$TIMEOUT" \
    --set global.externalUrl="$EXTERNAL_URL" --set externalUrl="$EXTERNAL_URL" \
    ${GOOGLE_BOOKS_API_KEY:+--set secrets.googleBooksApiKey="$GOOGLE_BOOKS_API_KEY"}

# Шлюз входа читает свои настройки один раз при запуске, а адрес в них меняется на
# предыдущем шаге. При первой установке порядок совпадает сам собой, при повторной —
# нет: под остаётся с прежним адресом издателя и отвечает отказом на верные токены.
kubectl rollout restart deployment/platform-oauth2-proxy -n "$NAMESPACE" >/dev/null 2>&1 || true
kubectl rollout status deployment/platform-oauth2-proxy -n "$NAMESPACE" --timeout=5m >/dev/null

echo "── 6. Сервисы"
for service in catalog storage ingester enricher web; do
    helm upgrade --install "$service" ./service \
        --namespace "$NAMESPACE" \
        -f "values/common.yaml" -f "values/${service}.yaml" \
        --set image.tag="$VERSION" \
        --set env.OIDC_ISSUER="${EXTERNAL_URL}/realms/bookcase" \
        --set env.ALLOWED_ORIGINS="$EXTERNAL_URL" \
        ${service:+$([ "$service" = catalog ] && echo "--set env.STORAGE_PUBLIC_URL=$EXTERNAL_URL")} \
        --wait --timeout "$TIMEOUT"
done

echo
kubectl get pods -n "$NAMESPACE" --no-headers | awk '{printf "  %-52s %s %s\n", $1, $2, $3}'
echo
echo "Библиотека: $EXTERNAL_URL"
