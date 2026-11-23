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

rootProject.name = "Lectora"

include(":app")
include(":core:model")
include(":core:database")
include(":engine:text")
include(":engine:translation")
include(":engine:tts")
include(":engine:tts-neural")
include(":third-party:sherpa-onnx")
include(":narration")
