package com.rts.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmResponseParserTest {

    private LlmResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new LlmResponseParser();
    }

    @Test
    void parse_validResponseWithSkippableTests() {
        String json = """
                {
                  "skippable_tests": [
                    {
                      "test_class": "com.example.FooTest",
                      "test_method": "testSomething",
                      "reason": "The changed method is not called by this test path"
                    }
                  ],
                  "confidence": 0.95,
                  "reasoning_summary": "Only one test is affected by the change"
                }
                """;

        LlmResponseParser.ParsedResponse response = parser.parse(json);

        assertThat(response.isValid()).isTrue();
        assertThat(response.skippableTests()).hasSize(1);
        assertThat(response.skippableTests().get(0).testClass()).isEqualTo("com.example.FooTest");
        assertThat(response.skippableTests().get(0).testMethod()).isEqualTo("testSomething");
        assertThat(response.confidence()).isEqualTo(0.95);
        assertThat(response.reasoningSummary()).isEqualTo("Only one test is affected by the change");
    }

    @Test
    void parse_validResponseWithNoSkippableTests() {
        String json = """
                {
                  "skippable_tests": [],
                  "confidence": 0.5
                }
                """;

        LlmResponseParser.ParsedResponse response = parser.parse(json);

        assertThat(response.isValid()).isTrue();
        assertThat(response.skippableTests()).isEmpty();
        assertThat(response.reasoningSummary()).isNull();
    }

    @Test
    void parse_invalidJson_returnsInvalidResponse() {
        LlmResponseParser.ParsedResponse response = parser.parse("not json at all");

        assertThat(response.isValid()).isFalse();
        assertThat(response.errorMessage()).isNotBlank();
        assertThat(response.skippableTests()).isEmpty();
    }

    @Test
    void parse_missingSkippableTests_returnsInvalid() {
        String json = """
                {
                  "confidence": 0.8
                }
                """;

        LlmResponseParser.ParsedResponse response = parser.parse(json);

        assertThat(response.isValid()).isFalse();
        assertThat(response.errorMessage()).contains("skippable_tests");
    }

    @Test
    void parse_missingConfidence_returnsInvalid() {
        String json = """
                {
                  "skippable_tests": []
                }
                """;

        LlmResponseParser.ParsedResponse response = parser.parse(json);

        assertThat(response.isValid()).isFalse();
        assertThat(response.errorMessage()).contains("confidence");
    }

    @Test
    void parse_confidenceOutOfRange_returnsInvalid() {
        String json = """
                {
                  "skippable_tests": [],
                  "confidence": 1.5
                }
                """;

        LlmResponseParser.ParsedResponse response = parser.parse(json);

        assertThat(response.isValid()).isFalse();
    }

    @Test
    void parse_stripsMarkdownCodeFences() {
        String raw = """
                ```json
                {
                  "skippable_tests": [],
                  "confidence": 0.9
                }
                ```
                """;

        LlmResponseParser.ParsedResponse response = parser.parse(raw);

        assertThat(response.isValid()).isTrue();
    }

    @Test
    void parse_nullResponse_returnsInvalid() {
        LlmResponseParser.ParsedResponse response = parser.parse(null);

        assertThat(response.isValid()).isFalse();
    }

    @Test
    void parse_emptyResponse_returnsInvalid() {
        LlmResponseParser.ParsedResponse response = parser.parse("  ");

        assertThat(response.isValid()).isFalse();
    }

    @Test
    void parse_skipsEntryMissingRequiredFields() {
        String json = """
                {
                  "skippable_tests": [
                    { "test_class": "com.example.Foo" }
                  ],
                  "confidence": 0.8
                }
                """;

        LlmResponseParser.ParsedResponse response = parser.parse(json);

        // Valid structure, but the malformed entry is skipped
        assertThat(response.isValid()).isTrue();
        assertThat(response.skippableTests()).isEmpty();
    }

    @Test
    void parse_jsonWithLeadingText_extractsJsonBlock() {
        String raw = "Here is my analysis:\n{ \"skippable_tests\": [], \"confidence\": 0.7 }";

        LlmResponseParser.ParsedResponse response = parser.parse(raw);

        assertThat(response.isValid()).isTrue();
    }
}
