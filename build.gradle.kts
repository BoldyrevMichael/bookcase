// Корневой проект собственного кода не содержит.
// Здесь только сводные задачи, которые проходят по всем модулям.

plugins {
    alias(libs.plugins.sonarqube)
}

tasks.register("qualityReport") {
    group = "verification"
    description = "Прогоняет проверки во всех модулях и собирает отчёты о покрытии."
    dependsOn(subprojects.map { "${it.path}:check" })
}

tasks.register("sbom") {
    group = "build"
    description = "Строит перечень состава для каждого сервиса."
    dependsOn(
        subprojects
            .filter { it.path.startsWith(":services:") }
            .map { "${it.path}:cyclonedxBom" }
    )
}

// ── SonarQube ────────────────────────────────────────────────────────────────
// Адрес сервера и ключ доступа берутся из окружения (SONAR_HOST_URL, SONAR_TOKEN):
// в файле сборки им не место. Как поднять сервер рядом — tools/sonar/compose.yaml.
//
// Модули плагин обходит сам: он видит исходники, тесты и отчёты каждого. Здесь
// добавлено только то, чего он знать не может.
sonar {
    properties {
        property("sonar.projectKey", "bookcase")
        property("sonar.projectName", "bookcase")
        property("sonar.projectVersion", version.toString())
        // Страницы — такой же код, как остальной: разметка, стили и сценарии
        // проверяются теми же правилами. У корневого проекта своих исходников нет,
        // поэтому каталог указан ему.
        property("sonar.sources", "web")
        // Страницы разбираются правилами языка, но в измерение покрытия не входят:
        // автоматических проверок для них нет, и без этой строки каждая строка разметки
        // и сценария считается непокрытой, а общая доля перестаёт что-либо означать.
        property("sonar.coverage.exclusions", "web/**")
        // Замечания checkstyle и SpotBugs приезжают в Sonar готовыми: набор правил
        // живёт в config/ и проходит через ревью, а не настраивается в интерфейсе.
        // Пути перечислены по модулям — общего отчёта у Gradle нет.
        property(
            "sonar.java.checkstyle.reportPaths",
            subprojects.flatMap { project ->
                listOf("main", "test").map {
                    "${project.projectDir}/build/reports/checkstyle/$it.xml"
                }
            }.joinToString(",")
        )
        property(
            "sonar.java.spotbugs.reportPaths",
            subprojects.flatMap { project ->
                listOf("main", "test").map {
                    "${project.projectDir}/build/reports/spotbugs/$it.xml"
                }
            }.joinToString(",")
        )
    }
}
