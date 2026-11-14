plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

description = "Mock of the external DHL gateway: answers WPP v1 datagrams over UDP"

dependencies {
    implementation(project(":protocol"))
}

application {
    mainClass.set("dev.waylo.mock.MainKt")
    applicationName = "dhl-mock"
}
