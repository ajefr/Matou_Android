import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.ajefr.matou"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.ajefr.matou"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signée avec la clé de debug pour pouvoir installer l'APK de test directement.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/*/OSGI-INF/MANIFEST.MF", "META-INF/*.md")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    // Client SSH (fork maintenu de JSch : ed25519, clés OpenSSH et PuTTY .ppk, redirection de ports)
    implementation("com.github.mwiede:jsch:2.27.9")
    // Fournit Ed25519/X25519 et Argon2 (clés .ppk v3) sur les anciennes versions d'Android
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
}
