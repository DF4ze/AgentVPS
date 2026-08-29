package fr.ses10doigts.agentvps.model;

/**
 * Statut d'une tache recurrente (roadmap Phase 7 - "couche projet / tache recurrente").
 * DISABLED n'est pas une suppression : la definition (cron, script, projet, notification)
 * reste intacte, seule la planification live est arretee (voir RecurringTaskScheduler).
 * Une tache DISABLED peut etre reactivee (ACTIVE) sans perdre son historique de dernier run.
 */
public enum RecurringTaskStatus {
    ACTIVE,
    DISABLED
}
