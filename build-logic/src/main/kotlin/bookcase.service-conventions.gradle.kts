import org.cyclonedx.gradle.CyclonedxDirectTask
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("bookcase.java-conventions")
    id("bookcase.quality-conventions")
    id("org.springframework.boot")
    id("org.cyclonedx.bom")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(platform(libs.findLibrary("springBootDependencies").orElseThrow()))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    // Сквозной идентификатор запроса. Он проставляется в каждую запись журнала, поэтому путь
    // одной книги через четыре сервиса читается как одна история, а не как четыре разрозненных,
    // и он же связывает записи журнала с трассой в Tempo.
    //
    // Связка — OpenTelemetry: это то, на чём сегодня сходится отрасль, её протокол понимают все
    // приёмники. Micrometer поддерживает и Brave, но развитие ушло сюда, а на проводах формат
    // один и тот же (заголовок W3C traceparent), так что выбор ни к чему не привязывает.
    //
    // Модулей три, и это неочевидно. Автонастройка Spring вынесена в отдельный модуль
    // (spring-boot-micrometer-tracing-opentelemetry) — как у Liquibase и Kafka в Boot 4; сама
    // связка живёт в micrometer-tracing-bridge-otel; отправитель по OTLP — третий. Без первого
    // ничего не собирается, без второго не находится реализация, без третьего трассы никуда не
    // уходят — и во всех трёх случаях молча, без единой ошибки в журнале.
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp") {
        // Отправляет OTLP по умолчанию через OkHttp, а тот приносит с собой Okio и
        // стандартную библиотеку Kotlin — три мегабайта в каждый образ ради одного
        // исходящего запроса раз в несколько секунд. В JDK клиент HTTP есть с 11-й
        // версии, и у OpenTelemetry для него готовый отправитель.
        exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
    }
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-sender-jdk")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// Сервис — не библиотека: зависеть от него неоткуда, а два архива в build/libs
// заставляют гадать, какой из них запускать.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.withType<CyclonedxDirectTask>().configureEach {
    // Без этого в перечень состава попадает сборочная обвязка — конфигурации анализаторов
    // и самих плагинов, — и проверка образа находит уязвимости в том, чего в образе нет.
    includeConfigs = listOf("runtimeClasspath")
    includeBuildEnvironment = false
}
