package fr.ses10doigts.agentvps.model;

/**
 * Mecanisme de declenchement d'une tache recurrente (roadmap Phase 7). Ajoute le
 * 29/08/2026 (demande Clem) pour couvrir aussi les taches "ponctuelles" : jusque-la,
 * RecurringTask etait toujours pilotee par une expression cron (repetition indefinie).
 *
 * CRON : cronExpression pilote un CronTrigger Spring classique, se repete indefiniment
 * tant que la tache est ACTIVE (voir RecurringTaskScheduler.schedule).
 *
 * ONE_TIME : scheduledAt pilote un declenchement unique a un instant precis (planifie via
 * TaskScheduler.schedule(Runnable, Instant), pas de CronTrigger). Une fois executee, la
 * tache est automatiquement repassee a DISABLED par RecurringTaskScheduler.executeTask -
 * elle ne peut pas se redeclencher, cronExpression reste null pour ce type.
 */
public enum RecurringTaskTriggerType {
    CRON,
    ONE_TIME
}
