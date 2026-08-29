package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.config.RecurringTaskProperties;
import fr.ses10doigts.agentvps.model.ClaudeCliResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Execute une tache recurrente en mode "mission agent" (RecurringTaskExecutionMode.AGENT_MISSION,
 * ajoute le 29/08/2026 - demande Clem) : contrairement a ScriptExecutionService (ProcessBuilder
 * direct), ce service delegue a ClaudeCliService.call() - meme mecanisme que ChatService pour
 * le chat interactif, mais avec des differences assumees :
 *
 * - Toujours un appel "a froid" (resumeSessionId=null) : chaque declenchement demarre une
 *   nouvelle conversation claude, sans historique des runs precedents. La memoire durable
 *   d'une execution a l'autre passe par CLAUDE.md du projet (meme principe que le chat), pas
 *   par --resume - decision retenue le 29/08/2026 pour rester simple ; a revoir si un cas
 *   d'usage reel demande explicitement une continuite entre executions.
 * - System prompt = le meme socle que le chat (prod-vps-rules-system-prompt.txt, regles VPS
 *   de prod, permissions, pas de Markdown Telegram) + un addendum specifique
 *   (agent-mission-system-prompt-addendum.txt) qui explique a l'agent qu'il tourne seul, sans
 *   personne pour repondre a une question, et que sa toute derniere reponse est ce qui sera
 *   envoye tel quel a Clem sur Telegram.
 * - Timeout dedie (RecurringTaskProperties.agentMissionTimeoutSeconds), distinct de
 *   agentvps.claude.timeout-seconds (chat interactif) : une mission peut enchainer plusieurs
 *   appels reseau/MCP, largement au-dela des 120s par defaut du chat.
 *
 * Un echec (timeout, process, ou is_error=true renvoye par claude) remonte tel quel comme une
 * ClaudeCliException - RecurringTaskScheduler l'attrape deja generiquement (meme chemin que
 * ScriptExecutionException pour le mode SCRIPT) et enregistre RunStatus.ERROR.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentMissionExecutionService {

    private static final String BASE_SYSTEM_PROMPT = loadPromptResource("prod-vps-rules-system-prompt.txt");
    private static final String MISSION_PROMPT_ADDENDUM = loadPromptResource("agent-mission-system-prompt-addendum.txt");
    private static final String APPEND_SYSTEM_PROMPT = BASE_SYSTEM_PROMPT + "\n\n" + MISSION_PROMPT_ADDENDUM;

    private final ClaudeCliService claudeCliService;
    private final RecurringTaskProperties properties;

    public ClaudeCliResult run(String missionPrompt, Path workingDirectory) {
        if (missionPrompt == null || missionPrompt.isBlank()) {
            throw new IllegalArgumentException("La mission a confier a l'agent ne peut pas etre vide");
        }
        log.info("Execution mission agent (cwd={}, timeout={}s)", workingDirectory, properties.getAgentMissionTimeoutSeconds());
        return claudeCliService.call(
                missionPrompt, null, workingDirectory, APPEND_SYSTEM_PROMPT, properties.getAgentMissionTimeoutSeconds());
    }

    private static String loadPromptResource(String fileName) {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + fileName);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de charger le prompt prompts/" + fileName, e);
        }
    }
}
