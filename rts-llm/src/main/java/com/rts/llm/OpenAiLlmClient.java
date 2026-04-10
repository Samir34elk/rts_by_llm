package com.rts.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rts.core.spi.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * {@link LlmClient} implementation compatible with the OpenAI chat completions API.
 *
 * <p>Works with any OpenAI-compatible endpoint (OpenAI, Azure OpenAI, Ollama, LM Studio, etc.).
 * Credentials are always read from the constructor parameters — never hard-coded.
 */
public class OpenAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Creates a client with full configuration.
     *
     * @param endpoint    base URL of the API (e.g. {@code https://api.openai.com})
     * @param apiKey      bearer token; pass an empty string for unauthenticated local endpoints
     * @param model       model identifier (e.g. {@code gpt-4o-mini})
     * @param maxTokens   maximum tokens in the completion
     * @param temperature sampling temperature (0.0 = deterministic)
     */
    public OpenAiLlmClient(String endpoint, String apiKey, String model, int maxTokens, double temperature) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sends the prompt as a single {@code user} message to the chat completions endpoint.
     *
     * @throws LlmException if the HTTP request fails or the API returns a non-200 response
     */
    @Override
    public String complete(String prompt) {
        String body = buildRequestBody(prompt);
        log.debug("Sending request to {} with model {}", endpoint, model);

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + CHAT_COMPLETIONS_PATH))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(body));

            if (apiKey != null && !apiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new LlmException("API returned status " + response.statusCode() + ": " + response.body());
            }

            return extractContent(response.body());

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("HTTP request failed: " + e.getMessage(), e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code true} if endpoint and model are both configured (non-blank).
     * Does not perform a live network check.
     */
    @Override
    public boolean isAvailable() {
        return endpoint != null && !endpoint.isBlank()
                && model != null && !model.isBlank();
    }

    // -------------------------------------------------------------------------

    private String buildRequestBody(String prompt) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", model);
            root.put("max_tokens", maxTokens);
            root.put("temperature", temperature);

            ArrayNode messages = root.putArray("messages");
            ObjectNode userMessage = messages.addObject();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new LlmException("Failed to serialize request body", e);
        }
    }

    private String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return root.path("choices").get(0)
                    .path("message")
                    .path("content")
                    .asText();
        } catch (Exception e) {
            throw new LlmException("Failed to parse API response: " + e.getMessage(), e);
        }
    }
}
