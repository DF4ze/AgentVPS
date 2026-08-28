package fr.ses10doigts.agentvps.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration de l'invocation du CLI Claude Code (claude -p ...).
 * Voir roadmap-implementation.md, Phase 3, point 3, et Phase 1 (securite).
 */
@Data
@ConfigurationProperties(prefix = "agentvps.claude")
public class ClaudeCliProperties {

    /** Chemin absolu du binaire claude (depend de l'utilisateur systeme, voir Phase 0bis). */
    private String binaryPath = "/home/agentvps/.local/bin/claude";

    /** Delai maximum d'attente d'une reponse, avant destruction forcee du process. */
    private int timeoutSeconds = 120;

    /** Si active, un appel de test est effectue au demarrage de l'application (voir ClaudeCliSmokeTestRunner). */
    private boolean smokeTestEnabled = false;

    /** Prompt utilise pour l'appel de test au demarrage. */
    private String smokeTestPrompt = "Reponds uniquement avec le mot : pong";

    /**
     * Mode de permission passe a claude via --permission-mode (Phase 1 de la roadmap).
     * "dontAsk" refuse automatiquement tout ce qui n'est pas pre-autorise dans le
     * settings.json (voir settingsPath) - indispensable en headless, sans personne
     * pour repondre a une demande de confirmation. Vide/blank = flag non transmis
     * (comportement par defaut de claude, deconseille en production : sans mode
     * explicite, claude ne peut faire AUCUNE action sur le filesystem en headless,
     * voir le test reel du 28/08/2026).
     */
    private String permissionMode = "dontAsk";

    /**
     * Chemin du fichier settings.json (regles allow/ask/deny) passe via --settings.
     * Vide/blank = flag non transmis.
     */
    private String settingsPath = "/home/agentvps/.config/agentvps/claude-settings.json";
}
