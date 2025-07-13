rootProject.name = "network"

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

include(":common")
include(":query")
include(":raknet")
include(":rcon")
