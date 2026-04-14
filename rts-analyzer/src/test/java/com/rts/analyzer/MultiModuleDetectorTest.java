package com.rts.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultiModuleDetectorTest {

    private final MultiModuleDetector detector = new MultiModuleDetector();

    // ── Single-module fallback ────────────────────────────────────────────────

    @Test
    void detectModules_singleModule_returnsRoot(@TempDir Path root) {
        List<Path> modules = detector.detectModules(root);
        assertThat(modules).containsExactly(root);
    }

    @Test
    void detectModules_mavenSingleModulePom_returnsRoot(@TempDir Path root) throws Exception {
        // pom.xml with no <modules> element
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>my-app</artifactId>
                  <version>1.0</version>
                </project>
                """);
        assertThat(detector.detectModules(root)).containsExactly(root);
    }

    // ── Maven multi-module ────────────────────────────────────────────────────

    @Test
    void detectModules_mavenMultiModule_returnsRootAndSubmodules(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("module-api"));
        Files.createDirectories(root.resolve("module-impl"));

        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modules>
                    <module>module-api</module>
                    <module>module-impl</module>
                  </modules>
                </project>
                """);

        List<Path> modules = detector.detectModules(root);
        assertThat(modules).containsExactlyInAnyOrder(
                root,
                root.resolve("module-api"),
                root.resolve("module-impl")
        );
    }

    @Test
    void detectModules_maven_ignoresMissingModuleDirectory(@TempDir Path root) throws Exception {
        // module-impl directory does NOT exist
        Files.createDirectories(root.resolve("module-api"));

        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modules>
                    <module>module-api</module>
                    <module>module-impl</module>
                  </modules>
                </project>
                """);

        List<Path> modules = detector.detectModules(root);
        assertThat(modules)
                .contains(root, root.resolve("module-api"))
                .doesNotContain(root.resolve("module-impl"));
    }

    // ── Gradle Kotlin DSL ─────────────────────────────────────────────────────

    @Test
    void detectModules_gradleKts_singleLineInclude(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("core"));
        Files.createDirectories(root.resolve("service"));

        Files.writeString(root.resolve("settings.gradle.kts"), """
                rootProject.name = "my-app"
                include("core", "service")
                """);

        List<Path> modules = detector.detectModules(root);
        assertThat(modules).containsExactlyInAnyOrder(
                root,
                root.resolve("core"),
                root.resolve("service")
        );
    }

    @Test
    void detectModules_gradleKts_multiLineInclude(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("rts-core"));
        Files.createDirectories(root.resolve("rts-analyzer"));

        Files.writeString(root.resolve("settings.gradle.kts"), """
                rootProject.name = "rts-system"
                include(
                    "rts-core",
                    "rts-analyzer"
                )
                """);

        List<Path> modules = detector.detectModules(root);
        assertThat(modules).contains(
                root.resolve("rts-core"),
                root.resolve("rts-analyzer")
        );
    }

    @Test
    void detectModules_gradleKts_nestedModuleColonSyntax(@TempDir Path root) throws Exception {
        // "parent:child" maps to root/parent/child/
        Files.createDirectories(root.resolve("parent/child"));

        Files.writeString(root.resolve("settings.gradle.kts"), """
                include("parent:child")
                """);

        List<Path> modules = detector.detectModules(root);
        assertThat(modules).contains(root.resolve("parent/child"));
    }

    @Test
    void detectModules_gradleGroovy_androidStyleLeadingColon(@TempDir Path root) throws Exception {
        // Android projects use include ':app', ':features:base' (leading colon)
        Files.createDirectories(root.resolve("app"));
        Files.createDirectories(root.resolve("features/base"));
        Files.createDirectories(root.resolve("features/repositories"));

        Files.writeString(root.resolve("settings.gradle"), """
                include ':app', ':features:base', ':features:repositories'
                """);

        List<Path> modules = detector.detectModules(root);
        assertThat(modules).contains(
                root.resolve("app"),
                root.resolve("features/base"),
                root.resolve("features/repositories")
        );
    }

    @Test
    void detectModules_gradleGroovy_multiLineIncludeWithoutParens(@TempDir Path root) throws Exception {
        // Real-world Android format:
        //   include ':app',
        //           ':features:base',
        //           ':features:repositories'
        Files.createDirectories(root.resolve("app"));
        Files.createDirectories(root.resolve("features/base"));
        Files.createDirectories(root.resolve("features/repositories"));
        Files.createDirectories(root.resolve("features/repository"));

        Files.writeString(root.resolve("settings.gradle"),
                "include ':app',\n" +
                "        ':features:base',\n" +
                "        ':features:repositories',\n" +
                "        ':features:repository'\n");

        List<Path> modules = detector.detectModules(root);
        assertThat(modules).contains(
                root.resolve("app"),
                root.resolve("features/base"),
                root.resolve("features/repositories"),
                root.resolve("features/repository")
        );
    }

    // ── Gradle Groovy DSL ─────────────────────────────────────────────────────

    @Test
    void detectModules_gradleGroovy_singleLineInclude(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("api"));
        Files.createDirectories(root.resolve("impl"));

        Files.writeString(root.resolve("settings.gradle"), """
                rootProject.name = 'my-app'
                include 'api', 'impl'
                """);

        List<Path> modules = detector.detectModules(root);
        assertThat(modules).containsExactlyInAnyOrder(
                root,
                root.resolve("api"),
                root.resolve("impl")
        );
    }

    // ── Priority: Maven before Gradle ─────────────────────────────────────────

    @Test
    void detectModules_mavenTakesPriorityOverGradle(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("maven-mod"));
        Files.createDirectories(root.resolve("gradle-mod"));

        // Both pom.xml and settings.gradle.kts present — Maven wins
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modules><module>maven-mod</module></modules>
                </project>
                """);
        Files.writeString(root.resolve("settings.gradle.kts"), """
                include("gradle-mod")
                """);

        List<Path> modules = detector.detectModules(root);
        assertThat(modules).contains(root.resolve("maven-mod"));
        assertThat(modules).doesNotContain(root.resolve("gradle-mod"));
    }
}
