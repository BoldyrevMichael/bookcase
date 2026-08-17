plugins {
    id("bookcase.service-conventions")
}

dependencies {
    // Проверка токена — общая для всех сервисов: подпись, издатель, получатель, срок.
    implementation(project(":shared:security"))
    // Описания событий: просьбу собрать архив присылает catalog.
    implementation(project(":shared:events"))

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // В Boot 4 автонастройка Liquibase живёт в отдельном модуле: одной библиотеки
    // liquibase-core на classpath уже недостаточно, миграции просто не запустятся.
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    runtimeOnly("org.postgresql:postgresql")

    implementation(platform(libs.awsSdkBom))
    implementation("software.amazon.awssdk:s3")

    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}
