package com.rts.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rts.analyzer.DependencyGraphBuilder;
import com.rts.analyzer.JavaAstAnalyzer;
import com.rts.analyzer.TestMappingResolver;
import com.rts.change.ChangeImpactAnalyzer;
import com.rts.change.JGitDiffParser;
import com.rts.core.model.*;
import com.rts.core.spi.LlmClient;
import com.rts.llm.LlmResponseParser;
import com.rts.llm.OpenAiLlmClient;
import com.rts.llm.PromptTemplates;
import com.rts.selector.HybridSelector;
import com.rts.selector.LlmRefinementSelector;
import com.rts.selector.StaticSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Main CLI entry point for the RTS system.
 *
 * <p>Provides two sub-commands:
 * <ul>
 *   <li>{@code analyze} — builds and prints the dependency graph for a project</li>
 *   <li>{@code select} — selects tests impacted by a git diff or file list</li>
 * </ul>
 */
@Command(
        name = "rts",
        mixinStandardHelpOptions = true,
        version = "1.0.0",
        description = "Regression Test Selection — selects the minimal set of tests to re-run.",
        subcommands = {
                RtsCommand.AnalyzeCommand.class,
                RtsCommand.SelectCommand.class
        }
)
public class RtsCommand implements Callable<Integer> {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new RtsCommand()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    // =========================================================================
    // analyze sub-command
    // =========================================================================

    @Command(name = "analyze", description = "Analyze a Java project and output its dependency graph.")
    static class AnalyzeCommand implements Callable<Integer> {

        private static final Logger log = LoggerFactory.getLogger(AnalyzeCommand.class);

        @Parameters(index = "0", description = "Path to the Java project root")
        private Path projectPath;

        @Option(names = {"--output", "-o"}, description = "Output file for the graph summary (default: stdout)")
        private Path outputFile;

        @Override
        public Integer call() throws Exception {
            log.info("Analyzing project: {}", projectPath);

            JavaAstAnalyzer astAnalyzer = new JavaAstAnalyzer();
            DependencyGraphBuilder builder = new DependencyGraphBuilder(astAnalyzer);
            DependencyGraph graph = builder.buildFromProject(projectPath);

            TestMappingResolver testResolver = new TestMappingResolver();
            List<TestCase> tests = testResolver.discoverTests(projectPath);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("projectPath", projectPath.toAbsolutePath().toString());
            output.put("totalElements", graph.size());
            output.put("discoveredTests", tests.size());

            List<Map<String, Object>> testList = new ArrayList<>();
            for (TestCase t : tests) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("class", t.className());
                entry.put("method", t.methodName());
                entry.put("coveredElements", t.coveredElements().size());
                testList.add(entry);
            }
            output.put("tests", testList);

            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            String json = mapper.writeValueAsString(output);

            if (outputFile != null) {
                java.nio.file.Files.writeString(outputFile, json);
                System.out.println("Graph written to " + outputFile);
            } else {
                System.out.println(json);
            }
            return 0;
        }
    }

    // =========================================================================
    // select sub-command
    // =========================================================================

    @Command(name = "select", description = "Select tests impacted by code changes.")
    static class SelectCommand implements Callable<Integer> {

        private static final Logger log = LoggerFactory.getLogger(SelectCommand.class);

        @Option(names = {"--project", "-p"}, required = true, description = "Path to the Java project root")
        private Path projectPath;

        @Option(names = {"--diff"}, description = "Git diff range, e.g. HEAD~1..HEAD")
        private String diffRange;

        @Option(names = {"--changed-files"}, description = "Comma-separated list of changed files")
        private String changedFiles;

        @Option(names = {"--mode"}, description = "Selection mode: static or hybrid (default: static)")
        private String mode = "static";

        @Option(names = {"--output", "-o"}, description = "Output file (default: stdout)")
        private Path outputFile;

        @Override
        public Integer call() throws Exception {
            if (diffRange == null && changedFiles == null) {
                System.err.println("Error: provide --diff or --changed-files");
                return 1;
            }

            RtsConfig config = RtsConfig.load(projectPath);
            log.info("Mode: {}, LLM enabled: {}", mode, config.getLlm().isEnabled());

            // Build dependency graph
            JavaAstAnalyzer astAnalyzer = new JavaAstAnalyzer();
            DependencyGraphBuilder graphBuilder = new DependencyGraphBuilder(astAnalyzer);
            DependencyGraph graph = graphBuilder.buildFromProject(projectPath);

            // Discover tests
            TestMappingResolver testResolver = new TestMappingResolver();
            List<TestCase> allTests = testResolver.discoverTests(projectPath);
            log.info("Discovered {} tests", allTests.size());

            // Parse changes
            JGitDiffParser diffParser = new JGitDiffParser(astAnalyzer);
            List<ChangeInfo> changes;
            if (diffRange != null) {
                String[] parts = diffRange.split("\\.\\.", 2);
                String from = parts[0].trim();
                String to = parts.length > 1 ? parts[1].trim() : "HEAD";
                changes = diffParser.parseChanges(projectPath, from, to);
            } else {
                List<String> files = Arrays.asList(changedFiles.split(","));
                changes = diffParser.parseChangedFiles(projectPath, files.stream()
                        .map(String::trim)
                        .toList());
            }
            log.info("Detected {} changes", changes.size());

            // Select tests
            ChangeImpactAnalyzer impactAnalyzer = new ChangeImpactAnalyzer();
            StaticSelector staticSelector = new StaticSelector(impactAnalyzer);
            SelectionResult result;

            if ("hybrid".equalsIgnoreCase(mode) && config.getLlm().isEnabled()) {
                LlmClient llmClient = buildLlmClient(config);
                LlmRefinementSelector llmSelector = new LlmRefinementSelector(
                        llmClient, new PromptTemplates(), new LlmResponseParser());
                HybridSelector hybrid = new HybridSelector(staticSelector, llmSelector, true);
                result = hybrid.select(graph, changes, allTests);
            } else {
                result = staticSelector.select(graph, changes, allTests);
            }

            // Serialize output
            String json = serializeResult(result, changes);
            if (outputFile != null) {
                java.nio.file.Files.writeString(outputFile, json);
                System.out.println("Result written to " + outputFile);
            } else {
                System.out.println(json);
            }
            return 0;
        }

        private LlmClient buildLlmClient(RtsConfig config) {
            RtsConfig.LlmConfig llm = config.getLlm();
            return new OpenAiLlmClient(
                    llm.getEndpoint(),
                    llm.getApiKey(),
                    llm.getModel(),
                    llm.getMaxTokens(),
                    llm.getTemperature()
            );
        }

        private String serializeResult(SelectionResult result, List<ChangeInfo> changes) throws Exception {
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("source", result.source().name());
            output.put("selectedTestCount", result.selectedTests().size());

            List<Map<String, String>> tests = new ArrayList<>();
            for (TestCase t : result.selectedTests()) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("class", t.className());
                entry.put("method", t.methodName());
                tests.add(entry);
            }
            output.put("selectedTests", tests);

            List<Map<String, String>> changesOut = new ArrayList<>();
            for (ChangeInfo c : changes) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("file", c.filePath());
                entry.put("class", c.fullyQualifiedClass());
                entry.put("type", c.type().name());
                changesOut.add(entry);
            }
            output.put("detectedChanges", changesOut);
            output.put("reasoning", result.reasoning());

            return mapper.writeValueAsString(output);
        }
    }
}
