pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "bookcase"

include(":services:catalog")
include(":services:storage")
include(":services:ingester")
include(":services:enricher")
include(":shared:events")
include(":shared:metadata")
include(":shared:security")
