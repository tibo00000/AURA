plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room3") version "3.0.0-alpha01"
    kotlin("plugin.serialization")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        moduleName = "shared"
        browser {
            commonWebpackConfig {
                outputFileName = "shared.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)

                // Coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

                // Room & SQLite (Uniquement l'interface commune, sans le moteur C)
                api("androidx.room3:room3-runtime:3.0.0-alpha01")
                api("androidx.sqlite:sqlite:2.5.0")

                // Coil 3 KMP
                api("io.coil-kt.coil3:coil-compose:3.0.0-alpha06")
                api("io.coil-kt.coil3:coil-network-ktor3:3.0.0-alpha06")

                // Serialization KMP
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

                // Ktor for network requests
                api("io.ktor:ktor-client-core:3.0.0")
                implementation("io.ktor:ktor-client-content-negotiation:3.0.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")
            }
        }

        val androidMain by getting {
            dependencies {
                implementation("androidx.core:core-ktx:1.13.1")
                implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

                // Moteur SQLite C embarqué pour Android
                implementation("androidx.sqlite:sqlite-bundled:2.5.0")

                // Ktor client OkHttp engine for Android
                implementation("io.ktor:ktor-client-okhttp:3.0.0")
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.windows_x64)
                runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-windows-arm64:0.8.15")

                // Moteur SQLite C embarqué pour le Bureau
                implementation("androidx.sqlite:sqlite-bundled:2.5.0")

                // Ktor client OkHttp engine for Desktop
                implementation("io.ktor:ktor-client-okhttp:3.0.0")

                // JavaFX Media for native JNI audio playback
                val osName = System.getProperty("os.name").lowercase()
                val classifier = when {
                    osName.contains("win") -> "win"
                    osName.contains("mac") -> {
                        val arch = System.getProperty("os.arch").lowercase()
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

        val wasmJsMain by getting {
            dependencies {
                // Pilote SQLite OPFS spécifique au Web
                implementation("androidx.sqlite:sqlite-web:2.5.0")
            }
        }
    }
}

android {
    namespace = "com.aura.music.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", "androidx.room3:room3-compiler:3.0.0-alpha01")
    add("kspDesktop", "androidx.room3:room3-compiler:3.0.0-alpha01")
    add("kspWasmJs", "androidx.room3:room3-compiler:3.0.0-alpha01")
}
