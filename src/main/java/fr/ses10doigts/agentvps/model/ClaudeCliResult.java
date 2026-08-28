package fr.ses10doigts.agentvps.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Resultat structure d'un appel "claude -p ... --output-format json".
 * Ne reprend que les champs utiles a AgentVPS ; le reste de la sortie
 * (statistiques d'usage detaillees, sous-agents, etc.) est ignore.
 *
 * Schema verifie en conditions reelles le 28/08/2026 sur le VPS
 * (utilisateur agentvps, Claude Code CLI v2.1.250) via la gateway SSH.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClaudeCliResult {

    @JsonProperty("type")
    private String type;

    @JsonProperty("subtype")
    private String subtype;

    @JsonProperty("is_error")
    private boolean error;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("result")
    private String result;

    @JsonProperty("stop_reason")
    private String stopReason;

    @JsonProperty("num_turns")
    private int numTurns;

    @JsonProperty("duration_ms")
    private long durationMs;

    @JsonProperty("total_cost_usd")
    private double totalCostUsd;

    @JsonProperty("usage")
    private Usage usage;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {

        @JsonProperty("input_tokens")
        private long inputTokens;

        @JsonProperty("output_tokens")
        private long outputTokens;

        @JsonProperty("cache_creation_input_tokens")
        private long cacheCreationInputTokens;

        @JsonProperty("cache_read_input_tokens")
        private long cacheReadInputTokens;
    }
}
