plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room3") version "3.0.0-alpha01"
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
                implementation("androidx.room3:room3-runtime:3.0.0-alpha01")
                implementation("androidx.sqlite:sqlite:2.5.0")

                // Coil 3 KMP
                implementation("io.coil-kt.coil3:coil-compose:3.0.0-alpha06")
                implementation("io.coil-kt.coil3:coil-network-ktor:3.0.0-alpha06")

                // Ktor for network requests
                implementation("io.ktor:ktor-client-core:3.0.0")
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
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.windows_x64)

                // Moteur SQLite C embarqué pour le Bureau
                implementation("androidx.sqlite:sqlite-bundled:2.5.0")
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