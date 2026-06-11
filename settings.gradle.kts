pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        ivy {
            url = java.net.URI("https://nodejs.org/dist")
            patternLayout {
                artifact("[revision]/[artifact]-[revision]-[classifier].[ext]")
            }
            metadataSources {
                artifact()
            }
        }
    }
}

rootProject.name = "AURA"
include(":shared")
include(":androidApp")
include(":desktopApp")
include(":webApp")
