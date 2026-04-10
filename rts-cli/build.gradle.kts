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

tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = "com.rts.cli.RtsCommand"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
