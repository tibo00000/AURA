plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvm("desktop")
    
    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":shared"))
                val osName = System.getProperty("os.name").lowercase()
                val arch = System.getProperty("os.arch").lowercase()

                // Compose Desktop runtime : Compose 1.7.0 ne publiant pas d'artefact windows-arm64,
                // toute machine Windows (x64 ou ARM64) cible l'artefact officiel windows_x64 (émulé nativement par Windows 11).
                if (osName.contains("win")) {
                    implementation(compose.desktop.windows_x64)
                } else {
                    implementation(compose.desktop.currentOs)
                }

                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
                
                // Native platform integration (DPAPI on Windows)
                implementation("net.java.dev.jna:jna:5.14.0")
                implementation("net.java.dev.jna:jna-platform:5.14.0")
                
                // JavaFX Media for native JNI audio playback
                val classifier = when {
                    osName.contains("win") -> "win"
                    osName.contains("mac") -> {
                        // Check if Apple Silicon or Intel
                        if (arch.contains("aarch64") || arch.contains("arm64")) "mac-aarch64" else "mac"
                    }
                    osName.contains("nix") || osName.contains("nux") -> "linux"
                    else -> "win"
                }
                implementation("org.openjfx:javafx-base:21.0.1")
                implementation("org.openjfx:javafx-base:21.0.1:$classifier")
                implementation("org.openjfx:javafx-graphics:21.0.1")
                implementation("org.openjfx:javafx-graphics:21.0.1:$classifier")
                implementation("org.openjfx:javafx-media:21.0.1")
                implementation("org.openjfx:javafx-media:21.0.1:$classifier")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.aura.music.desktop.MainKt"
        
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName = "AURA"
            packageVersion = "1.0.0"

            windows {
                menu = true
                shortcut = true
                // GUID immuable garantissant la détection et les Major Upgrades WiX
                upgradeUuid = "6C3B140B-22DF-4899-B943-2313EA8C1977"
                perUserInstall = true // Déploiement dans %LOCALAPPDATA% sans élévation UAC
                dirChooser = false
                iconFile.set(project.file("src/desktopMain/resources/aura_icon.ico"))
            }
            
            // Optimizations: Generational ZGC for low latency (JDK 21+)
            jvmArgs += listOf("-XX:+UseZGC", "-XX:+ZGenerational")
        }
    }
}
