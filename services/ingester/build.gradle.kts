plugins {
    id("bookcase.service-conventions")
}

dependencies {
    // Проверка токена — общая для всех сервисов: подпись, издатель, получатель, срок.
    implementation(project(":shared:security"))
    // Описания событий: их же читает catalog.
    implementation(project(":shared:events"))
    // Приведение значений метаданных к общему виду: тем же кодом пользуется enricher.
    implementation(project(":shared:metadata"))

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    // Как и у Liquibase, автонастройка Kafka в Boot 4 вынесена в отдельный модуль:
    // одной библиотеки spring-kafka на classpath недостаточно.
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    runtimeOnly("org.postgresql:postgresql")

    implementation(libs.pdfbox)
    implementation(libs.icu4j)

    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
}
