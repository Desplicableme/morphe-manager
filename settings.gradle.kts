@file:Suppress("UnstableApiUsage")

import java.util.Properties

val localProps = Properties().apply {
    val file = rootDir.resolve("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun githubUser(): String? =
    localProps.getProperty("gpr.user")
        ?: providers.gradleProperty("gpr.user").orNull
        ?: System.getenv("GITHUB_ACTOR")

fun githubToken(): String? =
    localProps.getProperty("gpr.key")
        ?: providers.gradleProperty("gpr.key").orNull
        ?: System.getenv("GITHUB_TOKEN")

pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        mavenCentral()
        google()
        maven("https://jitpack.io") {
            // Only the libraries that are published there. Without this, every artifact that Central or Google does
            // not serve is also looked up on JitPack, which makes builds depend on that host for no reason.
            content { includeGroupByRegex("com\\.github\\..*") }
            metadataSources {
                mavenPom()
                artifact()
            }
        }
        maven {
            // A repository must be specified for some reason. "registry" is a dummy.
            url = uri("https://maven.pkg.github.com/MorpheApp/registry")
            // Only the Morphe libraries (the patcher and the library). Nothing else is asked from GitHub Packages.
            content { includeGroup("app.morphe") }
            credentials {
                val gprUser: String? = githubUser()
                val gprKey: String? = githubToken()

                username = gprUser.orEmpty().ifBlank { "anonymous" }
                password = gprKey.orEmpty()
            }
        }
    }
}

rootProject.name = "morphe-manager"
include(":app")
include(":patcher-core")

// Include morphe-patcher as a composite build if it exists locally
mapOf(
    "morphe-patcher" to "app.morphe:morphe-patcher",
//    "ARSCLib" to "com.github.MorpheApp:ARSCLib"
).forEach { (libraryPath, libraryName) ->
    val libDir = file("../$libraryPath")
    if (libDir.exists()) {
        includeBuild(libDir) {
            dependencySubstitution {
                substitute(module(libraryName)).using(project(":"))
            }
        }
    }
}
