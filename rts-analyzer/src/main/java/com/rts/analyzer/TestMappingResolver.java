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
 * <p>Coverage is inferred statically via two heuristics:
 * <ol>
 *   <li>Import-based: non-framework imports → covered production classes</li>
 *   <li>Naming convention: {@code FooSteps}/{@code FooTest} → covers {@code Foo}</li>
 * </ol>
 */
public class TestMappingResolver implements TestDiscovery {

    private static final Logger log = LoggerFactory.getLogger(TestMappingResolver.class);

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
    }

    /**
     * Discovers all test cases under {@code src/test/} within the given project root.
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

        List<TestCase> tests = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(testRoot)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                    .forEach(file -> tests.addAll(extractTestCases(file)));
        } catch (IOException e) {
            log.warn("Cannot walk test directory {}: {}", testRoot, e.getMessage());
        }
        log.info("Discovered {} test cases under {}", tests.size(), testRoot);
        return tests;
    }

    /**
     * Extracts test cases from a single Java source file.
     *
     * @param sourceFile path to a {@code .java} file
     * @return test cases found; empty if the file is not a test/step class
     */
    public List<TestCase> extractTestCases(Path sourceFile) {
        try {
            return extractTestCases(Files.readString(sourceFile));
        } catch (IOException e) {
            log.warn("Cannot read test file {}: {}", sourceFile, e.getMessage());
            return List.of();
        }
    }

    /**
     * Extracts test cases from a raw Java source snippet.
     *
     * @param source Java source code
     * @return test cases found; empty if none detected
     */
    public List<TestCase> extractTestCases(String source) {
        ParseResult<CompilationUnit> result = parser.parse(source);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return List.of();
        }
        return extractFromCu(result.getResult().get());
    }

    // -------------------------------------------------------------------------

    private List<TestCase> extractFromCu(CompilationUnit cu) {
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
            Set<String> classLevelCoverage = inferCoveredClasses(decl.getNameAsString(), importedClasses, packageName);

            List<TestCase> extracted = extractJUnit(decl, className, classLevelCoverage, importedClasses);
            if (!extracted.isEmpty()) {
                results.addAll(extracted);
                return;
            }

            // Try Cucumber step class
            extracted = extractCucumberSteps(decl, className, classLevelCoverage);
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

    private Set<String> inferCoveredClasses(String className, Set<String> imports, String packageName) {
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
