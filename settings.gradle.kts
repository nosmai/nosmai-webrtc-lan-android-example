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
        // The Nosmai SDK is an .aar attached to its GitHub release; this resolves it
        // directly, so there is nothing to download by hand.
        ivy {
            url = uri("https://github.com/nosmai/nosmai_effects_sdk_android/releases/download")
            patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.nosmai", "nosmai-sdk") }
        }
    }
}

rootProject.name = "NosmaiWebRTCDemo"
include(":app")
