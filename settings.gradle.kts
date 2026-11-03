pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Rokid Maven repo selection, per the CXR-L Quick Start. Use "external" off the Rokid corporate network.
val cxrRepoEnvironment = providers.gradleProperty("cxrRepoEnv")
    .orElse("external")
    .get()
    .lowercase()

require(cxrRepoEnvironment in setOf("internal", "external")) {
    "Invalid cxrRepoEnv '$cxrRepoEnvironment'. Allowed values: internal, external."
}

val cxrRepositoryUrl = when (cxrRepoEnvironment) {
    "internal" -> "https://maven.rokid-inc.com/repository/maven-public/"
    else -> "https://maven.rokid.com/repository/maven-public/"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository { maven(cxrRepositoryUrl) }
            filter { includeGroup("com.rokid.cxr") }
        }
    }
}

rootProject.name = "885rokidOS"

include(":protocol")
include(":phone")
include(":glasses")
include(":tools")
