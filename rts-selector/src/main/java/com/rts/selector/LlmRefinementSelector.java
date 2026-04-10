package com.rts.selector;

import com.rts.core.model.*;
import com.rts.core.spi.LlmClient;
import com.rts.llm.LlmResponseParser;
import com.rts.llm.PromptTemplates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Refines a candidate test set produced by the {@link StaticSelector} using an LLM.
 *
 * <p>The LLM can only <em>remove</em> tests from the candidate set — it cannot add new ones.
 * This ensures a conservative bias: if the LLM is uncertain or returns an invalid response,
 * the full candidate set is returned unchanged.
 */
public class LlmRefinementSelector {

    private static final Logger log = LoggerFactory.getLogger(LlmRefinementSelector.class);

    private final LlmClient llmClient;
    private final PromptTemplates promptTemplates;
    private final LlmResponseParser responseParser;

    /**
     * Creates a refinement selector with the given LLM dependencies.
     *
     * @param llmClient      client used to call the model
     * @param promptTemplates template builder for the selection prompt
     * @param responseParser  parser for the model's JSON response
     */
    public LlmRefinementSelector(LlmClient llmClient,
                                  PromptTemplates promptTemplates,
                                  LlmResponseParser responseParser) {
        this.llmClient = llmClient;
        this.promptTemplates = promptTemplates;
        this.responseParser = responseParser;
    }

    /**
     * Filters the candidate test set by asking the LLM which tests can be safely skipped.
     *
     * @param candidates candidate tests from the static layer
     * @param changes    the changes that triggered this selection run
     * @return selection result with source {@link SelectionSource#LLM_REFINED};
     *         falls back to the full candidate set on any error
     */
    public SelectionResult refine(List<TestCase> candidates, List<ChangeInfo> changes) {
        if (candidates.isEmpty()) {
            return new SelectionResult(List.of(), SelectionSource.LLM_REFINED, Map.of());
        }

        String prompt = promptTemplates.buildSelectionPrompt(candidates, changes);

        String rawResponse;
        try {
            rawResponse = llmClient.complete(prompt);
        } catch (LlmClient.LlmException e) {
            log.warn("LLM call failed, returning all candidates unchanged: {}", e.getMessage());
            return fallback(candidates, "LLM call failed: " + e.getMessage());
        }

        LlmResponseParser.ParsedResponse parsed = responseParser.parse(rawResponse);
        if (!parsed.isValid()) {
            log.warn("LLM response invalid, returning all candidates unchanged: {}", parsed.errorMessage());
            return fallback(candidates, "Invalid LLM response: " + parsed.errorMessage());
        }

        // Build a set of (class, method) pairs the LLM says can be skipped
        Set<String> skippableIds = parsed.skippableTests().stream()
                .map(s -> s.testClass() + "#" + s.testMethod())
                .collect(Collectors.toSet());

        List<TestCase> filtered = candidates.stream()
                .filter(t -> !skippableIds.contains(t.id()))
                .collect(Collectors.toList());

        int removed = candidates.size() - filtered.size();
        log.info("LLM refinement: removed {}/{} tests (confidence={})",
                removed, candidates.size(), parsed.confidence());

        Map<String, String> reasoning = buildReasoning(candidates, parsed);
        return new SelectionResult(List.copyOf(filtered), SelectionSource.LLM_REFINED, reasoning);
    }

    // -------------------------------------------------------------------------

    private SelectionResult fallback(List<TestCase> candidates, String reason) {
        Map<String, String> reasoning = new LinkedHashMap<>();
        reasoning.put("_fallback", reason);
        return new SelectionResult(List.copyOf(candidates), SelectionSource.LLM_REFINED, reasoning);
    }

    private Map<String, String> buildReasoning(List<TestCase> candidates,
                                                LlmResponseParser.ParsedResponse parsed) {
        Map<String, String> reasoning = new LinkedHashMap<>();
        if (parsed.reasoningSummary() != null) {
            reasoning.put("_summary", parsed.reasoningSummary());
        }
        for (LlmResponseParser.SkippableTest skip : parsed.skippableTests()) {
            String id = skip.testClass() + "#" + skip.testMethod();
            reasoning.put(id, "SKIPPED: " + skip.reason());
        }
        return reasoning;
    }
}
