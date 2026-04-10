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
 * Resolves the mapping between test methods and the production code elements they cover.
 *
 * <p>Coverage is inferred statically using three heuristics (in priority order):
 * <ol>
 *   <li>Import-based: a test imports {@code com.example.Foo} → covers {@code com.example.Foo}</li>
 *   <li>Naming convention: {@code FooTest} → covers {@code com.example.Foo}</li>
 *   <li>Annotation: class or method is annotated with {@code @Test} (JUnit 5)</li>
 * </ol>
 */
public class TestMappingResolver implements TestDiscovery {

    private static final Logger log = LoggerFactory.getLogger(TestMappingResolver.class);

    private static final String TEST_ANNOTATION = "Test";
    private static final String[] TEST_PATTERNS = {"Test", "Tests", "Spec", "IT"};

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
        return tests;
    }

    /**
     * Extracts test cases from a single Java source file provided as a path.
     *
     * @param sourceFile path to a {@code .java} file
     * @return test cases found in the file; empty if the file is not a test class
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
        CompilationUnit cu = result.getResult().get();
        return extractFromCu(cu);
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

            // Determine covered production class via naming convention
            Set<String> classLevelCoverage = inferCoveredClasses(decl.getNameAsString(), importedClasses, packageName);

            // Extract test methods (annotated with @Test)
            List<MethodDeclaration> testMethods = decl.getMethods().stream()
                    .filter(m -> m.getAnnotations().stream()
                            .anyMatch(a -> a.getNameAsString().equals(TEST_ANNOTATION)))
                    .toList();

            if (testMethods.isEmpty()) {
                return; // not a test class
            }

            for (MethodDeclaration method : testMethods) {
                Set<String> coveredElements = new HashSet<>(classLevelCoverage);
                // Also add imports used by the method's parameter/return types
                coveredElements.addAll(inferMethodCoverage(method, importedClasses));
                results.add(new TestCase(className, method.getNameAsString(), Set.copyOf(coveredElements)));
            }
        });

        return results;
    }

    private Set<String> inferCoveredClasses(String testClassName, Set<String> imports, String packageName) {
        Set<String> covered = new HashSet<>();

        // Heuristic 1: strip test suffix to derive production class name
        String productionSimpleName = stripTestSuffix(testClassName);
        if (productionSimpleName != null) {
            // Look in imports first
            for (String imp : imports) {
                if (imp.endsWith("." + productionSimpleName)) {
                    covered.add(imp);
                    break;
                }
            }
            // Also try same package
            if (!packageName.isEmpty()) {
                String candidate = packageName.replace(".test.", ".") + productionSimpleName;
                covered.add(candidate);
            }
        }

        // Heuristic 2: all non-JUnit, non-java imports are potential subjects
        imports.stream()
                .filter(imp -> !imp.startsWith("org.junit")
                        && !imp.startsWith("java.")
                        && !imp.startsWith("org.assertj")
                        && !imp.startsWith("org.mockito"))
                .forEach(covered::add);

        return covered;
    }

    private Set<String> inferMethodCoverage(MethodDeclaration method, Set<String> imports) {
        // For now, method-level coverage inherits from class level (imports already handled above)
        return Set.of();
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
