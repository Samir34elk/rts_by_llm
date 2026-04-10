package com.rts.selector;

import com.rts.core.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Combines the {@link StaticSelector} (baseline) with the {@link LlmRefinementSelector}
 * (optional refinement) to produce a final test selection.
 *
 * <p>The LLM layer is only invoked when it is both enabled and available.
 * The static layer always runs first and provides the candidate set.
 */
public class HybridSelector {

    private static final Logger log = LoggerFactory.getLogger(HybridSelector.class);

    private final StaticSelector staticSelector;
    private final LlmRefinementSelector llmSelector;
    private final boolean llmEnabled;

    /**
     * Creates a hybrid selector with LLM refinement enabled.
     *
     * @param staticSelector baseline selector
     * @param llmSelector    LLM refinement layer
     */
    public HybridSelector(StaticSelector staticSelector, LlmRefinementSelector llmSelector) {
        this(staticSelector, llmSelector, true);
    }

    /**
     * Creates a hybrid selector with explicit LLM enable flag.
     *
     * @param staticSelector baseline selector
     * @param llmSelector    LLM refinement layer (may be {@code null} when disabled)
     * @param llmEnabled     whether to attempt LLM refinement
     */
    public HybridSelector(StaticSelector staticSelector, LlmRefinementSelector llmSelector, boolean llmEnabled) {
        this.staticSelector = staticSelector;
        this.llmSelector = llmSelector;
        this.llmEnabled = llmEnabled;
    }

    /**
     * Runs the full selection pipeline.
     *
     * <p>If LLM is enabled and available, runs static selection then LLM refinement
     * and returns a result with source {@link SelectionSource#HYBRID}.
     * Otherwise returns the static result as-is.
     *
     * @param graph    dependency graph
     * @param changes  detected changes
     * @param allTests all known test cases
     * @return final selection result
     */
    public SelectionResult select(DependencyGraph graph, List<ChangeInfo> changes, List<TestCase> allTests) {
        SelectionResult staticResult = staticSelector.select(graph, changes, allTests);

        if (!llmEnabled || llmSelector == null) {
            log.debug("LLM disabled — returning static result");
            return staticResult;
        }

        log.info("Running LLM refinement on {} candidates", staticResult.selectedTests().size());
        SelectionResult refined = llmSelector.refine(staticResult.selectedTests(), changes);

        // Merge reasoning from both layers
        Map<String, String> mergedReasoning = new java.util.LinkedHashMap<>();
        mergedReasoning.putAll(staticResult.reasoning());
        mergedReasoning.putAll(refined.reasoning());

        return new SelectionResult(refined.selectedTests(), SelectionSource.HYBRID, mergedReasoning);
    }
}
