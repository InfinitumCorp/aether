pluginManagement {
    repositories {
        // Google Maven via Google's CDN (dl.google.com returns 404 from this network).
        maven("https://redirector.gvt1.com/edgedl/android/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://redirector.gvt1.com/edgedl/android/maven2/")
        mavenCentral()
    }
}

rootProject.name = "aether"
include(":app")
