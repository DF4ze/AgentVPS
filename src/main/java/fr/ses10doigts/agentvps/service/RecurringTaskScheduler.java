package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.model.Project;
import fr.ses10doigts.agentvps.model.RecurringTask;
import fr.ses10doigts.agentvps.model.RecurringTaskRunOutcome;
import fr.ses10doigts.agentvps.model.RecurringTaskStatus;
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

    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();
    private final Set<String> runningTasks = ConcurrentHashMap.newKeySet();

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        recurringTaskService.listTasks().stream()
                .filter(task -> task.getStatus() == RecurringTaskStatus.ACTIVE)
                .forEach(this::schedule);
        log.info("Taches recurrentes planifiees au demarrage : {}", scheduledFutures.keySet());
    }

    /** (Re)planifie une tache : annule d'abord toute planification existante du meme nom (voir unschedule). */
    public void schedule(RecurringTask task) {
        unschedule(task.getName());
        CronTrigger trigger;
        try {
            trigger = new CronTrigger(task.getCronExpression());
        } catch (IllegalArgumentException e) {
            // Ne devrait pas arriver (cron deja valide a la creation, voir RecurringTaskService.validateCron)
            // mais on ne veut pas faire echouer tout le demarrage de l'application pour une tache.
            log.error("Expression cron invalide pour la tache '{}', non planifiee : {}", task.getName(), e.getMessage());
            return;
        }
        String name = task.getName();
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
                ScriptExecutionResult result = scriptExecutionService.run(
                        task.getCommand(), Path.of(project.getWorkingDirectory()));
                RunStatus status = RunStatus.fromExitCode(result.exitCode());
                String summary = truncate(pickSummary(result));
                recurringTaskService.recordRunResult(name, now, result.exitCode(), status, summary);
                outcome = new RecurringTaskRunOutcome(status, result.exitCode(), summary, null);
            } catch (Exception e) {
                log.error("Echec d'execution de la tache recurrente '{}'", name, e);
                recurringTaskService.recordRunError(name, now, e.getMessage());
                outcome = new RecurringTaskRunOutcome(RunStatus.ERROR, null, null, e.getMessage());
            }

            if (triggeredBySchedule) {
                notifier.notify(task, outcome);
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
