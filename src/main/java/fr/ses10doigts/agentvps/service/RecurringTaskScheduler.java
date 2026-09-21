package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.config.ClaudeCliProperties;
import fr.ses10doigts.agentvps.model.ClaudeCliResult;
import fr.ses10doigts.agentvps.model.Project;
import fr.ses10doigts.agentvps.model.RecurringTask;
import fr.ses10doigts.agentvps.model.RecurringTaskExecutionMode;
import fr.ses10doigts.agentvps.model.RecurringTaskRunOutcome;
import fr.ses10doigts.agentvps.model.RecurringTaskStatus;
import fr.ses10doigts.agentvps.model.RecurringTaskTriggerType;
import fr.ses10doigts.agentvps.model.RunStatus;
import fr.ses10doigts.agentvps.model.ScriptExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Cote "live" du moteur de taches recurrentes (roadmap Phase 7) : planifie/deplanifie
 * dynamiquement un CronTrigger par tache sur le TaskScheduler Spring (voir
 * SchedulingConfig), et execute effectivement le script au declenchement.
 *
 * Ne persiste/valide jamais une RecurringTask directement (voir RecurringTaskService) -
 * ce composant lit toujours la version la plus recente d'une tache au moment de son
 * declenchement (recurringTaskService.findTask), pour refleter un changement (nouvelle
 * commande, nouvelle politique de notification...) fait apres la planification initiale
 * sans avoir besoin de re-planifier a chaque edition mineure.
 *
 * Planifie au demarrage (ApplicationReadyEvent, pas @PostConstruct) toutes les taches
 * ACTIVE existantes : ApplicationReadyEvent garantit que le contexte Spring est
 * completement initialise (TelegramSender, etc.) avant qu'un premier declenchement ne
 * puisse survenir.
 *
 * Garde-fou d'idempotence (Clem, 29/08/2026) : un Set de noms de taches "en cours"
 * empeche deux executions concurrentes de la MEME tache (declenchement planifie qui
 * chevauche un run manuel /tache run, ou run precedent qui traine au-dela de son propre
 * intervalle) - l'execution qui arrive en second est simplement ignoree (loguee), pas
 * mise en file d'attente.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecurringTaskScheduler implements ApplicationListener<ApplicationReadyEvent> {

    private static final int OUTPUT_SUMMARY_MAX_LENGTH = 1500;

    private final TaskScheduler taskScheduler;
    private final RecurringTaskService recurringTaskService;
    private final ProjectService projectService;
    private final ScriptExecutionService scriptExecutionService;
    private final RecurringTaskNotifier notifier;
    private final AgentMissionExecutionService agentMissionExecutionService;
    private final ClaudeCliProperties claudeCliProperties;

    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();
    private final Set<String> runningTasks = ConcurrentHashMap.newKeySet();

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        recurringTaskService.listTasks().stream()
                .filter(task -> task.getStatus() == RecurringTaskStatus.ACTIVE)
                .forEach(this::schedule);
        log.info("Taches recurrentes planifiees au demarrage : {}", scheduledFutures.keySet());
    }

    /**
     * (Re)planifie une tache : annule d'abord toute planification existante du meme nom
     * (voir unschedule). Branche selon RecurringTaskTriggerType (ajoute le 29/08/2026) :
     * une tache ONE_TIME est planifiee pour un instant unique (TaskScheduler.schedule avec
     * un Instant, pas de CronTrigger) - elle ne se replanifie jamais d'elle-meme, voir
     * executeTask qui la desactive automatiquement une fois executee.
     */
    public void schedule(RecurringTask task) {
        unschedule(task.getName());
        String name = task.getName();

        if (task.getTriggerType() == RecurringTaskTriggerType.ONE_TIME) {
            Instant runAt = task.getScheduledAt();
            if (runAt == null || !runAt.isAfter(Instant.now())) {
                log.warn("Tache ponctuelle '{}' non planifiee (date d'execution manquante ou deja passee : {})",
                        name, runAt);
                return;
            }
            ScheduledFuture<?> future = taskScheduler.schedule(() -> executeTask(name, true), runAt);
            scheduledFutures.put(name, future);
            log.info("Tache ponctuelle '{}' planifiee pour {}", name, runAt);
            return;
        }

        CronTrigger trigger;
        try {
            trigger = new CronTrigger(task.getCronExpression());
        } catch (IllegalArgumentException e) {
            // Ne devrait pas arriver (cron deja valide a la creation, voir RecurringTaskService.validateCron)
            // mais on ne veut pas faire echouer tout le demarrage de l'application pour une tache.
            log.error("Expression cron invalide pour la tache '{}', non planifiee : {}", name, e.getMessage());
            return;
        }
        ScheduledFuture<?> future = taskScheduler.schedule(() -> executeTask(name, true), trigger);
        scheduledFutures.put(name, future);
        log.info("Tache recurrente '{}' planifiee (cron={})", name, task.getCronExpression());
    }

    /** Annule la planification live d'une tache (ne touche pas a sa persistance - voir RecurringTaskService). */
    public void unschedule(String name) {
        ScheduledFuture<?> future = scheduledFutures.remove(name);
        if (future != null) {
            future.cancel(false);
            log.info("Tache recurrente '{}' deplanifiee", name);
        }
    }

    /** Execution manuelle (/tache run) : synchrone, ne declenche PAS la notification automatique (le controller repond directement avec le resultat). */
    public RecurringTaskRunOutcome runNow(String name) {
        return executeTask(name, false);
    }

    /**
     * @param triggeredBySchedule true pour un declenchement cron normal (applique la
     *                            politique de notification et ignore une tache
     *                            entre-temps desactivee) ; false pour /tache run
     *                            (execution manuelle meme si la tache est desactivee,
     *                            utile pour la tester avant de la reactiver - pas de
     *                            notification automatique, la reponse est le retour).
     */
    private RecurringTaskRunOutcome executeTask(String name, boolean triggeredBySchedule) {
        if (!runningTasks.add(name)) {
            log.warn("Tache recurrente '{}' deja en cours d'execution : declenchement ignore (chevauchement)", name);
            return new RecurringTaskRunOutcome(RunStatus.UNKNOWN, null, null,
                    "Deja en cours d'execution : ce declenchement a ete ignore");
        }

        try {
            RecurringTask task = recurringTaskService.findTask(name).orElse(null);
            if (task == null) {
                unschedule(name);
                return new RecurringTaskRunOutcome(RunStatus.ERROR, null, null, "Tache introuvable (supprimee)");
            }
            if (triggeredBySchedule && task.getStatus() != RecurringTaskStatus.ACTIVE) {
                // Defensif : un declenchement en vol peut avoir ete lance juste avant un
                // /tache disable qui n'a pas encore eu le temps d'annuler ce future precis.
                unschedule(name);
                return new RecurringTaskRunOutcome(RunStatus.UNKNOWN, null, null, "Tache desactivee : ignoree");
            }

            Instant now = Instant.now();
            RecurringTaskRunOutcome outcome;
            try {
                Project project = projectService.getProject(task.getProjectName());
                if (task.getExecutionMode() == RecurringTaskExecutionMode.AGENT_MISSION) {
                    // Pas de code de sortie pour une mission agent (voir AgentMissionExecutionService) :
                    // ClaudeCliService.call() leve deja une exception (attrapee ci-dessous, meme
                    // chemin que ScriptExecutionException) en cas d'echec - un retour normal ici
                    // est donc toujours un succes.
                    ClaudeCliResult result = project.isElevated()
                            ? agentMissionExecutionService.run(task.getMissionPrompt(),
                            Path.of(project.getWorkingDirectory()), claudeCliProperties.getElevatedSettingsPath())
                            : agentMissionExecutionService.run(task.getMissionPrompt(),
                            Path.of(project.getWorkingDirectory()));
                    String summary = truncate(result.getResult());
                    recurringTaskService.recordAgentRunResult(name, now, summary);
                    outcome = new RecurringTaskRunOutcome(RunStatus.OK, null, summary, null);
                } else {
                    ScriptExecutionResult result = scriptExecutionService.run(
                            task.getCommand(), Path.of(project.getWorkingDirectory()));
                    RunStatus status = RunStatus.fromExitCode(result.exitCode());
                    String summary = truncate(pickSummary(result));
                    recurringTaskService.recordRunResult(name, now, result.exitCode(), status, summary);
                    outcome = new RecurringTaskRunOutcome(status, result.exitCode(), summary, null);
                }
            } catch (Exception e) {
                log.error("Echec d'execution de la tache recurrente '{}'", name, e);
                recurringTaskService.recordRunError(name, now, e.getMessage());
                outcome = new RecurringTaskRunOutcome(RunStatus.ERROR, null, null, e.getMessage());
            }

            if (triggeredBySchedule) {
                // Garde-fou (bug trouve en prod le 29/08/2026, voir RecurringTaskNotifier) :
                // un echec d'envoi de la notification (Telegram down, config manquante,
                // etc.) ne doit jamais faire perdre le resultat reel de l'execution du
                // script ni remonter comme une erreur "du scheduler" dans les logs
                // (TaskUtils$LoggingErrorHandler) - le run lui-meme a deja reussi/echoue
                // et a deja ete persiste par recordRunResult/recordRunError ci-dessus.
                try {
                    notifier.notify(task, outcome);
                } catch (Exception e) {
                    log.error("Echec d'envoi de la notification Telegram pour la tache recurrente '{}' "
                            + "(execution du script non affectee, statut={})", name, outcome.status(), e);
                }

                // Une tache ponctuelle (ajoute le 29/08/2026) ne se redeclenche jamais toute
                // seule (planifiee via un Instant unique, pas un CronTrigger) - on la repasse
                // a DISABLED pour que /tache list/show reflete clairement qu'elle est
                // terminee, plutot que de la laisser ACTIVE indefiniment alors qu'elle
                // n'attend plus rien.
                if (task.getTriggerType() == RecurringTaskTriggerType.ONE_TIME) {
                    try {
                        unschedule(name);
                        recurringTaskService.setEnabled(name, false);
                        log.info("Tache ponctuelle '{}' executee (statut={}) : desactivee automatiquement",
                                name, outcome.status());
                    } catch (Exception e) {
                        log.error("Echec de la desactivation automatique de la tache ponctuelle '{}' apres execution",
                                name, e);
                    }
                }
            }
            return outcome;
        } finally {
            runningTasks.remove(name);
        }
    }

    private static String pickSummary(ScriptExecutionResult result) {
        if (result.stdout() != null && !result.stdout().isBlank()) {
            return result.stdout().strip();
        }
        return result.stderr() != null ? result.stderr().strip() : "";
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > OUTPUT_SUMMARY_MAX_LENGTH
                ? text.substring(0, OUTPUT_SUMMARY_MAX_LENGTH) + "... (tronque)"
                : text;
    }
}
