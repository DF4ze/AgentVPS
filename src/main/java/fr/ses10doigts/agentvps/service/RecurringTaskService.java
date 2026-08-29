package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.model.NotificationPolicy;
import fr.ses10doigts.agentvps.model.RecurringTask;
import fr.ses10doigts.agentvps.model.RecurringTaskStatus;
import fr.ses10doigts.agentvps.model.RecurringTaskStore;
import fr.ses10doigts.agentvps.model.RecurringTaskTriggerType;
import fr.ses10doigts.agentvps.model.RunStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Gestion des taches recurrentes (roadmap Phase 7, "couche projet / tache recurrente") :
 * CRUD et persistance pure, sur le meme modele que ProjectService (store JSON charge une
 * fois puis tenu a jour en memoire, chaque mutation immediatement persistee sous le meme
 * verrou). Ce service ne connait ni le TaskScheduler Spring ni Telegram - voir
 * RecurringTaskManager pour l'orchestration (creation + (re)planification live) et
 * RecurringTaskScheduler pour l'execution effective des scripts.
 *
 * Validation a la creation : nom unique (slug), projet associe existant (ProjectService),
 * expression cron syntaxiquement valide (org.springframework.scheduling.support.CronExpression) -
 * rejeter tot une tache non planifiable plutot que de le decouvrir au premier declenchement.
 */
@Service
@Slf4j
public class RecurringTaskService {

    private final RecurringTaskStoreRepository repository;
    private final ProjectService projectService;
    private final Object lock = new Object();
    private RecurringTaskStore store;

    public RecurringTaskService(RecurringTaskStoreRepository repository, ProjectService projectService) {
        this.repository = repository;
        this.projectService = projectService;
    }

    public List<RecurringTask> listTasks() {
        synchronized (lock) {
            return List.copyOf(store().getTasks().values());
        }
    }

    public Optional<RecurringTask> findTask(String name) {
        synchronized (lock) {
            return Optional.ofNullable(store().getTasks().get(slugify(name)));
        }
    }

    public RecurringTask getTask(String name) {
        return findTask(name)
                .orElseThrow(() -> new RecurringTaskException("Aucune tache recurrente nommee '" + name + "'"));
    }

    /**
     * Cree une nouvelle tache recurrente (declenchement CRON), active par defaut. Le
     * projet associe doit deja exister (voir ProjectService.getProject) : une tache
     * recurrente n'a pas de sens sans repertoire de travail. N'effectue aucune
     * planification live - voir RecurringTaskManager.
     */
    public RecurringTask createTask(String rawName, String projectName, String command,
                                     String cronExpression, NotificationPolicy notificationPolicy,
                                     String description) {
        String validatedCron = validateCron(cronExpression);
        return createTaskInternal(rawName, projectName, command, RecurringTaskTriggerType.CRON,
                validatedCron, null, notificationPolicy, description);
    }

    /**
     * Cree une nouvelle tache ponctuelle (declenchement ONE_TIME, ajoute le 29/08/2026 -
     * demande Clem), active par defaut : s'execute une seule fois a scheduledAt, puis
     * repasse automatiquement a DISABLED (voir RecurringTaskScheduler.executeTask). Meme
     * validations que createTask (nom unique, commande non vide, projet existant), plus
     * scheduledAt obligatoire et strictement dans le futur - une tache ponctuelle deja
     * passee au moment de sa creation n'aurait aucun sens.
     */
    public RecurringTask createOneTimeTask(String rawName, String projectName, String command,
                                            Instant scheduledAt, NotificationPolicy notificationPolicy,
                                            String description) {
        if (scheduledAt == null) {
            throw new RecurringTaskException("La date d'execution ne peut pas etre vide");
        }
        if (!scheduledAt.isAfter(Instant.now())) {
            throw new RecurringTaskException("La date d'execution doit etre dans le futur");
        }
        return createTaskInternal(rawName, projectName, command, RecurringTaskTriggerType.ONE_TIME,
                null, scheduledAt, notificationPolicy, description);
    }

    private RecurringTask createTaskInternal(String rawName, String projectName, String command,
                                              RecurringTaskTriggerType triggerType, String cronExpression,
                                              Instant scheduledAt, NotificationPolicy notificationPolicy,
                                              String description) {
        synchronized (lock) {
            String slug = slugify(rawName);
            if (store().getTasks().containsKey(slug)) {
                throw new RecurringTaskException("Une tache recurrente nommee '" + slug + "' existe deja");
            }
            if (command == null || command.isBlank()) {
                throw new RecurringTaskException("La commande a executer ne peut pas etre vide");
            }

            try {
                projectService.getProject(projectName);
            } catch (ProjectException e) {
                throw new RecurringTaskException(
                        "Projet '" + projectName + "' introuvable (cree-le d'abord avec /projet new)", e);
            }

            RecurringTask task = new RecurringTask();
            task.setName(slug);
            task.setDescription(description);
            task.setProjectName(projectName);
            task.setCommand(command.trim());
            task.setTriggerType(triggerType);
            task.setCronExpression(cronExpression);
            task.setScheduledAt(scheduledAt);
            task.setStatus(RecurringTaskStatus.ACTIVE);
            task.setNotificationPolicy(notificationPolicy != null ? notificationPolicy : NotificationPolicy.ON_ISSUE);
            task.setCreatedAt(Instant.now());

            store().getTasks().put(slug, task);
            persist();
            log.info("Tache recurrente '{}' creee (projet={}, {})", slug, projectName,
                    triggerType == RecurringTaskTriggerType.ONE_TIME
                            ? "une fois le " + scheduledAt
                            : "cron=" + cronExpression);
            return task;
        }
    }

    public RecurringTask setEnabled(String name, boolean enabled) {
        synchronized (lock) {
            RecurringTask task = getTask(name);
            task.setStatus(enabled ? RecurringTaskStatus.ACTIVE : RecurringTaskStatus.DISABLED);
            persist();
            log.info("Tache recurrente '{}' {}", task.getName(), enabled ? "activee" : "desactivee");
            return task;
        }
    }

    public void deleteTask(String name) {
        synchronized (lock) {
            RecurringTask task = getTask(name);
            store().getTasks().remove(task.getName());
            persist();
            log.info("Tache recurrente '{}' supprimee", task.getName());
        }
    }

    /** Enregistre le resultat d'une execution reussie (code de sortie recu du script). */
    public void recordRunResult(String name, Instant runAt, int exitCode, RunStatus status, String outputSummary) {
        synchronized (lock) {
            findTask(name).ifPresent(task -> {
                task.setLastRunAt(runAt);
                task.setLastExitCode(exitCode);
                task.setLastRunStatus(status);
                task.setLastOutputSummary(outputSummary);
                task.setLastErrorMessage(null);
                persist();
            });
        }
    }

    /** Enregistre un echec d'execution (process introuvable, timeout...) - pas de code de sortie du script. */
    public void recordRunError(String name, Instant runAt, String errorMessage) {
        synchronized (lock) {
            findTask(name).ifPresent(task -> {
                task.setLastRunAt(runAt);
                task.setLastExitCode(null);
                task.setLastRunStatus(RunStatus.ERROR);
                task.setLastOutputSummary(null);
                task.setLastErrorMessage(errorMessage);
                persist();
            });
        }
    }

    private RecurringTaskStore store() {
        if (store == null) {
            store = repository.load();
        }
        return store;
    }

    private void persist() {
        repository.save(store);
    }

    /**
     * Valide une expression cron Spring (6 champs, ou macro type "@daily"/"@hourly" -
     * voir org.springframework.scheduling.support.CronExpression) et la renvoie
     * normalisee (trim). Rejette toute expression vide ou syntaxiquement invalide plutot
     * que de le decouvrir au premier declenchement planifie.
     */
    public static String validateCron(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            throw new RecurringTaskException("L'expression cron ne peut pas etre vide");
        }
        String trimmed = cronExpression.trim();
        try {
            CronExpression.parse(trimmed);
        } catch (IllegalArgumentException e) {
            throw new RecurringTaskException(
                    "Expression cron invalide : '" + trimmed + "' (" + e.getMessage() + ")", e);
        }
        return trimmed;
    }

    /** Meme convention que ProjectService.slugify (nom libre -> slug stable, cle du store). */
    static String slugify(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new RecurringTaskException("Le nom de la tache ne peut pas etre vide");
        }
        String withoutAccents = Normalizer.normalize(rawName.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String slug = withoutAccents.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            throw new RecurringTaskException("Nom de tache invalide : '" + rawName + "'");
        }
        return slug;
    }
}
