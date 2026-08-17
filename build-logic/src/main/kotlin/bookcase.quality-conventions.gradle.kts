import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

plugins {
    java
    checkstyle
    jacoco
    id("com.github.spotbugs")
    id("com.diffplug.spotless")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// ── Формат кода ──────────────────────────────────────────────────────────────
// Раскладку кода задаёт форматтер, а не Checkstyle: отступы, переносы и
// положение скобок применяются машинально. Режим AOSP — тот же
// google-java-format, но с отступом в четыре пробела и продолжением строки
// в восемь; ширина строки в нём 100 — столько же держит LineLength.
//
// enforceCheck не выключается: репозиторий один и отформатирован с самого
// начала, поэтому spotlessCheck остаётся частью обычной check. Выключать его
// имеет смысл там, где конвенция раздаётся чужим проектам со старым кодом.
spotless {
    java {
        googleJavaFormat(libs.findVersion("googleJavaFormat").orElseThrow().requiredVersion).aosp()
    }
}

// ── Checkstyle ───────────────────────────────────────────────────────────────
// Правила общие для всех модулей. Подавления складываются из двух слоёв, оба
// необязательные: общий config/checkstyle/suppressions.xml и собственный
// config/checkstyle/suppressions.xml рядом с модулем.
checkstyle {
    toolVersion = libs.findVersion("checkstyle").orElseThrow().requiredVersion
    maxWarnings = 0
    maxErrors = 0
    isIgnoreFailures = false
    configProperties = mapOf(
        "module_config" to layout.projectDirectory.dir("config/checkstyle").asFile.absolutePath
    )
}

tasks.withType<Checkstyle>().configureEach {
    reports {
        xml.required = true
        html.required = true
    }
}

// ── SpotBugs и FindSecBugs ───────────────────────────────────────────────────
// Исключения двухслойные: общие из корня репозитория плюс собственные
// config/spotbugs/exclude.xml модуля, если такой файл есть. Формат фильтра —
// список <Match>, поэтому слияние сводится к объединению списков.
val sharedExcludes = rootProject.layout.projectDirectory.file("config/spotbugs/exclude.xml").asFile
val moduleExcludes = layout.projectDirectory.file("config/spotbugs/exclude.xml").asFile
val mergedExcludes = layout.buildDirectory.file("quality/spotbugs-exclude.xml")

val mergeSpotbugsExcludes = tasks.register("mergeSpotbugsExcludes") {
    group = "verification"
    description = "Складывает общие исключения SpotBugs с собственными исключениями модуля."
    inputs.file(sharedExcludes)
    if (moduleExcludes.exists()) {
        inputs.file(moduleExcludes)
    }
    outputs.file(mergedExcludes)

    val shared = sharedExcludes
    val own = moduleExcludes
    val target = mergedExcludes

    doLast {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val merged = builder.parse(shared)
        if (own.exists()) {
            val children = builder.parse(own).documentElement.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node.nodeType == Node.ELEMENT_NODE) {
                    merged.documentElement.appendChild(merged.importNode(node, true))
                }
            }
        }
        val file = target.get().asFile
        file.parentFile.mkdirs()
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        file.outputStream().use { stream -> transformer.transform(DOMSource(merged), StreamResult(stream)) }
    }
}

spotbugs {
    toolVersion = libs.findVersion("spotbugs").orElseThrow().requiredVersion
    effort = Effort.MAX
    reportLevel = Confidence.LOW
}

tasks.withType<SpotBugsTask>().configureEach {
    dependsOn(mergeSpotbugsExcludes)
    excludeFilter = mergedExcludes
    reports.create("xml") { required = true }
    reports.create("html") { required = true }
}

// ── Покрытие ─────────────────────────────────────────────────────────────────
jacoco {
    toolVersion = libs.findVersion("jacoco").orElseThrow().requiredVersion
}

dependencies {
    spotbugsPlugins(libs.findLibrary("findsecbugs").orElseThrow())
    // Аннотация @SuppressFBWarnings для точечного подавления в коде.
    compileOnly(libs.findLibrary("spotbugsAnnotations").orElseThrow())
    testCompileOnly(libs.findLibrary("spotbugsAnnotations").orElseThrow())
}

tasks.named<Test>("test") {
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required = true
        html.required = true
        csv.required = false
    }
}
