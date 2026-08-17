import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    // Модуль — библиотека для сервисов, а не самостоятельная программа: часть
    // зависимостей он передаёт потребителям, для этого и нужен java-library.
    `java-library`
    id("bookcase.java-conventions")
    id("bookcase.quality-conventions")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    api(platform(libs.findLibrary("springBootDependencies").orElseThrow()))

    // Настройка расходится по сервисам вместе с этим модулем, поэтому нужные
    // библиотеки он передаёт дальше сам.
    api("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Модуль — библиотека, а не сервис: соглашение сервиса сюда не подключено,
    // и запускатель проверок приходится назвать явно.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
