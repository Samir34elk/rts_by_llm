package com.rts.analyzer;

import com.rts.core.model.CodeElement;
import com.rts.core.model.DependencyGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * Builds a {@link DependencyGraph} from a list of {@link CodeElement}s or by scanning
 * a project directory via {@link JavaAstAnalyzer}.
 *
 * <p>An edge A → B is added whenever element A lists B among its dependencies.
 */
public class DependencyGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(DependencyGraphBuilder.class);

    private final JavaAstAnalyzer astAnalyzer;

    /**
     * Creates a builder backed by the given AST analyzer.
     *
     * @param astAnalyzer analyzer used to parse source files
     */
    public DependencyGraphBuilder(JavaAstAnalyzer astAnalyzer) {
        this.astAnalyzer = astAnalyzer;
    }

    /**
     * Scans the given project root (both {@code src/main/} and {@code src/test/} trees)
     * and builds a complete dependency graph.
     *
     * @param projectRoot root directory of the project
     * @return fully-populated dependency graph
     */
    public DependencyGraph buildFromProject(Path projectRoot) {
        log.info("Building dependency graph for project: {}", projectRoot);
        List<CodeElement> elements = astAnalyzer.analyzeDirectory(projectRoot);
        log.debug("Discovered {} code elements", elements.size());
        return buildFromElements(elements);
    }

    /**
     * Builds a dependency graph from a pre-computed list of elements.
     *
     * @param elements elements to populate the graph with
     * @return populated dependency graph
     */
    public DependencyGraph buildFromElements(Collection<CodeElement> elements) {
        DependencyGraph graph = new DependencyGraph();

        // First pass: register all elements
        for (CodeElement element : elements) {
            graph.addElement(element);
        }

        // Second pass: wire edges based on declared dependencies
        for (CodeElement element : elements) {
            // For class-like elements, add class-level dependency edges
            if (element.isClassLike()) {
                for (String dep : element.dependencies()) {
                    if (graph.contains(dep)) {
                        graph.addDependency(element.fullyQualifiedName(), dep);
                    } else {
                        log.trace("Skipping external dependency: {} → {}", element.fullyQualifiedName(), dep);
                    }
                }
            } else {
                // For methods, link the owning class as the dependent
                String owningClass = element.owningClass();
                for (String dep : element.dependencies()) {
                    if (graph.contains(dep) && !dep.equals(owningClass)) {
                        graph.addDependency(owningClass, dep);
                    }
                }
            }
        }

        log.info("Dependency graph built: {} elements", graph.size());
        return graph;
    }
}
