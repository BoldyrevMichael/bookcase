import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    java
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val lombok = libs.findLibrary("lombok").orElseThrow()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.findVersion("java").orElseThrow().requiredVersion)
    }
}

dependencies {
    compileOnly(lombok)
    annotationProcessor(lombok)
    testCompileOnly(lombok)
    testAnnotationProcessor(lombok)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // -Xlint:-processing глушит предупреждение о том, что ни один обработчик не заявил права
    // на аннотации: Lombok — единственный обработчик, и в тестовых классах его аннотаций нет.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
