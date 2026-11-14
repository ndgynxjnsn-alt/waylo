plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

description = "Waylo gateway: REST API in front of the DHL UDP gateway"

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":protocol"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":dhl-mock"))
}

tasks.bootJar {
    archiveFileName.set("waylo-gateway.jar")
}

tasks.jar {
    enabled = false // only the executable boot jar
}
