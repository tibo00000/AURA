// ── Workaround: KT-48420 — Windows ARM64 (Snapdragon) ──
// Le Kotlin Gradle Plugin 2.1.0 exécute `uname -m` (ProviderFactory.exec) lors de
// la détection de plateforme Node.js. Sur Windows ARM64, `uname` n'existe pas dans
// le PATH standard. On injecte le répertoire Git contenant uname.exe dans le PATH
// du processus JVM courant AVANT l'application des plugins.
if (System.getProperty("os.name")?.lowercase()?.contains("windows") == true) {
    val gitUnamePath = "C:\\Program Files\\Git\\usr\\bin"
    val currentPath = System.getenv("PATH") ?: ""
    if (!currentPath.contains(gitUnamePath)) {
        try {
            // Modifier le PATH du processus courant via reflection (JDK 17+)
            val processEnvClass = Class.forName("java.lang.ProcessEnvironment")
            val envField = processEnvClass.getDeclaredField("theCaseInsensitiveEnvironment")
            envField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val env = envField.get(null) as MutableMap<String, String>
            env["PATH"] = "$gitUnamePath;$currentPath"
        } catch (_: Exception) {
            // Fallback silencieux — l'utilisateur devra ajouter Git/usr/bin au PATH système
        }
    }
}

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
                artifact("v[revision]/[artifact]-v[revision]-[classifier].[ext]")
            }
            metadataSources {
                artifact()
            }
        }
        ivy {
            url = java.net.URI("https://github.com/yarnpkg/yarn/releases/download")
            patternLayout {
                artifact("v[revision]/[artifact]-v[revision].[ext]")
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
