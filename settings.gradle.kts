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
        if (providers.gradleProperty("useN2K02Libraries").orNull == "true") {
            val token = providers.environmentVariable("N2K02_PACKAGES_TOKEN").orNull
            require(!token.isNullOrBlank()) { "N2K02_PACKAGES_TOKEN is required" }
            listOf("n2k02theme" to "theme", "n2k02updater" to "updater").forEach { (repo, artifact) ->
                maven {
                    name = "N2K02_" + artifact
                    url = uri("https://maven.pkg.github.com/N2K02/$repo")
                    credentials {
                        username = "N2K02"
                        password = token
                    }
                    content { includeModule("dev.n2k02", artifact) }
                }
            }
        }
    }
}

rootProject.name = "PCL"
include(":app")
