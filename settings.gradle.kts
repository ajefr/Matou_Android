pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Matou"

// Le cœur du terminal (parseur VT, écran, protocole graphique) est du Kotlin pur,
// testable sur la JVM sans SDK Android : MATOU_CORE_ONLY=1 ./gradlew :core:test
include(":core")
if (System.getenv("MATOU_CORE_ONLY") == null) {
    include(":app")
}
