package com.rts.analyzer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects Maven and Gradle multi-module project structures and returns the list
 * of submodule root directories.
 *
 * <p>Supported layouts:
 * <ul>
 *   <li><b>Maven</b> — reads {@code <modules>} from the root {@code pom.xml}</li>
 *   <li><b>Gradle (Kotlin DSL)</b> — extracts {@code include(...)} calls from
 *       {@code settings.gradle.kts}</li>
 *   <li><b>Gradle (Groovy DSL)</b> — extracts {@code include ...} calls from
 *       {@code settings.gradle}</li>
 *   <li><b>Single-module fallback</b> — returns {@code [projectRoot]} when no
 *       multi-module descriptor is found</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * MultiModuleDetector detector = new MultiModuleDetector();
 * List<Path> modules = detector.detectModules(projectRoot);
 * // modules == [root/module-api, root/module-impl, ...]
 * }</pre>
 */
public class MultiModuleDetector {

    private static final Logger log = LoggerFactory.getLogger(MultiModuleDetector.class);

    /**
     * Quoted-string pattern used to extract module names from Gradle {@code include()} calls.
     * Matches single-quoted and double-quoted strings.
     */
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");

    /**
     * Returns the list of module root directories for the given project.
     *
     * <p>For single-module projects the returned list contains only
     * {@code projectRoot} itself.
     *
     * @param projectRoot root directory of the project
     * @return ordered list of module root paths (never empty, never {@code null})
     */
    public List<Path> detectModules(Path projectRoot) {
        // Maven multi-module
        Path pom = projectRoot.resolve("pom.xml");
        if (Files.exists(pom)) {
            List<Path> mavenModules = parseMavenModules(projectRoot, pom);
            if (!mavenModules.isEmpty()) {
                log.info("Multi-module Maven project detected: {} modules", mavenModules.size());
                return mavenModules;
            }
        }

        // Gradle Kotlin DSL
        Path settingsKts = projectRoot.resolve("settings.gradle.kts");
        if (Files.exists(settingsKts)) {
            List<Path> gradleModules = parseGradleSettings(projectRoot, settingsKts);
            if (!gradleModules.isEmpty()) {
                log.info("Multi-module Gradle project detected (KTS): {} modules", gradleModules.size());
                return gradleModules;
            }
        }

        // Gradle Groovy DSL
        Path settingsGroovy = projectRoot.resolve("settings.gradle");
        if (Files.exists(settingsGroovy)) {
            List<Path> gradleModules = parseGradleSettings(projectRoot, settingsGroovy);
            if (!gradleModules.isEmpty()) {
                log.info("Multi-module Gradle project detected (Groovy): {} modules", gradleModules.size());
                return gradleModules;
            }
        }

        log.debug("Single-module project (no multi-module descriptor found under {})", projectRoot);
        return List.of(projectRoot);
    }

    // ── Maven ─────────────────────────────────────────────────────────────────

    private List<Path> parseMavenModules(Path root, Path pomFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setValidating(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            Document doc = builder.parse(pomFile.toFile());

            NodeList modulesNodes = doc.getElementsByTagName("module");
            if (modulesNodes.getLength() == 0) {
                return List.of();   // single-module pom.xml (no <modules> element)
            }

            List<Path> modules = new ArrayList<>();
            // Always include the root itself — it may contain shared sources
            modules.add(root);
            for (int i = 0; i < modulesNodes.getLength(); i++) {
                Element moduleEl = (Element) modulesNodes.item(i);
                String moduleName = moduleEl.getTextContent().trim();
                if (!moduleName.isEmpty()) {
                    Path modulePath = root.resolve(moduleName);
                    if (Files.isDirectory(modulePath)) {
                        modules.add(modulePath);
                    } else {
                        log.warn("Maven module directory not found: {}", modulePath);
                    }
                }
            }
            return modules;

        } catch (Exception e) {
            log.warn("Failed to parse Maven pom.xml at {}: {}", pomFile, e.getMessage());
            return List.of();
        }
    }

    // ── Gradle ────────────────────────────────────────────────────────────────

    /**
     * Parses {@code include()} and {@code include} calls from a Gradle settings file
     * (both KTS and Groovy DSL).
     *
     * <p>Handles:
     * <ul>
     *   <li>{@code include("module-a", "module-b")}</li>
     *   <li>{@code include("parent:child")  →  parent/child/ directory}</li>
     *   <li>Multi-line {@code include(...)} blocks</li>
     * </ul>
     */
    private List<Path> parseGradleSettings(Path root, Path settingsFile) {
        try {
            String content = Files.readString(settingsFile);

            // Extract all include(...) blocks (single-line and multi-line)
            Pattern includeBlock = Pattern.compile(
                    "include\\s*\\(([^)]+)\\)", Pattern.DOTALL);
            // Also handle Groovy-style: include 'a', 'b'
            Pattern includeGroovy = Pattern.compile(
                    "^\\s*include\\s+(.+)$", Pattern.MULTILINE);

            List<String> moduleNames = new ArrayList<>();
            extractQuotedStrings(includeBlock.matcher(content), moduleNames);
            extractQuotedStrings(includeGroovy.matcher(content), moduleNames);

            if (moduleNames.isEmpty()) {
                return List.of();
            }

            List<Path> modules = new ArrayList<>();
            modules.add(root);  // root may have shared sources

            for (String name : moduleNames) {
                // Gradle uses ':' as path separator for nested projects
                // "parent:child" → root/parent/child
                String dirPath = name.replace(':', '/');
                Path modulePath = root.resolve(dirPath);
                if (Files.isDirectory(modulePath)) {
                    modules.add(modulePath);
                } else {
                    log.debug("Gradle module directory not found (skipping): {}", modulePath);
                }
            }
            return modules;

        } catch (IOException e) {
            log.warn("Failed to read Gradle settings file {}: {}", settingsFile, e.getMessage());
            return List.of();
        }
    }

    private void extractQuotedStrings(Matcher matcher, List<String> target) {
        while (matcher.find()) {
            String block = matcher.group(1);
            Matcher q = QUOTED.matcher(block);
            while (q.find()) {
                target.add(q.group(1));
            }
        }
    }
}
