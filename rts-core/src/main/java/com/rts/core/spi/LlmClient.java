package com.rts.core.spi;

/**
 * Abstraction over a Large Language Model HTTP endpoint.
 *
 * <p>Implementations must be stateless; all context is passed per-call.
 */
public interface LlmClient {

    /**
     * Sends a prompt to the model and returns the raw text response.
     *
     * @param prompt the full prompt to send (system + user content already merged)
     * @return the model's text output; never {@code null}
     * @throws LlmException if the request fails or the model returns an error
     */
    String complete(String prompt);

    /**
     * Returns {@code true} if this client is properly configured and reachable.
     * Implementations should perform a lightweight check (e.g. verify config, not a real request).
     *
     * @return availability flag
     */
    boolean isAvailable();

    /**
     * Thrown when the LLM call fails for any reason (network error, auth failure, etc.).
     */
    class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
