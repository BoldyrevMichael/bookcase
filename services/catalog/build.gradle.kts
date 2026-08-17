plugins {
    id("bookcase.service-conventions")
}

dependencies {
    // Проверка токена — общая для всех сервисов: подпись, издатель, получатель, срок.
    implementation(project(":shared:security"))
    // Описания событий: их отправляет ingester, а catalog читает.
    implementation(project(":shared:events"))

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
}
