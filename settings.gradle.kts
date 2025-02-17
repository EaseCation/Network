rootProject.name = "network"

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("libs.versions.toml"))
        }
    }
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

include(":common")
include(":query")
include(":raknet")
include(":rcon")
