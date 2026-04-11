package com.rts.analyzer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses a JaCoCo XML coverage report and extracts the set of production methods
 * that have at least one covered instruction.
 *
 * <p>Supports the standard report locations produced by the JaCoCo Gradle and Maven
 * plugins:
 * <ul>
 *   <li>{@code build/reports/jacoco/test/jacocoTestReport.xml} (Gradle)</li>
 *   <li>{@code build/reports/jacoco/jacocoTestReport.xml} (Gradle — custom task name)</li>
 *   <li>{@code target/site/jacoco/jacoco.xml} (Maven)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * JacocoReportParser parser = new JacocoReportParser();
 * parser.findReport(projectRoot).ifPresent(report -> {
 *     Map<String, Set<String>> coverage = parser.parse(report);
 *     // coverage.get("com.example.Foo") → {"setName", "validate", ...}
 * });
 * }</pre>
 */
public class JacocoReportParser {

    private static final Logger log = LoggerFactory.getLogger(JacocoReportParser.class);

    /** Standard JaCoCo report file paths, relative to the project root. */
    private static final List<String> REPORT_LOCATIONS = List.of(
            "build/reports/jacoco/test/jacocoTestReport.xml",
            "build/reports/jacoco/jacocoTestReport.xml",
            "target/site/jacoco/jacoco.xml"
    );

    /**
     * Searches for the first existing JaCoCo XML report under the project root.
     *
     * @param projectRoot root directory of the project
     * @return path to the report, or empty if none found
     */
    public Optional<Path> findReport(Path projectRoot) {
        for (String location : REPORT_LOCATIONS) {
            Path candidate = projectRoot.resolve(location);
            if (Files.exists(candidate)) {
                log.info("JaCoCo report found: {}", candidate);
                return Optional.of(candidate);
            }
        }
        log.debug("No JaCoCo report found under {} — coverage will use static heuristic", projectRoot);
        return Optional.empty();
    }

    /**
     * Parses a JaCoCo XML report and returns method-level coverage data.
     *
     * <p>Only methods with at least one covered instruction are included.
     *
     * @param reportFile path to the JaCoCo XML report
     * @return map from class FQN (dot-separated, e.g. {@code "com.example.Foo"}) to the
     *         set of method names covered by the test suite
     */
    public Map<String, Set<String>> parse(Path reportFile) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Disable external DTD loading — JaCoCo XML declares a DTD but we do not need validation
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setValidating(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            // Suppress DTD warning output
            builder.setErrorHandler(null);
            Document doc = builder.parse(reportFile.toFile());

            NodeList packages = doc.getElementsByTagName("package");
            for (int i = 0; i < packages.getLength(); i++) {
                Element pkg = (Element) packages.item(i);

                NodeList classes = pkg.getElementsByTagName("class");
                for (int j = 0; j < classes.getLength(); j++) {
                    Element cls = (Element) classes.item(j);
                    // JaCoCo uses slash-separated internal names: "com/example/Foo"
                    String classFqn = cls.getAttribute("name").replace('/', '.');

                    Set<String> coveredMethods = new LinkedHashSet<>();
                    NodeList methods = cls.getElementsByTagName("method");
                    for (int k = 0; k < methods.getLength(); k++) {
                        Element method = (Element) methods.item(k);
                        if (hasCoveredInstructions(method)) {
                            coveredMethods.add(method.getAttribute("name"));
                        }
                    }

                    if (!coveredMethods.isEmpty()) {
                        result.put(classFqn, coveredMethods);
                    }
                }
            }
            log.info("JaCoCo report parsed: {} classes with coverage from {}", result.size(), reportFile);
        } catch (Exception e) {
            log.warn("Failed to parse JaCoCo report {}: {}", reportFile, e.getMessage());
        }
        return result;
    }

    // -------------------------------------------------------------------------

    private boolean hasCoveredInstructions(Element method) {
        NodeList counters = method.getElementsByTagName("counter");
        for (int i = 0; i < counters.getLength(); i++) {
            Element counter = (Element) counters.item(i);
            if ("INSTRUCTION".equals(counter.getAttribute("type"))) {
                try {
                    return Integer.parseInt(counter.getAttribute("covered")) > 0;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        return false;
    }
}
