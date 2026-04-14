package com.rts.gradle;

import com.rts.analyzer.DependencyGraphBuilder;
import com.rts.analyzer.JavaAstAnalyzer;
import com.rts.analyzer.TestMappingResolver;
import com.rts.change.ChangeImpactAnalyzer;
import com.rts.change.JGitDiffParser;
import com.rts.core.cache.GraphCache;
import com.rts.core.model.*;
import com.rts.selector.StaticSelector;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Stateless facade that encapsulates the full RTS selection pipeline for use
 * inside the Gradle plugin.
 *
 * <p>The logic mirrors {@code RtsCommand.SelectCommand} in the CLI module but
 * operates purely in-process — no subprocess, no JSON serialisation.
 */
public final class RtsRunner {

    private RtsRunner() {}

    /**
     * Runs the full RTS selection pipeline and returns the result.
     *
     * @param projectRoot  root directory of the project to analyse
     * @param diffRange    git diff range (e.g. {@code "HEAD~1..HEAD"}); mutually exclusive
     *                     with {@code changedFiles}
     * @param changedFiles comma-separated file paths relative to {@code projectRoot};
     *                     mutually exclusive with {@code diffRange}
     * @return selection result from the static selector
     * @throws Exception if graph building or diff parsing fails
     */
    public static SelectionResult select(Path projectRoot,
                                         String diffRange,
                                         String changedFiles) throws Exception {
        JavaAstAnalyzer astAnalyzer = new JavaAstAnalyzer();
        DependencyGraphBuilder graphBuilder = new DependencyGraphBuilder(astAnalyzer);

        // Use the graph cache to avoid re-analysing on every run
        GraphCache cache = new GraphCache(projectRoot);
        DependencyGraph graph = cache.load().orElseGet(() -> {
            try {
                DependencyGraph g = graphBuilder.buildFromProject(projectRoot);
                cache.save(g);
                return g;
            } catch (Exception e) {
                throw new RuntimeException("Failed to build dependency graph", e);
            }
        });

        TestMappingResolver testResolver = new TestMappingResolver();
        List<TestCase> allTests = testResolver.discoverTests(projectRoot);

        JGitDiffParser diffParser = new JGitDiffParser(astAnalyzer);
        List<ChangeInfo> changes;
        if (diffRange != null) {
            String[] parts = diffRange.split("\\.\\.", 2);
            String from = parts[0].trim();
            String to   = parts.length > 1 ? parts[1].trim() : "HEAD";
            changes = diffParser.parseChanges(projectRoot, from, to);
        } else {
            List<String> files = Arrays.stream(changedFiles.split(","))
                    .map(String::trim)
                    .collect(Collectors.toList());
            changes = diffParser.parseChangedFiles(projectRoot, files);
        }

        StaticSelector selector = new StaticSelector(new ChangeImpactAnalyzer());
        return selector.select(graph, changes, allTests);
    }
}
