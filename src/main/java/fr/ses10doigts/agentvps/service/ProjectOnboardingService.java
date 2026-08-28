package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.model.ClaudeCliResult;
import fr.ses10doigts.agentvps.model.Conversation;
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
 * Orchestration de la creation d'un projet et de son interview initiale (decision
 * Clem du 28/08/2026 : /projet new declenche automatiquement un premier appel claude
 * qui interroge l'utilisateur puis ecrit le CLAUDE.md du projet, voir le prompt
 * dedie project-onboarding-system-prompt.txt).
 *
 * Separe de ProjectService (qui ne connait pas ClaudeCliService) pour garder la
 * pure gestion de projets/conversations testable sans spawn de process claude.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectOnboardingService {

    /**
     * Prompt utilisateur "de lancement" envoye a claude pour demarrer l'interview : le
     * contenu reel de l'interview vient du system-prompt (voir SYSTEM_PROMPT), ce prompt
     * ne sert qu'a declencher la premiere reponse de claude (sa premiere question).
     */
    static final String KICKOFF_PROMPT =
            "Nouveau projet cree, sans CLAUDE.md pour l'instant. Demarre l'interview.";

    static final String LABEL_INTERVIEW_INITIALE = "Mise en place initiale";

    private static final String SYSTEM_PROMPT = loadOnboardingSystemPrompt();

    private final ProjectService projectService;
    private final ClaudeCliService claudeCliService;

    /**
     * Cree le projet (voir ProjectService.createProject) puis lance immediatement l'appel
     * claude d'interview dans son repertoire de travail. Si l'appel claude echoue
     * (ClaudeCliException), le projet reste cree (dossier + entree dans le store) mais
     * sans conversation : pas de rollback automatique, l'utilisateur peut reessayer une
     * nouvelle conversation sur ce projet une fois le probleme identifie.
     *
     * @return le projet cree et la premiere question posee par claude (result.result())
     */
    public OnboardingResult createProjectAndStartOnboarding(String rawName) {
        Project project = projectService.createProject(rawName);

        ClaudeCliResult result = claudeCliService.call(
                KICKOFF_PROMPT, null, Path.of(project.getWorkingDirectory()), SYSTEM_PROMPT);

        Conversation conversation = projectService.recordConversationStart(
                project.getName(), result.getSessionId(), LABEL_INTERVIEW_INITIALE);

        log.info("Interview de creation demarree pour le projet '{}' (session_id={})",
                project.getName(), result.getSessionId());

        return new OnboardingResult(project, conversation, result.getResult());
    }

    private static String loadOnboardingSystemPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/project-onboarding-system-prompt.txt");
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Impossible de charger le prompt d'onboarding projet (prompts/project-onboarding-system-prompt.txt)", e);
        }
    }

    /** Resultat de la creation d'un projet avec interview : le projet, sa premiere conversation, et la question posee. */
    public record OnboardingResult(Project project, Conversation conversation, String firstClaudeMessage) {
    }
}
