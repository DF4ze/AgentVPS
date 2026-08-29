package fr.ses10doigts.agentvps.model;

/**
 * Resultat d'une execution de tache recurrente, tel que produit par RecurringTaskScheduler
 * et consomme par RecurringTaskNotifier (decision d'envoi selon NotificationPolicy) et par
 * le controller Telegram (reponse immediate a /tache run). exitCode est null quand status
 * vaut ERROR (echec d'execution, pas un code de sortie du script - voir ScriptExecutionException).
 */
public record RecurringTaskRunOutcome(RunStatus status, Integer exitCode, String outputSummary, String errorMessage) {
}
