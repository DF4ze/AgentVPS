package fr.ses10doigts.agentvps.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeCliResultTest {

    // Extrait reel d'un appel "claude -p ... --output-format json" (CLI v2.1.250),
    // capture sur le VPS (utilisateur agentvps) le 28/08/2026 - voir roadmap-implementation.md.
    private static final String SAMPLE_JSON = """
            {"is_error":false,"duration_api_ms":2004,"num_turns":1,"stop_reason":"end_turn",
            "session_id":"92faf56e-f57d-4fd6-9e68-fe12ba39b479","total_cost_usd":0.0231652,
            "usage":{"input_tokens":2,"cache_creation_input_tokens":4648,"cache_read_input_tokens":22146,
            "output_tokens":14},"subtype":"success","result":"Hi! What can I help you with today?",
            "type":"result","duration_ms":2073,"uuid":"d528176e-9582-4fd8-89fa-0112a447a2f1"}
            """;

    @Test
    void deserialisesRealClaudeCliOutput() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        ClaudeCliResult result = mapper.readValue(SAMPLE_JSON, ClaudeCliResult.class);

        assertThat(result.isError()).isFalse();
        assertThat(result.getSessionId()).isEqualTo("92faf56e-f57d-4fd6-9e68-fe12ba39b479");
        assertThat(result.getResult()).isEqualTo("Hi! What can I help you with today?");
        assertThat(result.getSubtype()).isEqualTo("success");
        assertThat(result.getNumTurns()).isEqualTo(1);
        assertThat(result.getTotalCostUsd()).isEqualTo(0.0231652);
        assertThat(result.getUsage()).isNotNull();
        assertThat(result.getUsage().getCacheReadInputTokens()).isEqualTo(22146);
    }

    @Test
    void ignoresUnknownFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String jsonWithExtra = SAMPLE_JSON.replace(
                "\"type\":\"result\"", "\"type\":\"result\",\"some_future_field\":{\"nested\":true}");

        ClaudeCliResult result = mapper.readValue(jsonWithExtra, ClaudeCliResult.class);

        assertThat(result.getSessionId()).isEqualTo("92faf56e-f57d-4fd6-9e68-fe12ba39b479");
    }
}
