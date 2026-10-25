pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // HookTool（HChenX）经 JitPack 分发
        maven("https://jitpack.io")
    }
}

rootProject.name = "HyperIsleOpen"
include(":app")
