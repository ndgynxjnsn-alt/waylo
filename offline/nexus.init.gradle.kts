// Gradle init script: resolve plugins AND dependencies only from the company Nexus Maven group,
// instead of the Gradle Plugin Portal / Maven Central declared by the build.
//
//   gradle --init-script offline/nexus.init.gradle.kts build
//
// Reads NEXUS_URL, GROUP_REPO (default maven-public) and optionally NEXUS_USER / NEXUS_PASSWORD.
// Used by offline/build-with-nexus.sh.

val nexusUrl = System.getenv("NEXUS_URL")?.trimEnd('/')
    ?: throw GradleException("NEXUS_URL is not set")
val groupUrl = "$nexusUrl/repository/${System.getenv("GROUP_REPO") ?: "maven-public"}/"

fun RepositoryHandler.onlyNexus() {
    clear()
    maven {
        name = "nexus"
        url = uri(groupUrl)
        isAllowInsecureProtocol = groupUrl.startsWith("http://")
        System.getenv("NEXUS_USER")?.let { user ->
            credentials {
                username = user
                password = System.getenv("NEXUS_PASSWORD")
            }
        }
    }
}

// After settings.gradle.kts ran, so its repositories are replaced, not just extended.
// pluginManagement: where `plugins { id("org.jetbrains.kotlin.jvm") }` is looked up (plugin markers).
// dependencyResolutionManagement: where all project dependencies come from.
settingsEvaluated {
    pluginManagement.repositories.onlyNexus()
    dependencyResolutionManagement.repositories.onlyNexus()
}
