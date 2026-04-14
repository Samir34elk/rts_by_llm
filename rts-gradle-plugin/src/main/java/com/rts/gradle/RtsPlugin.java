package com.rts.gradle;

import com.rts.core.model.SelectionResult;
import com.rts.core.model.TestCase;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.testing.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Gradle plugin that adds an {@code rtsTest} task to the project.
 *
 * <p>Apply it in your {@code build.gradle.kts}:
 * <pre>{@code
 * plugins {
 *     id("com.rts.plugin")
 * }
 * }</pre>
 *
 * <p>Usage:
 * <pre>{@code
 * # Static mode — select by git diff
 * ./gradlew rtsTest -PrtsDiff=HEAD~1..HEAD
 *
 * # Static mode — select by explicit file list
 * ./gradlew rtsTest -PrtsChangedFiles=src/main/java/com/example/Foo.java
 *
 * # Optional DSL defaults (overridden by -P flags at runtime)
 * rts {
 *     mode = "static"
 *     diff = "HEAD~1..HEAD"
 * }
 * }</pre>
 *
 * <p>The {@code rtsTest} task is a standard Gradle {@link Test} task whose test-class
 * inclusion filter is computed at execution time by the RTS selection engine.  It copies
 * its classpath and test-class directories from the project's existing {@code test} task.
 */
public class RtsPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        RtsExtension extension = project.getExtensions().create("rts", RtsExtension.class);

        var rtsTestProvider = project.getTasks().register("rtsTest", Test.class, rtsTest -> {
            rtsTest.setGroup("verification");
            rtsTest.setDescription(
                    "Run only the tests impacted by recent code changes (RTS-selected).");
            rtsTest.useJUnitPlatform();
            // The selection filter depends on the git diff / changed-files list which Gradle
            // cannot track as a task input — always re-run to reflect the current state.
            rtsTest.getOutputs().upToDateWhen(t -> false);

            // The filter is applied at execution time (doFirst) so it can be computed
            // from the actual diff/changed-files provided on the command line.
            rtsTest.doFirst(task -> {
                Test self = (Test) task;
                Path root = project.getProjectDir().toPath();

                String diff  = resolve(project, extension.getDiff(), "rtsDiff");
                String files = resolve(project, extension.getChangedFiles(), "rtsChangedFiles");

                if (diff == null && files == null) {
                    project.getLogger().warn(
                            "[RTS] No changes specified. Use -PrtsDiff=<range> or " +
                            "-PrtsChangedFiles=<files>. Running all tests as fallback.");
                    return;
                }

                try {
                    SelectionResult result = RtsRunner.select(root, diff, files);
                    List<String> selectedClasses = result.selectedTests().stream()
                            .map(TestCase::className)
                            .distinct()
                            .sorted()
                            .collect(Collectors.toList());

                    int total = (int) result.selectedTests().stream()
                            .map(TestCase::className).distinct().count();

                    if (selectedClasses.isEmpty()) {
                        project.getLogger().lifecycle(
                                "[RTS] No tests impacted by the detected changes — skipping test run.");
                        self.filter(f -> {
                            f.includeTestsMatching("__RTS_NO_TESTS_SELECTED__");
                            f.setFailOnNoMatchingTests(false);
                        });
                        return;
                    }

                    project.getLogger().lifecycle(
                            "[RTS] {} test class(es) selected:", selectedClasses.size());
                    selectedClasses.forEach(c ->
                            project.getLogger().lifecycle("[RTS]   + {}", c));

                    self.filter(f -> {
                        f.setFailOnNoMatchingTests(false);
                        selectedClasses.forEach(f::includeTestsMatching);
                    });

                } catch (Exception e) {
                    project.getLogger().error(
                            "[RTS] Selection failed ({}). Falling back to running all tests.",
                            e.getMessage());
                    // No filter → all tests run (safe fallback)
                }
            });
        });

        // Copy classpath from the standard `test` task after project evaluation,
        // so that all test-related source sets and dependencies are inherited.
        // This must be done outside the register() callback to avoid calling
        // afterEvaluate() from within the configuration phase.
        project.afterEvaluate(p -> rtsTestProvider.configure(rtsTest -> {
            var testTask = p.getTasks().findByName("test");
            if (testTask instanceof Test standard) {
                rtsTest.setTestClassesDirs(standard.getTestClassesDirs());
                rtsTest.setClasspath(standard.getClasspath());
            } else {
                project.getLogger().warn(
                        "[RTS] No 'test' task found — rtsTest may have an incomplete classpath.");
            }
        }));
    }

    /**
     * Resolves a string value by checking the Gradle project property first (CLI flag),
     * then falling back to the DSL extension property.
     */
    private static String resolve(Project project, Property<String> prop, String projectPropKey) {
        if (project.hasProperty(projectPropKey)) {
            return (String) project.property(projectPropKey);
        }
        return prop.isPresent() ? prop.get() : null;
    }
}
