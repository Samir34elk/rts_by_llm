package com.rts.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rts.analyzer.DependencyGraphBuilder;
import com.rts.analyzer.JavaAstAnalyzer;
import com.rts.analyzer.TestMappingResolver;
import com.rts.change.ChangeImpactAnalyzer;
import com.rts.change.GitCloneHelper;
import com.rts.change.JGitDiffParser;
import com.rts.core.cache.GraphCache;
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
 *   <li>{@code analyze} — builds and prints the dependency graph / test inventory</li>
 *   <li>{@code select} — selects tests impacted by a git diff or a list of changed files</li>
 * </ul>
 *
 * <p>Both sub-commands accept either a local {@code --project} path or a remote
 * {@code --url} pointing to a Git repository that will be cloned automatically.
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

    @Command(name = "analyze",
             description = "Analyze a Java project and output its dependency graph and test inventory.")
    static class AnalyzeCommand implements Callable<Integer> {

        private static final Logger log = LoggerFactory.getLogger(AnalyzeCommand.class);

        @Parameters(index = "0", description = "Path to the Java project root", arity = "0..1")
        private Path projectPath;

        @Option(names = {"--url", "-u"},
                description = "Remote Git URL to clone and analyze (alternative to project path)")
        private String gitUrl;

        @Option(names = {"--branch", "-b"},
                description = "Branch to checkout when using --url (default: repository default branch)")
        private String branch;

        @Option(names = {"--output", "-o"}, description = "Output file (default: stdout)")
        private Path outputFile;

        @Override
        public Integer call() throws Exception {
            if (gitUrl != null) {
                try (GitCloneHelper clone = GitCloneHelper.clone(gitUrl, branch)) {
                    return runAnalysis(clone.getRepoRoot(), gitUrl, clone.getHeadCommit());
                }
            }
            if (projectPath == null) {
                System.err.println("Error: provide a project path or --url <git-url>");
                return 1;
            }
            return runAnalysis(projectPath, null, null);
        }

        private int runAnalysis(Path root, String remoteUrl, String headCommit) throws Exception {
            log.info("Analyzing project: {}", root);

            JavaAstAnalyzer astAnalyzer = new JavaAstAnalyzer();
            DependencyGraphBuilder builder = new DependencyGraphBuilder(astAnalyzer);
            GraphCache cache = new GraphCache(root);
            DependencyGraph graph = cache.load().orElseGet(() -> {
                try {
                    DependencyGraph g = builder.buildFromProject(root);
                    cache.save(g);
                    return g;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            TestMappingResolver testResolver = new TestMappingResolver();
            List<TestCase> tests = testResolver.discoverTests(root);

            Map<String, Object> output = new LinkedHashMap<>();
            if (remoteUrl != null) {
                output.put("remoteUrl", remoteUrl);
                output.put("headCommit", headCommit);
            } else {
                output.put("projectPath", root.toAbsolutePath().toString());
            }
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

            String json = new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(output);

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

        @Option(names = {"--project", "-p"},
                description = "Path to the local Java project root")
        private Path projectPath;

        @Option(names = {"--url", "-u"},
                description = "Remote Git URL to clone and analyze (alternative to --project)")
        private String gitUrl;

        @Option(names = {"--branch", "-b"},
                description = "Branch to checkout when using --url (default: repository default branch)")
        private String branch;

        @Option(names = {"--diff"},
                description = "Git diff range, e.g. HEAD~1..HEAD (requires a local project or cloned repo with history)")
        private String diffRange;

        @Option(names = {"--changed-files"},
                description = "Comma-separated list of changed file paths relative to the project root")
        private String changedFiles;

        @Option(names = {"--mode"},
                description = "Selection mode: static or hybrid (default: static)")
        private String mode = "static";

        @Option(names = {"--output", "-o"}, description = "Output file (default: stdout)")
        private Path outputFile;

        @Override
        public Integer call() throws Exception {
            if (gitUrl != null) {
                try (GitCloneHelper clone = GitCloneHelper.clone(gitUrl, branch)) {
                    return runSelection(clone.getRepoRoot(), gitUrl, clone.getHeadCommit());
                }
            }
            if (projectPath == null) {
                System.err.println("Error: provide --project <path> or --url <git-url>");
                return 1;
            }
            return runSelection(projectPath, null, null);
        }

        private int runSelection(Path root, String remoteUrl, String headCommit) throws Exception {
            if (diffRange == null && changedFiles == null) {
                System.err.println("Error: provide --diff <range> or --changed-files <files>");
                return 1;
            }

            // --mode hybrid active le LLM même sans rts-config.yaml :
            // les variables d'env RTS_LLM_* prennent le dessus sur le fichier.
            boolean hybridRequested = "hybrid".equalsIgnoreCase(mode);
            RtsConfig config = RtsConfig.load(root);
            if (hybridRequested) {
                config.getLlm().setEnabled(true);
            }
            log.info("Mode: {}, LLM enabled: {}", mode, config.getLlm().isEnabled());

            // Build dependency graph (with cache)
            JavaAstAnalyzer astAnalyzer = new JavaAstAnalyzer();
            DependencyGraphBuilder graphBuilder = new DependencyGraphBuilder(astAnalyzer);
            GraphCache cache = new GraphCache(root);
            DependencyGraph graph = cache.load().orElseGet(() -> {
                try {
                    DependencyGraph g = graphBuilder.buildFromProject(root);
                    cache.save(g);
                    return g;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // Discover tests
            TestMappingResolver testResolver = new TestMappingResolver();
            List<TestCase> allTests = testResolver.discoverTests(root);
            log.info("Discovered {} tests", allTests.size());

            // Parse changes
            JGitDiffParser diffParser = new JGitDiffParser(astAnalyzer);
            List<ChangeInfo> changes;
            if (diffRange != null) {
                String[] parts = diffRange.split("\\.\\.", 2);
                String from = parts[0].trim();
                String to = parts.length > 1 ? parts[1].trim() : "HEAD";
                changes = diffParser.parseChanges(root, from, to);
            } else {
                List<String> files = Arrays.asList(changedFiles.split(","));
                changes = diffParser.parseChangedFiles(root, files.stream().map(String::trim).toList());
            }
            log.info("Detected {} changes", changes.size());

            // Select tests
            ChangeImpactAnalyzer impactAnalyzer = new ChangeImpactAnalyzer();
            StaticSelector staticSelector = new StaticSelector(impactAnalyzer);
            SelectionResult result;

            if (hybridRequested) {
                LlmClient llmClient = buildLlmClient(config);
                if (!llmClient.isAvailable()) {
                    System.err.println("⚠  LLM non configuré (endpoint ou modèle manquant) — " +
                            "set RTS_LLM_ENDPOINT et RTS_LLM_MODEL, ou vérifiez rts-config.yaml. " +
                            "Fallback sur le mode statique.");
                    result = staticSelector.select(graph, changes, allTests);
                } else {
                    LlmRefinementSelector llmSelector = new LlmRefinementSelector(
                            llmClient, new PromptTemplates(), new LlmResponseParser());
                    HybridSelector hybrid = new HybridSelector(staticSelector, llmSelector, true);
                    result = hybrid.select(graph, changes, allTests);
                }
            } else {
                result = staticSelector.select(graph, changes, allTests);
            }

            String json = serializeResult(result, changes, remoteUrl, headCommit);
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
                    llm.getEndpoint(), llm.getApiKey(), llm.getModel(),
                    llm.getMaxTokens(), llm.getTemperature());
        }

        private String serializeResult(SelectionResult result, List<ChangeInfo> changes,
                                        String remoteUrl, String headCommit) throws Exception {
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            Map<String, Object> output = new LinkedHashMap<>();

            if (remoteUrl != null) {
                output.put("remoteUrl", remoteUrl);
                output.put("headCommit", headCommit);
            }
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
