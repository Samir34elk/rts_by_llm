package com.rts.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses and validates the JSON response produced by the LLM during the refinement step.
 *
 * <p>The expected schema requires {@code skippable_tests} (array) and {@code confidence}
 * (number in [0, 1]). {@code reasoning_summary} is optional.
 *
 * <p>On any parse or validation failure, {@link ParsedResponse#isValid()} returns
 * {@code false} and the caller falls back to using the full candidate set.
 */
public class LlmResponseParser {

    private static final Logger log = LoggerFactory.getLogger(LlmResponseParser.class);

    private final ObjectMapper objectMapper;

    /** Creates a parser with a default Jackson {@link ObjectMapper}. */
    public LlmResponseParser() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Parses the raw LLM output string into a {@link ParsedResponse}.
     *
     * <p>Attempts to extract a JSON object even when the model wraps it in markdown
     * code fences (``` ```json ... ``` ```).
     *
     * @param rawResponse the raw text returned by the model
     * @return parsed response; check {@link ParsedResponse#isValid()} before using
     */
    public ParsedResponse parse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return ParsedResponse.invalid("Empty response from LLM");
        }

        String json = extractJson(rawResponse);
        try {
            JsonNode root = objectMapper.readTree(json);
            return validate(root);
        } catch (Exception e) {
            log.warn("Failed to parse LLM response as JSON: {}", e.getMessage());
            return ParsedResponse.invalid("JSON parse error: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------

    private String extractJson(String raw) {
        String trimmed = raw.strip();
        // Strip markdown code fences
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (start >= 0 && end > start) {
                trimmed = trimmed.substring(start + 1, end).strip();
            }
        }
        // Find first '{' in case there's leading text
        int jsonStart = trimmed.indexOf('{');
        int jsonEnd = trimmed.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd >= jsonStart) {
            trimmed = trimmed.substring(jsonStart, jsonEnd + 1);
        }
        return trimmed;
    }

    private ParsedResponse validate(JsonNode root) {
        // Required: skippable_tests array
        if (!root.has("skippable_tests") || !root.get("skippable_tests").isArray()) {
            return ParsedResponse.invalid("Missing or invalid 'skippable_tests' array");
        }

        // Required: confidence number in [0, 1]
        if (!root.has("confidence") || !root.get("confidence").isNumber()) {
            return ParsedResponse.invalid("Missing or invalid 'confidence' field");
        }
        double confidence = root.get("confidence").asDouble();
        if (confidence < 0.0 || confidence > 1.0) {
            return ParsedResponse.invalid("'confidence' must be between 0 and 1, got " + confidence);
        }

        // Parse skippable tests
        List<SkippableTest> skippable = new ArrayList<>();
        for (JsonNode item : root.get("skippable_tests")) {
            if (!item.has("test_class") || !item.has("test_method") || !item.has("reason")) {
                log.warn("Skipping malformed entry in skippable_tests: {}", item);
                continue;
            }
            skippable.add(new SkippableTest(
                    item.get("test_class").asText(),
                    item.get("test_method").asText(),
                    item.get("reason").asText()
            ));
        }

        String summary = root.has("reasoning_summary")
                ? root.get("reasoning_summary").asText()
                : null;

        return ParsedResponse.valid(skippable, confidence, summary);
    }

    // =========================================================================
    // Result types
    // =========================================================================

    /**
     * Represents a single test that the LLM identified as safely skippable.
     *
     * @param testClass  fully-qualified test class name
     * @param testMethod test method name
     * @param reason     brief justification from the LLM
     */
    public record SkippableTest(String testClass, String testMethod, String reason) {}

    /**
     * The result of parsing an LLM response.
     */
    public static final class ParsedResponse {

        private final boolean valid;
        private final String errorMessage;
        private final List<SkippableTest> skippableTests;
        private final double confidence;
        private final String reasoningSummary;

        private ParsedResponse(boolean valid, String errorMessage, List<SkippableTest> skippableTests,
                               double confidence, String reasoningSummary) {
            this.valid = valid;
            this.errorMessage = errorMessage;
            this.skippableTests = skippableTests;
            this.confidence = confidence;
            this.reasoningSummary = reasoningSummary;
        }

        /** Creates a valid parsed response. */
        public static ParsedResponse valid(List<SkippableTest> tests, double confidence, String summary) {
            return new ParsedResponse(true, null, List.copyOf(tests), confidence, summary);
        }

        /** Creates an invalid response with the given error message. */
        public static ParsedResponse invalid(String errorMessage) {
            return new ParsedResponse(false, errorMessage, List.of(), 0.0, null);
        }

        /** Returns {@code true} if the response was successfully parsed and validated. */
        public boolean isValid() { return valid; }

        /** Returns the error message if invalid; {@code null} otherwise. */
        public String errorMessage() { return errorMessage; }

        /** Returns the list of tests to skip; empty if invalid. */
        public List<SkippableTest> skippableTests() { return skippableTests; }

        /** Returns the confidence score reported by the LLM. */
        public double confidence() { return confidence; }

        /** Returns the optional reasoning summary; may be {@code null}. */
        public String reasoningSummary() { return reasoningSummary; }
    }
}
