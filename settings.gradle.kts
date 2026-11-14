rootProject.name = "waylo"

include("protocol", "dhl-mock", "gateway")

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
