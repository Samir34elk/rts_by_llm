dependencies {
    implementation(project(":rts-core"))
    implementation(project(":rts-analyzer"))
    implementation(project(":rts-change"))
    implementation(project(":rts-llm"))
    implementation("org.slf4j:slf4j-api:2.0.12")
    testImplementation(project(":rts-analyzer"))
}
