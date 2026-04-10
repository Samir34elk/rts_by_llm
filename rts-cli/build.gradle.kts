import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    application
}

dependencies {
    implementation(project(":rts-core"))
    implementation(project(":rts-analyzer"))
    implementation(project(":rts-change"))
    implementation(project(":rts-selector"))
    implementation(project(":rts-llm"))
    implementation("info.picocli:picocli:4.7.6")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.0")
    implementation("org.slf4j:slf4j-api:2.0.12")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.12")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")
}

application {
    mainClass.set("com.rts.cli.RtsCommand")
}

/**
 * Strips JAR signing metadata (*.SF, *.RSA, *.DSA) from every JAR in the
 * distribution lib/ directory after installDist copies them.
 * Needed because JGit ships signed with an Eclipse certificate that the
 * JVM rejects when loaded from a plain application classpath.
 */
tasks.named("installDist") {
    doLast {
        val libDir = layout.buildDirectory.dir("install/${project.name}/lib").get().asFile
        libDir.listFiles { f -> f.extension == "jar" }?.forEach { jar ->
            stripJarSignatures(jar)
        }
    }
}

fun isSigEntry(name: String): Boolean {
    val u = name.uppercase()
    return u.startsWith("META-INF/") &&
        (u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA") || u.endsWith(".EC"))
}

fun stripJarSignatures(jar: File) {
    val hasSig = ZipFile(jar).use { zf ->
        zf.entries().asSequence().any { isSigEntry(it.name) }
    }
    if (!hasSig) return

    logger.lifecycle("Stripping JAR signatures from ${jar.name}")
    val tmp = File(jar.parentFile, jar.name + ".tmp")
    ZipFile(jar).use { zf ->
        ZipOutputStream(tmp.outputStream().buffered()).use { out ->
            zf.entries().asSequence()
                .filter { !isSigEntry(it.name) }
                .forEach { entry ->
                    out.putNextEntry(ZipEntry(entry.name))
                    zf.getInputStream(entry).copyTo(out)
                    out.closeEntry()
                }
        }
    }
    jar.delete()
    tmp.renameTo(jar)
}
