import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    // Библиотека для сервисов, а не самостоятельная программа.
    `java-library`
    id("bookcase.java-conventions")
    id("bookcase.quality-conventions")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    api(platform(libs.findLibrary("springBootDependencies").orElseThrow()))

    // Содержимое модуля — чистые преобразования строк; от Spring нужна лишь возможность
    // объявить их бинами самостоятельно, не полагаясь на сканирование пакетов потребителем.
    api("org.springframework.boot:spring-boot-autoconfigure")
}
