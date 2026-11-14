plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "WPP v1 wire protocol: immutable message model, codec and Kaitai Struct definition"

// ---------------------------------------------------------------------------------------------
// Kaitai Struct: compile the .ksy into a Java parser that the tests use as an independent oracle
// for what the Kotlin codec puts on the wire.
// ---------------------------------------------------------------------------------------------
val kaitaiCompiler: Configuration = configurations.create("kaitaiCompiler")

dependencies {
    kaitaiCompiler(libs.kaitai.compiler)
    testImplementation(libs.kaitai.runtime)
}

val ksyDir = layout.projectDirectory.dir("src/main/kaitai")
val kaitaiOut = layout.buildDirectory.dir("generated/kaitai")

val generateKaitaiParser = tasks.register<JavaExec>("generateKaitaiParser") {
    description = "Compiles the .ksy protocol definition to a Java parser (test oracle)."
    group = "build"
    classpath = kaitaiCompiler
    mainClass.set("io.kaitai.struct.JavaMain")
    inputs.dir(ksyDir)
    outputs.dir(kaitaiOut)
    val ksyFiles = ksyDir.asFileTree.matching { include("*.ksy") }
    doFirst { kaitaiOut.get().asFile.deleteRecursively() }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--target", "java",
            "--java-package", "dev.waylo.protocol.kaitai",
            "--outdir", kaitaiOut.get().asFile.absolutePath,
            "--ksc-exceptions",
        ) + ksyFiles.files.map { it.absolutePath }
    })
}

sourceSets.test {
    java.srcDir(generateKaitaiParser.map { kaitaiOut.get() })
}
