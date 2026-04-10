package com.rts.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Configuration loaded from {@code rts-config.yaml} or environment variables.
 *
 * <p>Environment variables take precedence over file-based configuration.
 * Variable references in the form {@code ${VAR_NAME}} or {@code ${VAR:default}}
 * are resolved at load time.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RtsConfig {

    private static final Logger log = LoggerFactory.getLogger(RtsConfig.class);
    private static final String CONFIG_FILE = "rts-config.yaml";

    private LlmConfig llm = new LlmConfig();

    /** Returns the LLM configuration section. */
    public LlmConfig getLlm() { return llm; }
    public void setLlm(LlmConfig llm) { this.llm = llm; }

    /**
     * Loads configuration from the given directory (looks for {@code rts-config.yaml}).
     * Falls back to defaults if no file is found.
     *
     * @param searchDir directory to look for the config file
     * @return loaded configuration
     */
    public static RtsConfig load(Path searchDir) {
        Path configFile = searchDir.resolve(CONFIG_FILE);
        if (Files.exists(configFile)) {
            log.debug("Loading config from {}", configFile);
            try (InputStream is = Files.newInputStream(configFile)) {
                ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
                RtsConfig config = mapper.readValue(is, RtsConfig.class);
                config.resolveEnvVars();
                return config;
            } catch (IOException e) {
                log.warn("Failed to read {}: {} — using defaults", configFile, e.getMessage());
            }
        }
        RtsConfig defaults = new RtsConfig();
        defaults.resolveEnvVars();
        return defaults;
    }

    private void resolveEnvVars() {
        llm.setEndpoint(resolveEnvRef(llm.getEndpoint()));
        llm.setApiKey(resolveEnvRef(llm.getApiKey()));
        llm.setModel(resolveEnvRef(llm.getModel()));
    }

    private String resolveEnvRef(String value) {
        if (value == null || !value.startsWith("${")) {
            return value;
        }
        String inner = value.substring(2, value.length() - 1);
        String[] parts = inner.split(":", 2);
        String envVar = parts[0].trim();
        String defaultVal = parts.length > 1 ? parts[1].trim() : null;
        return Optional.ofNullable(System.getenv(envVar)).orElse(defaultVal);
    }

    // -------------------------------------------------------------------------

    /** LLM-specific configuration block. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LlmConfig {
        private boolean enabled = false;
        private String provider = "openai";
        private String endpoint = "${RTS_LLM_ENDPOINT}";
        private String apiKey = "${RTS_LLM_API_KEY}";
        private String model = "${RTS_LLM_MODEL:gpt-4o-mini}";
        private int maxTokens = 2000;
        private double temperature = 0.1;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
    }
}
