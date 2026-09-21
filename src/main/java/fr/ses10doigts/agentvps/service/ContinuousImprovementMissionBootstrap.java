package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.model.NotificationPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Seede automatiquement, au demarrage, la tache recurrente "amelioration-continue"
 * (etape 2/3 du plan documente dans la memoire projet "continuous_improvement_capture" :
 * capture -> EXTRACTION -> banque de scripts) - mode AGENT_MISSION, projet "system" (seul
 * projet dont le cwd remonte a la racine du workspace et peut donc lire
 * conversation-logs/<projet>/chat.jsonl de TOUS les projets, voir ProjectService.ELEVATED_PROJECT_SLUG
 * et la memoire projet "god_mode_system_project").
 *
 * Contrairement au reste des taches recurrentes de ce projet (toutes creees a la main via
 * l'assistant conversationnel /tache new, voir memoire projet "phase7_scheduler_implementation"),
 * celle-ci est seedee par code : demande explicite de Clem (03/09/2026) - la tache doit exister
 * DESACTIVEE des le demarrage, prete a etre activee plus tard (/tache enable amelioration-continue)
 * une fois qu'il aura pu constater manuellement le contenu des captures JSONL. Utilise
 * RecurringTaskService directement (PAS RecurringTaskManager, qui planifierait la tache live
 * immediatement apres sa creation) : la tache reste simplement absente du TaskScheduler tant
 * qu'elle est DISABLED (voir RecurringTaskScheduler.onApplicationEvent, qui ne planifie au
 * demarrage que les taches ACTIVE) - aucun risque qu'elle se declenche avant d'etre activee.
 *
 * Idempotent (verifie l'existence de la tache avant de la creer, ne fait rien si elle existe
 * deja - y compris si Clem l'a depuis activee ou modifiee) et tolerant a une initialisation
 * incomplete du projet "system" (jamais d'echec bloquant du demarrage pour ce seeding best-effort).
 */
@Component
@Order(10)
@RequiredArgsConstructor
@Slf4j
public class ContinuousImprovementMissionBootstrap implements ApplicationListener<ApplicationReadyEvent> {

    static final String TASK_NAME = "amelioration-continue";
    static final String TASK_DESCRIPTION =
            "Amelioration continue : analyse des captures de conversation (conversation-logs/**/chat.jsonl, "
                    + "raisonnement inclus) pour proposer des patterns/scripts candidats - aucune ecriture hors "
                    + "de conversation-logs/analysis/script-candidates.md";
    static final String TASK_CRON = "@weekly";

    private static final String MISSION_PROMPT = loadPromptResource("continuous-improvement-mission-prompt.txt");

    private final RecurringTaskService recurringTaskService;
    private final ProjectService projectService;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        seedIfNeeded();
    }

    void seedIfNeeded() {
        if (recurringTaskService.findTask(TASK_NAME).isPresent()) {
            log.debug("Tache recurrente '{}' deja presente : rien a seeder", TASK_NAME);
            return;
        }
        if (projectService.findProjectByName(ProjectService.ELEVATED_PROJECT_SLUG).isEmpty()) {
            log.info("Projet '{}' indisponible : tache '{}' pas encore seedee (nouvelle tentative au "
                            + "prochain demarrage)", ProjectService.ELEVATED_PROJECT_SLUG, TASK_NAME);
            return;
        }
        try {
            recurringTaskService.createAgentMissionTask(TASK_NAME, ProjectService.ELEVATED_PROJECT_SLUG,
                    MISSION_PROMPT, TASK_CRON, NotificationPolicy.ALWAYS, TASK_DESCRIPTION);
            // createAgentMissionTask() cree systematiquement la tache ACTIVE (comportement standard,
            // partage avec /tache new) - on la desactive explicitement juste apres, comme demande par
            // Clem : la tache existe et est prete, mais ne se declenchera pas tant qu'il ne l'aura pas
            // activee lui-meme (/tache enable). Fenetre entre les deux appels sans consequence : ni cet
            // appel ni RecurringTaskScheduler ne planifient quoi que ce soit en dehors de leurs propres
            // methodes schedule()/onApplicationEvent(), jamais invoquees ici.
            recurringTaskService.setEnabled(TASK_NAME, false);
            log.info("Tache recurrente '{}' creee automatiquement (DESACTIVEE par defaut, cron={} une fois "
                    + "activee via /tache enable {})", TASK_NAME, TASK_CRON, TASK_NAME);
        } catch (RecurringTaskException e) {
            log.warn("Echec de la creation automatique de la tache '{}' : {}", TASK_NAME, e.getMessage());
        }
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
