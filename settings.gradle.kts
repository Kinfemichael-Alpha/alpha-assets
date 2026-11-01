// ALPHA AI - የፕሮጀክት ቅንብር / project settings
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven { url = uri("https://jitpack.io"); content { includeGroup("cz.adaptech.tesseract4android") } }
    }
}
rootProject.name = "AlphaAI"
include(":app")
