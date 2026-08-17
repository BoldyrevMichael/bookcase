plugins {
    id("bookcase.service-conventions")
}

dependencies {
    // Проверка токена — общая для всех сервисов: подпись, издатель, получатель, срок.
    implementation(project(":shared:security"))
    // Описания событий: book.added читаем, book.enriched пишем.
    implementation(project(":shared:events"))
    // Тот же нормализатор языка, что и у разбора файлов: справочники отдают язык
    // как «eng», в карточке он должен быть «en», иначе отбор по языку раздваивается.
    implementation(project(":shared:metadata"))

    // Служебная учётная запись: обложки кладутся в storage в фоне, когда токена
    // пользователя уже нет. Токен по client_credentials выписывает Keycloak.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    runtimeOnly("org.postgresql:postgresql")

    // Предохранитель и ограничитель скорости: единственный сервис, который ходит наружу,
    // и единственный, кому есть что предохранять.
    implementation(libs.resilience4j)
    // Аспекты, на которых работают аннотации resilience4j. В Boot 4 стартер называется
    // spring-boot-starter-aspectj: прежнего spring-boot-starter-aop в спецификации больше нет.
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
}
