package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.model.NotificationPolicy;
import fr.ses10doigts.agentvps.model.RecurringTask;
import fr.ses10doigts.agentvps.model.RecurringTaskRunOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Orchestration des taches recurrentes (roadmap Phase 7) : compose RecurringTaskService
 * (persistance pure) et RecurringTaskScheduler (planification live) pour que chaque
 * mutation reste toujours coherente entre le fichier JSON et le TaskScheduler Spring en
 * memoire - meme role que ProjectOnboardingService/ChatService vis-a-vis de ProjectService
 * (garder les services de persistance independants de leurs collaborateurs).
 *
 * Point d'entree utilise par le controller Telegram (/tache ...) - voir
 * AgentVpsTelegramController.
 */
@Service
@RequiredArgsConstructor
public class RecurringTaskManager {

    private final RecurringTaskService recurringTaskService;
    private final RecurringTaskScheduler scheduler;

    public RecurringTask createTask(String name, String projectName, String command, String cronExpression,
                                     NotificationPolicy notificationPolicy, String description) {
        RecurringTask task = recurringTaskService.createTask(
                name, projectName, command, cronExpression, notificationPolicy, description);
        scheduler.schedule(task);
        return task;
    }

    public RecurringTask enable(String name) {
        RecurringTask task = recurringTaskService.setEnabled(name, true);
        scheduler.schedule(task);
        return task;
    }

    public RecurringTask disable(String name) {
        RecurringTask task = recurringTaskService.setEnabled(name, false);
        scheduler.unschedule(name);
        return task;
    }

    public void deleteTask(String name) {
        // Deplanifier AVANT de supprimer la persistance : si un declenchement etait deja
        // en vol pendant l'appel, il retrouvera de toute facon la tache absente du store
        // (voir RecurringTaskScheduler.executeTask, cas task == null) et s'auto-deplanifiera.
        scheduler.unschedule(name);
        recurringTaskService.deleteTask(name);
    }

    /** Execution manuelle (/tache run <nom>) : leve RecurringTaskException si le nom est inconnu (message clair avant meme de tenter l'execution). */
    public RecurringTaskRunOutcome runNow(String name) {
        recurringTaskService.getTask(name);
        return scheduler.runNow(name);
    }
}
