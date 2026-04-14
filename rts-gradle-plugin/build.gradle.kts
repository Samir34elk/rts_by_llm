plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group   = "com.rts"
version = "1.0.0"

// java-gradle-plugin already applies `java`, so the root subprojects block
// (which also applies `java`) is harmless here.

repositories {
    mavenCentral()
}

dependencies {
    // RTS modules — all bundled as implementation deps so the plugin is self-contained
    implementation(project(":rts-core"))
    implementation(project(":rts-analyzer"))
    implementation(project(":rts-change"))
    implementation(project(":rts-selector"))

    // Logging implementation (SLF4J simple) — needed at plugin runtime
    runtimeOnly("org.slf4j:slf4j-simple:2.0.12")
}

gradlePlugin {
    plugins {
        create("rtsPlugin") {
            id                  = "com.rts.plugin"
            implementationClass = "com.rts.gradle.RtsPlugin"
            displayName         = "RTS — Regression Test Selection"
            description         = "Selects and runs only the tests impacted by recent code changes."
        }
    }
}

// Allow `./gradlew :rts-gradle-plugin:publishToMavenLocal` for external project use
publishing {
    publications {
        create<MavenPublication>("pluginMaven") {
            groupId    = "com.rts"
            artifactId = "rts-gradle-plugin"
            version    = "1.0.0"
        }
    }
}
