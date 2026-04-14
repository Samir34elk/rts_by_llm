plugins {
    java
    id("com.rts.plugin")
}

group   = "com.example"
version = "1.0.0"

repositories { mavenCentral() }

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Optional: set RTS defaults so you don't need -P flags every time
rts {
    mode = "static"
}
