package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.config.ClaudeCliProperties;
import fr.ses10doigts.agentvps.model.ClaudeCliResult;
import fr.ses10doigts.agentvps.model.Project;
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
 * Orchestration d'un message @Chat libre (voir AgentVpsTelegramController) : construit
 * l'appel claude avec le socle system prompt fixe (regles VPS de prod, voir memoire projet
 * "prompting_architecture") et, si du, le renforcement periodique en prefixe du message
 * utilisateur - PAS via --append-system-prompt, pour eviter de faire grossir un system
 * prompt envoye a chaque appel (voir aussi ProjectOnboardingService pour le meme pattern
 * de chargement de prompt applique a l'interview de creation de projet).
 *
 * Separe de ProjectService (qui ne connait pas ClaudeCliService, meme raison que pour
 * ProjectOnboardingService) et du controller (garde le handler @Chat mince et cette
 * orchestration testable sans TelegramUpdateContext).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private static final String BASE_SYSTEM_PROMPT = loadPromptResource("prod-vps-rules-system-prompt.txt");
    private static final String REINFORCEMENT_PREFIX = loadPromptResource("periodic-reinforcement-prefix.txt");

    private final ProjectService projectService;
    private final ClaudeCliService claudeCliService;
    private final ClaudeCliProperties claudeCliProperties;

    /**
     * Envoie un message libre au projet actif : reprend sa conversation courante
     * (--resume) si elle existe, sinon en demarre une nouvelle. Determine si le
     * renforcement periodique doit etre injecte AVANT l'appel (voir
     * ProjectService.isReinforcementDue), puis met a jour le compteur de la conversation
     * APRES un appel reussi seulement (voir ProjectService.recordReinforcementOutcome) -
     * un appel en echec ne doit pas faire perdre un renforcement du.
     */
    public ClaudeCliResult sendMessage(Project project, String text) {
        String sessionId = project.getCurrentSessionId();
        boolean reinforcementDue = projectService.isReinforcementDue(
                project.getName(), sessionId, claudeCliProperties.getReinforcementEveryMessages());

        String prompt = reinforcementDue ? REINFORCEMENT_PREFIX + text : text;

        ClaudeCliResult result = claudeCliService.call(
                prompt, sessionId, Path.of(project.getWorkingDirectory()), BASE_SYSTEM_PROMPT);

        if (sessionId == null) {
            // Premier message de la conversation : le compteur du nouvel objet Conversation
            // demarre deja a 0 (voir Conversation.messagesSinceReinforcement), coherent avec
            // reinforcementDue=true systematique sur ce cas - rien a faire de plus ici.
            projectService.recordConversationStart(project.getName(), result.getSessionId(), null);
        } else {
            projectService.touchConversation(project.getName(), sessionId);
            projectService.recordReinforcementOutcome(project.getName(), sessionId, reinforcementDue);
        }

        return result;
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
