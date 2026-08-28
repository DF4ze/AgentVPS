package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.config.ClaudeCliProperties;
import fr.ses10doigts.agentvps.model.ClaudeCliResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Effectue un unique appel claude au demarrage de l'application, pour valider
 * de bout en bout l'invocation ProcessBuilder + le parsing JSON en conditions
 * reelles (voir roadmap-implementation.md, Phase 3, point 3).
 *
 * Desactive par defaut : activer via agentvps.claude.smoke-test-enabled=true
 * (ou la variable d'environnement AGENTVPS_CLAUDE_SMOKE_TEST=true) sur un
 * environnement ou le binaire claude est installe et authentifie (le VPS,
 * pas le poste de dev).
 */
@Component
@ConditionalOnProperty(prefix = "agentvps.claude", name = "smoke-test-enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ClaudeCliSmokeTestRunner implements CommandLineRunner {

    private final ClaudeCliService claudeCliService;
    private final ClaudeCliProperties properties;

    @Override
    public void run(String... args) {
        log.info("Smoke test claude CLI au demarrage - prompt : \"{}\"", properties.getSmokeTestPrompt());
        try {
            ClaudeCliResult result = claudeCliService.call(properties.getSmokeTestPrompt());
            log.info("Smoke test claude CLI OK - session_id={}, num_turns={}, cost_usd={}, reponse : \"{}\"",
                    result.getSessionId(), result.getNumTurns(), result.getTotalCostUsd(), result.getResult());
        } catch (ClaudeCliException e) {
            log.error("Smoke test claude CLI en echec : {}", e.getMessage(), e);
        }
    }
}
