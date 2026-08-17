#!/usr/bin/env bash
# Проверки безопасности и настроек: зависимости, файлы репозитория, манифесты, образы.
#
# Четыре шага независимы и запускаются по отдельности:
#     tools/scan.sh deps      зависимости по перечню состава (SBOM)
#     tools/scan.sh repo      секреты и настройки в файлах репозитория
#     tools/scan.sh charts    собранные манифесты: kube-linter и trivy
#     tools/scan.sh images    собранные образы
#     tools/scan.sh           всё подряд
#
# Каждый шаг устроен одинаково: сначала полный отчёт в JSON — он остаётся в
# build/reports/quality и содержит находки любой важности, включая подавленные,
# с обоснованием из .trivyignore.yaml; затем «ворота» — отдельный проход по уже
# готовому отчёту, который заканчивается отказом, если после отбора по важности
# что-то осталось. Повторной проверки на воротах нет: `trivy convert` только
# перечитывает отчёт.
set -euo pipefail

cd "$(dirname "$0")/.."

REPORTS="build/reports/quality"
MANIFESTS="$REPORTS/manifests"
IGNORE=".trivyignore.yaml"
SERVICES="catalog storage ingester enricher"
IMAGES="catalog storage ingester enricher web"
VERSION="${VERSION:-0.1.0}"

mkdir -p "$MANIFESTS"

# Файл исключений передаётся явно в каждый вызов: сам Trivy находит только старый
# построчный .trivyignore, в котором нельзя записать, почему риск принят.
# --show-suppressed оставляет подавленные находки в отчёте с пометкой и обоснованием,
# иначе «чисто» неотличимо от «всё подавлено».
TRIVY_COMMON=(--ignorefile "$IGNORE" --show-suppressed)

gate() {
    # $1 — отчёт, дальше — дополнительные условия отбора.
    local report="$1"
    shift
    trivy convert --format table --show-suppressed --severity HIGH,CRITICAL --exit-code 1 "$@" "$report"
}

deps() {
    echo "── Зависимости"
    # Перечень состава строит сам Gradle и заново при каждом запуске: список
    # «библиотека → версия», который не может устареть, в отличие от файла с
    # зафиксированными версиями.
    ./gradlew sbom -q --console=plain
    for service in $SERVICES; do
        trivy sbom "${TRIVY_COMMON[@]}" --no-progress \
            --format json -o "$REPORTS/deps-$service.json" \
            "services/$service/build/reports/cyclonedx/application.cdx.json"
        echo "   $service"
        gate "$REPORTS/deps-$service.json"
    done
}

repo() {
    echo "── Файлы репозитория"
    # Чужие чарты пропускаются: они лежат архивами в deploy/helm/platform/charts,
    # проверяются собранными манифестами (шаг charts), а здесь дали бы сотню
    # находок в чужих шаблонах.
    trivy fs --scanners secret,misconfig "${TRIVY_COMMON[@]}" --no-progress \
        --skip-dirs deploy/helm/platform/charts --skip-dirs '**/build' --skip-dirs '**/.gradle' \
        --format json -o "$REPORTS/repo.json" .
    gate "$REPORTS/repo.json"
}

charts() {
    echo "── Манифесты"
    # Проверяется то, что действительно уедет в кластер, — собранные шаблоны,
    # а не значения по умолчанию. Проверочные поды чарта (`helm test`) пропущены:
    # при установке они не создаются.
    ( cd deploy/helm && helm dependency build platform >/dev/null )
    for service in $IMAGES; do
        helm template "$service" deploy/helm/service -n bookcase \
            -f deploy/helm/values/common.yaml -f "deploy/helm/values/$service.yaml" \
            --set image.tag="$VERSION" > "$MANIFESTS/$service.yaml"
    done
    helm template platform deploy/helm/platform -n bookcase --skip-tests > "$MANIFESTS/platform.yaml"

    # Свои рабочие нагрузки — полным набором проверок, чужие чарты — с двумя
    # исключениями, обоснование в config/kube-linter/platform.yaml.
    kube-linter lint --config config/kube-linter/services.yaml \
        $(for service in $IMAGES; do echo "$MANIFESTS/$service.yaml"; done)
    kube-linter lint --config config/kube-linter/platform.yaml "$MANIFESTS/platform.yaml"

    # Тот же вызов, что и на шаге repo, только по собранным манифестам: `trivy config`
    # — это и есть `trivy fs --scanners misconfig`, но подавленные находки он
    # показывать не умеет.
    trivy fs --scanners misconfig "${TRIVY_COMMON[@]}" --no-progress \
        --format json -o "$REPORTS/charts.json" "$MANIFESTS"
    gate "$REPORTS/charts.json"
}

images() {
    echo "── Образы"
    for image in $IMAGES; do
        trivy image "${TRIVY_COMMON[@]}" --no-progress \
            --format json -o "$REPORTS/image-$image.json" "bookcase/$image:$VERSION"
        echo "   $image"
        # Ворота здесь — отдельный проход по образу, а не по готовому отчёту:
        # `trivy convert` не умеет --ignore-unfixed. Слои уже разобраны и лежат
        # в кэше, так что второй проход стоит секунды.
        #
        # --ignore-unfixed: уязвимости, для которых исправления не существует,
        # ворота не роняют — устранить их нечем, а красные ворота, которые нельзя
        # починить, перестают что-либо значить. В полном отчёте они остаются.
        # --table-mode detailed убирает сводку по каждому jar внутри образа:
        # их полторы сотни, и ворота с нулём находок печатали бы экран таблиц.
        trivy image "${TRIVY_COMMON[@]}" --no-progress --format table --table-mode detailed \
            --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 "bookcase/$image:$VERSION"
    done
}

case "${1:-all}" in
    deps) deps ;;
    repo) repo ;;
    charts) charts ;;
    images) images ;;
    all) deps; repo; charts; images ;;
    *) echo "Неизвестный шаг: $1 (deps | repo | charts | images)" >&2; exit 2 ;;
esac

echo
echo "Отчёты: $REPORTS"
