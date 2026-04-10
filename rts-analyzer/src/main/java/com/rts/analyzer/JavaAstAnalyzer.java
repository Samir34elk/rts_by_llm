package com.rts.analyzer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.rts.core.model.CodeElement;
import com.rts.core.model.ElementType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses Java source files using JavaParser and extracts {@link CodeElement} instances
 * (classes, interfaces, methods) along with their static dependencies derived from imports.
 */
public class JavaAstAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(JavaAstAnalyzer.class);

    private final JavaParser parser;

    /** Creates an analyzer with a default {@link JavaParser} configuration. */
    public JavaAstAnalyzer() {
        this.parser = new JavaParser();
    }

    /**
     * Analyzes a single Java source file and extracts all code elements declared in it.
     *
     * @param sourceFile path to a {@code .java} file
     * @return list of extracted elements; empty if the file cannot be parsed
     */
    public List<CodeElement> analyzeFile(Path sourceFile) {
        try {
            String source = Files.readString(sourceFile);
            return analyzeSource(source);
        } catch (IOException e) {
            log.warn("Cannot read source file {}: {}", sourceFile, e.getMessage());
            return List.of();
        }
    }

    /**
     * Analyzes a Java source snippet provided as a {@link String}.
     *
     * @param source raw Java source code
     * @return list of extracted elements; empty if parsing fails
     */
    public List<CodeElement> analyzeSource(String source) {
        ParseResult<CompilationUnit> result = parser.parse(source);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            result.getProblems().forEach(p -> log.warn("Parse problem: {}", p.getMessage()));
            return List.of();
        }
        CompilationUnit cu = result.getResult().get();
        return extractElements(cu);
    }

    /**
     * Recursively scans a directory and analyzes every {@code .java} file found.
     *
     * @param sourceRoot root directory to scan
     * @return all elements found across all files
     */
    public List<CodeElement> analyzeDirectory(Path sourceRoot) {
        List<CodeElement> all = new ArrayList<>();
        try (var stream = Files.walk(sourceRoot)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                    .forEach(file -> all.addAll(analyzeFile(file)));
        } catch (IOException e) {
            log.warn("Cannot walk directory {}: {}", sourceRoot, e.getMessage());
        }
        return all;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private List<CodeElement> extractElements(CompilationUnit cu) {
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString() + ".")
                .orElse("");

        Set<String> importedClasses = extractImports(cu);
        List<CodeElement> elements = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(decl -> {
            String simpleName = decl.getNameAsString();
            String fqn = packageName + simpleName;
            ElementType type = decl.isInterface() ? ElementType.INTERFACE : ElementType.CLASS;

            // Class-level dependencies = imports + explicitly listed interfaces/extends
            Set<String> classDeps = new HashSet<>(importedClasses);
            decl.getImplementedTypes().forEach(t -> classDeps.add(resolveType(t.getNameAsString(), importedClasses, packageName)));
            decl.getExtendedTypes().forEach(t -> classDeps.add(resolveType(t.getNameAsString(), importedClasses, packageName)));
            classDeps.remove(fqn); // no self-dependency

            elements.add(new CodeElement(fqn, type, Set.copyOf(classDeps)));

            // Method-level elements
            decl.getMethods().forEach(method -> {
                String methodFqn = fqn + "#" + method.getNameAsString();
                Set<String> methodDeps = buildMethodDependencies(method, importedClasses, packageName);
                elements.add(new CodeElement(methodFqn, ElementType.METHOD, Set.copyOf(methodDeps)));
            });
        });

        return elements;
    }

    private Set<String> extractImports(CompilationUnit cu) {
        Set<String> imports = new HashSet<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isAsterisk() && !imp.isStatic()) {
                imports.add(imp.getNameAsString());
            }
        }
        return imports;
    }

    private Set<String> buildMethodDependencies(MethodDeclaration method,
                                                 Set<String> importedClasses,
                                                 String packagePrefix) {
        Set<String> deps = new HashSet<>();
        // Return type
        String returnType = method.getTypeAsString();
        resolveAndAdd(returnType, importedClasses, packagePrefix, deps);

        // Parameter types
        method.getParameters().forEach(p -> resolveAndAdd(p.getTypeAsString(), importedClasses, packagePrefix, deps));

        // Thrown exceptions
        method.getThrownExceptions().forEach(e -> resolveAndAdd(e.asString(), importedClasses, packagePrefix, deps));

        return deps;
    }

    private void resolveAndAdd(String simpleName, Set<String> imports, String packagePrefix, Set<String> target) {
        String resolved = resolveType(simpleName, imports, packagePrefix);
        if (!resolved.isEmpty() && !isPrimitive(resolved)) {
            target.add(resolved);
        }
    }

    private String resolveType(String simpleName, Set<String> imports, String packagePrefix) {
        // Strip generic parameters
        String base = simpleName.contains("<") ? simpleName.substring(0, simpleName.indexOf('<')) : simpleName;
        // Already FQN
        if (base.contains(".")) {
            return base;
        }
        // Try to find in imports
        for (String imp : imports) {
            if (imp.endsWith("." + base)) {
                return imp;
            }
        }
        // Assume same package
        if (!packagePrefix.isEmpty() && !base.isEmpty()) {
            return packagePrefix + base;
        }
        return base;
    }

    private boolean isPrimitive(String type) {
        return switch (type) {
            case "void", "int", "long", "double", "float", "boolean", "byte", "short", "char",
                    "String", "Object", "Integer", "Long", "Double", "Float", "Boolean" -> true;
            default -> false;
        };
    }
}
