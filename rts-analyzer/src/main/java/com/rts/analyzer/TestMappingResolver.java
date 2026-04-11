package com.rts.analyzer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.rts.core.model.TestCase;
import com.rts.core.spi.TestDiscovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Resolves the mapping between test methods/step definitions and the production code
 * elements they cover.
 *
 * <p>Supports two test frameworks detected via annotations:
 * <ul>
 *   <li><b>JUnit 5</b> — methods annotated with {@code @Test}</li>
 *   <li><b>Cucumber</b> — methods annotated with {@code @Given}, {@code @When},
 *       {@code @Then}, {@code @And}, {@code @But}; the whole step class is treated
 *       as a single logical test unit</li>
 * </ul>
 *
 * <p>Coverage resolution (best-effort, in order):
 * <ol>
 *   <li><b>JaCoCo XML report</b> — if a {@code jacocoTestReport.xml} (Gradle) or
 *       {@code jacoco.xml} (Maven) is found in the project's build output, exact
 *       method-level FQNs ({@code com.example.Foo#bar}) are derived from the report.
 *       Only methods with at least one covered instruction are included.</li>
 *   <li><b>Static heuristic fallback</b> — import-based and naming-convention inference
 *       when no JaCoCo report is available.</li>
 * </ol>
 */
public class TestMappingResolver implements TestDiscovery {

    private static final Logger log = LoggerFactory.getLogger(TestMappingResolver.class);

    private final JacocoReportParser jacocoParser;

    /** JUnit 5 test method annotation. */
    private static final String JUNIT_TEST = "Test";

    /** Cucumber step annotations (any of these makes a method a step definition). */
    private static final Set<String> CUCUMBER_STEP_ANNOTATIONS =
            Set.of("Given", "When", "Then", "And", "But");

    /** Class name suffixes/prefixes that mark a class as test-related. */
    private static final String[] TEST_PATTERNS = {"Test", "Tests", "Spec", "IT", "Steps", "StepDefs", "StepDefinitions"};

    /** Imports that belong to test/utility frameworks and should not be counted as coverage. */
    private static final Set<String> FRAMEWORK_PREFIXES = Set.of(
            "org.junit", "java.", "javax.", "org.assertj", "org.mockito",
            "io.cucumber", "io.github.bonigarcia", "org.openqa.selenium",
            "org.slf4j", "com.fasterxml.jackson"
    );

    private final JavaParser parser;

    /** Creates a resolver with a default {@link JavaParser} configuration. */
    public TestMappingResolver() {
        this.parser = new JavaParser();
        this.jacocoParser = new JacocoReportParser();
    }

    /**
     * Discovers all test cases under {@code src/test/} within the given project root.
     *
     * <p>Automatically detects a JaCoCo XML report in the project's build output and,
     * when found, uses it to populate {@code coveredElements} with exact method-level FQNs
     * ({@code com.example.Foo#bar}) instead of the coarse static heuristic.
     *
     * @param projectRoot root directory of the project
     * @return list of test cases with inferred coverage; never {@code null}
     */
    @Override
    public List<TestCase> discoverTests(Path projectRoot) {
        Path testRoot = projectRoot.resolve("src/test/java");
        if (!Files.exists(testRoot)) {
            log.debug("No src/test/java directory found under {}", projectRoot);
            return List.of();
        }

        // Load JaCoCo coverage data if available — enables method-level precision
        Map<String, Set<String>> jacocoCoverage = jacocoParser.findReport(projectRoot)
                .map(jacocoParser::parse)
                .orElse(Map.of());

        if (!jacocoCoverage.isEmpty()) {
            log.info("Using JaCoCo coverage data: {} classes with method-level coverage", jacocoCoverage.size());
        } else {
            log.info("No JaCoCo report found — falling back to static heuristic for coverage inference");
        }

        List<TestCase> tests = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(testRoot)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                    .forEach(file -> tests.addAll(extractTestCases(file, jacocoCoverage)));
        } catch (IOException e) {
            log.warn("Cannot walk test directory {}: {}", testRoot, e.getMessage());
        }
        log.info("Discovered {} test cases under {}", tests.size(), testRoot);
        return tests;
    }

    /**
     * Extracts test cases from a single Java source file using the static heuristic.
     *
     * @param sourceFile path to a {@code .java} file
     * @return test cases found; empty if the file is not a test/step class
     */
    public List<TestCase> extractTestCases(Path sourceFile) {
        return extractTestCases(sourceFile, Map.of());
    }

    /**
     * Extracts test cases from a single Java source file, enriching coverage with JaCoCo
     * data when provided.
     *
     * @param sourceFile     path to a {@code .java} file
     * @param jacocoCoverage map from class FQN to set of covered method names; empty map
     *                       triggers the static heuristic fallback
     * @return test cases found; empty if the file is not a test/step class
     */
    public List<TestCase> extractTestCases(Path sourceFile, Map<String, Set<String>> jacocoCoverage) {
        try {
            return extractTestCases(Files.readString(sourceFile), jacocoCoverage);
        } catch (IOException e) {
            log.warn("Cannot read test file {}: {}", sourceFile, e.getMessage());
            return List.of();
        }
    }

    /**
     * Extracts test cases from a raw Java source snippet (static heuristic only).
     *
     * @param source Java source code
     * @return test cases found; empty if none detected
     */
    public List<TestCase> extractTestCases(String source) {
        return extractTestCases(source, Map.of());
    }

    /**
     * Extracts test cases from a raw Java source snippet, enriching coverage with JaCoCo
     * data when provided.
     *
     * @param source         Java source code
     * @param jacocoCoverage map from class FQN to set of covered method names
     * @return test cases found; empty if none detected
     */
    public List<TestCase> extractTestCases(String source, Map<String, Set<String>> jacocoCoverage) {
        ParseResult<CompilationUnit> result = parser.parse(source);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return List.of();
        }
        return extractFromCu(result.getResult().get(), jacocoCoverage);
    }

    // -------------------------------------------------------------------------

    private List<TestCase> extractFromCu(CompilationUnit cu, Map<String, Set<String>> jacocoCoverage) {
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString() + ".")
                .orElse("");

        Set<String> importedClasses = new HashSet<>();
        cu.getImports().forEach(imp -> {
            if (!imp.isAsterisk() && !imp.isStatic()) {
                importedClasses.add(imp.getNameAsString());
            }
        });

        List<TestCase> results = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(decl -> {
            String className = packageName + decl.getNameAsString();
            Set<String> coverage = inferCoveredElements(
                    decl.getNameAsString(), importedClasses, packageName, jacocoCoverage);

            List<TestCase> extracted = extractJUnit(decl, className, coverage, importedClasses);
            if (!extracted.isEmpty()) {
                results.addAll(extracted);
                return;
            }

            // Try Cucumber step class
            extracted = extractCucumberSteps(decl, className, coverage);
            results.addAll(extracted);
        });

        return results;
    }

    /**
     * Extracts JUnit 5 test methods (annotated with {@code @Test}).
     */
    private List<TestCase> extractJUnit(ClassOrInterfaceDeclaration decl,
                                         String className,
                                         Set<String> classLevelCoverage,
                                         Set<String> importedClasses) {
        List<MethodDeclaration> testMethods = decl.getMethods().stream()
                .filter(m -> m.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals(JUNIT_TEST)))
                .toList();

        if (testMethods.isEmpty()) {
            return List.of();
        }

        List<TestCase> results = new ArrayList<>();
        for (MethodDeclaration method : testMethods) {
            Set<String> covered = new HashSet<>(classLevelCoverage);
            results.add(new TestCase(className, method.getNameAsString(), Set.copyOf(covered)));
        }
        return results;
    }

    /**
     * Extracts Cucumber step definitions.
     *
     * <p>Each step method ({@code @Given/@When/@Then/@And/@But}) is surfaced as a
     * {@link TestCase}. If no step methods exist the class is not considered a test.
     */
    private List<TestCase> extractCucumberSteps(ClassOrInterfaceDeclaration decl,
                                                 String className,
                                                 Set<String> classLevelCoverage) {
        List<MethodDeclaration> stepMethods = decl.getMethods().stream()
                .filter(m -> m.getAnnotations().stream()
                        .anyMatch(a -> CUCUMBER_STEP_ANNOTATIONS.contains(a.getNameAsString())))
                .toList();

        if (stepMethods.isEmpty()) {
            return List.of();
        }

        List<TestCase> results = new ArrayList<>();
        for (MethodDeclaration method : stepMethods) {
            results.add(new TestCase(className, method.getNameAsString(), Set.copyOf(classLevelCoverage)));
        }
        return results;
    }

    /**
     * Resolves the set of production code elements covered by a test class.
     *
     * <p>When {@code jacocoCoverage} is non-empty, candidate production classes are
     * resolved via the static heuristic and then enriched with exact method FQNs
     * ({@code com.example.Foo#bar}) from the JaCoCo report.  Only methods with at least
     * one covered instruction are included.  If a candidate class has no JaCoCo entry
     * at all (e.g., not yet executed), the class-level FQN is kept as a conservative
     * fallback so no test is silently dropped.
     *
     * <p>When {@code jacocoCoverage} is empty the method falls back to the static
     * heuristic alone (class-level FQNs only).
     */
    private Set<String> inferCoveredElements(String className, Set<String> imports,
                                              String packageName,
                                              Map<String, Set<String>> jacocoCoverage) {
        Set<String> candidateClasses = resolveCandidateClasses(className, imports, packageName);

        if (jacocoCoverage.isEmpty()) {
            // Static heuristic fallback — keep class-level FQNs
            return candidateClasses;
        }

        // JaCoCo path — expand each candidate class to its covered method FQNs
        Set<String> covered = new HashSet<>();
        for (String cls : candidateClasses) {
            Set<String> methods = jacocoCoverage.get(cls);
            if (methods != null && !methods.isEmpty()) {
                for (String method : methods) {
                    covered.add(cls + "#" + method);
                }
            } else {
                // Class not in JaCoCo report — keep class-level as safety net
                covered.add(cls);
            }
        }
        return covered;
    }

    /**
     * Derives the set of candidate production class FQNs that a test class is likely
     * to cover, using naming-convention and import-based heuristics.
     */
    private Set<String> resolveCandidateClasses(String className, Set<String> imports, String packageName) {
        Set<String> covered = new HashSet<>();

        // Heuristic 1: strip test/step suffix → derive likely production class name
        String productionSimpleName = stripTestSuffix(className);
        if (productionSimpleName != null) {
            for (String imp : imports) {
                if (imp.endsWith("." + productionSimpleName)) {
                    covered.add(imp);
                    break;
                }
            }
            if (!packageName.isEmpty()) {
                // Map test package back to main package
                String mainPkg = packageName
                        .replace(".test.", ".")
                        .replace(".steps.", ".")
                        .replace(".stepdefs.", ".");
                covered.add(mainPkg + productionSimpleName);
            }
        }

        // Heuristic 2: all non-framework imports are potential production subjects
        imports.stream()
                .filter(imp -> FRAMEWORK_PREFIXES.stream().noneMatch(imp::startsWith))
                .forEach(covered::add);

        return covered;
    }

    private String stripTestSuffix(String name) {
        for (String suffix : TEST_PATTERNS) {
            if (name.endsWith(suffix) && name.length() > suffix.length()) {
                return name.substring(0, name.length() - suffix.length());
            }
            if (name.startsWith(suffix) && name.length() > suffix.length()) {
                return name.substring(suffix.length());
            }
        }
        return null;
    }
}
