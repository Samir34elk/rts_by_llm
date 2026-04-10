package com.rts.selector;

import com.rts.change.ChangeImpactAnalyzer;
import com.rts.core.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Baseline test selector that uses only static dependency analysis.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Compute all classes impacted by the given changes (transitive closure via dependency graph).</li>
 *   <li>For each known test case, check whether any of its covered elements is in the impacted set.</li>
 *   <li>Return the union of all matching test cases.</li>
 * </ol>
 *
 * <p>This selector is deliberately conservative: it will never miss a test that should run,
 * at the cost of potentially including some false positives.
 */
public class StaticSelector {

    private static final Logger log = LoggerFactory.getLogger(StaticSelector.class);

    private final ChangeImpactAnalyzer impactAnalyzer;

    /**
     * Creates a selector backed by the given impact analyzer.
     *
     * @param impactAnalyzer used to compute the transitive impact set
     */
    public StaticSelector(ChangeImpactAnalyzer impactAnalyzer) {
        this.impactAnalyzer = impactAnalyzer;
    }

    /**
     * Selects the minimal (but conservative) set of tests that must be re-executed
     * given the provided changes.
     *
     * @param graph      dependency graph of the project under test
     * @param changes    detected changes to consider
     * @param allTests   full list of known test cases
     * @return selection result with source {@link SelectionSource#STATIC}
     */
    public SelectionResult select(DependencyGraph graph, List<ChangeInfo> changes, List<TestCase> allTests) {
        if (changes.isEmpty()) {
            log.info("No changes detected — no tests selected");
            return new SelectionResult(List.of(), SelectionSource.STATIC, Map.of());
        }

        Set<String> impactedClasses = impactAnalyzer.computeImpactedClasses(changes, graph);
        log.debug("Impacted classes: {}", impactedClasses);

        List<TestCase> selected = allTests.stream()
                .filter(test -> isTestImpacted(test, impactedClasses))
                .collect(Collectors.toList());

        Map<String, String> reasoning = buildReasoning(selected, impactedClasses);

        log.info("Static selection: {}/{} tests selected", selected.size(), allTests.size());
        return new SelectionResult(List.copyOf(selected), SelectionSource.STATIC, reasoning);
    }

    // -------------------------------------------------------------------------

    /**
     * A test is impacted if any element it covers intersects with the impacted class set,
     * or if the test class itself is in the impacted set.
     */
    private boolean isTestImpacted(TestCase test, Set<String> impactedClasses) {
        // Test class itself was changed
        if (impactedClasses.contains(test.className())) {
            return true;
        }
        // Any covered element is in the impacted set (check class portion of FQNs)
        for (String covered : test.coveredElements()) {
            String coveredClass = covered.contains("#") ? covered.substring(0, covered.indexOf('#')) : covered;
            if (impactedClasses.contains(coveredClass) || impactedClasses.contains(covered)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> buildReasoning(List<TestCase> selected, Set<String> impactedClasses) {
        Map<String, String> reasoning = new LinkedHashMap<>();
        for (TestCase test : selected) {
            Set<String> matchedElements = test.coveredElements().stream()
                    .filter(e -> {
                        String cls = e.contains("#") ? e.substring(0, e.indexOf('#')) : e;
                        return impactedClasses.contains(cls) || impactedClasses.contains(e);
                    })
                    .collect(Collectors.toSet());

            if (impactedClasses.contains(test.className())) {
                matchedElements.add(test.className());
            }

            reasoning.put(test.id(), "Covers impacted elements: " + matchedElements);
        }
        return reasoning;
    }
}
