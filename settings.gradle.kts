pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/") { content { includeGroup("de.robv.android.xposed") } }
    }
}
rootProject.name = "Easylsp"
include(":native-runtime", ":terminal-component", ":app", ":hook-runtime", ":plugin-api", ":demo-target", ":standalone-template")

include(":zygisk-host")

include(":native-entry")
