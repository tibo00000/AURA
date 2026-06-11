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
                implementation(compose.desktop.currentOs)
                
                // JNativeHook for global OS key shortcuts
                implementation("com.github.kwhat:jnativehook:2.2.2")
                
                // JavaFX Media for native JNI audio playback
                val osName = System.getProperty("os.name").lowercase()
                val classifier = when {
                    osName.contains("win") -> "win"
                    osName.contains("mac") -> {
                        // Check if Apple Silicon or Intel
                        val arch = System.getProperty("os.arch").lowercase()
                        if (arch.contains("aarch64") || arch.contains("arm64")) "mac-aarch64" else "mac"
                    }
                    osName.contains("nix") || osName.contains("nux") -> "linux"
                    else -> "win"
                }
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
            
            // Optimizations: Generational ZGC for low latency
            jvmArgs += listOf("-XX:+UseZGC")
        }
    }
}
