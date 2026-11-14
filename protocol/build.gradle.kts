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

val kaitaiOut = layout.buildDirectory.dir("generated/kaitai")

val generateKaitaiParser = tasks.register<JavaExec>("generateKaitaiParser") {
    description = "Compiles the .ksy protocol definition to a Java parser (test oracle)."
    group = "build"
    val ksyFiles = layout.projectDirectory.dir("src/main/kaitai").asFileTree.matching { include("*.ksy") }
    val outDir = kaitaiOut.get().asFile
    classpath = kaitaiCompiler
    mainClass.set("io.kaitai.struct.JavaMain")
    inputs.files(ksyFiles)
    outputs.dir(outDir)
    doFirst { outDir.deleteRecursively() }
    args(
        "--target", "java",
        "--java-package", "dev.waylo.protocol.kaitai",
        "--outdir", outDir.absolutePath,
        "--ksc-exceptions",
    )
    args(ksyFiles.files.map { it.absolutePath })
}

sourceSets.test {
    java.srcDir(generateKaitaiParser)
}
