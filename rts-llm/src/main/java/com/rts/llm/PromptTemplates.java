package com.rts.llm;

import com.rts.core.model.ChangeInfo;
import com.rts.core.model.TestCase;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds structured prompts for the LLM test selection refinement step.
 *
 * <p>Prompts are designed to elicit a conservative response: the LLM must justify
 * why a test can be <em>skipped</em>, not why it should run. When in doubt, the LLM
 * should leave the test in the run set.
 */
public class PromptTemplates {

    private static final String SYSTEM_INSTRUCTION = """
            You are an expert software engineer specializing in test impact analysis.
            Your task is to analyze code changes and identify which tests from a given candidate list can be SAFELY SKIPPED.

            IMPORTANT RULES:
            - You can only REMOVE tests from the candidate list, never add new ones.
            - When in doubt, do NOT skip a test. False negatives (missing a broken test) are unacceptable.
            - Only skip a test if you are highly confident (>95%) that the change cannot affect its behavior.
            - Your response MUST be valid JSON matching the specified schema exactly.
            """;

    private static final String RESPONSE_SCHEMA = """
            {
              "skippable_tests": [
                {
                  "test_class": "fully.qualified.TestClassName",
                  "test_method": "methodName",
                  "reason": "Brief explanation (max 200 chars)"
                }
              ],
              "confidence": 0.0,
              "reasoning_summary": "Overall summary of your analysis (max 500 chars)"
            }
            """;

    /**
     * Builds the full selection prompt combining system instructions, change context,
     * candidate tests, and the expected response schema.
     *
     * @param candidates candidate tests to evaluate
     * @param changes    the changes that triggered this selection run
     * @return complete prompt string ready to send to the model
     */
    public String buildSelectionPrompt(List<TestCase> candidates, List<ChangeInfo> changes) {
        return SYSTEM_INSTRUCTION + "\n\n"
                + "## Code Changes\n\n"
                + formatChanges(changes) + "\n\n"
                + "## Candidate Tests (selected by static analysis)\n\n"
                + formatCandidates(candidates) + "\n\n"
                + "## Task\n\n"
                + "From the candidate tests listed above, identify which ones can be SAFELY SKIPPED.\n"
                + "Return ONLY a JSON object matching this schema (no markdown, no explanation outside JSON):\n\n"
                + RESPONSE_SCHEMA;
    }

    // -------------------------------------------------------------------------

    private String formatChanges(List<ChangeInfo> changes) {
        StringBuilder sb = new StringBuilder();
        for (ChangeInfo change : changes) {
            sb.append("- **").append(change.type()).append("**: `").append(change.fullyQualifiedClass()).append("`");
            sb.append(" (`").append(change.filePath()).append("`)");
            if (!change.changedMethods().isEmpty()) {
                sb.append("\n  - Changed methods: ")
                        .append(change.changedMethods().stream()
                                .map(m -> "`" + m + "`")
                                .collect(Collectors.joining(", ")));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String formatCandidates(List<TestCase> candidates) {
        StringBuilder sb = new StringBuilder();
        for (TestCase test : candidates) {
            sb.append("- `").append(test.className()).append("#").append(test.methodName()).append("`");
            if (!test.coveredElements().isEmpty()) {
                sb.append("\n  - Covers: ")
                        .append(test.coveredElements().stream()
                                .limit(5)
                                .map(e -> "`" + e + "`")
                                .collect(Collectors.joining(", ")));
                if (test.coveredElements().size() > 5) {
                    sb.append(", ... (").append(test.coveredElements().size() - 5).append(" more)");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
