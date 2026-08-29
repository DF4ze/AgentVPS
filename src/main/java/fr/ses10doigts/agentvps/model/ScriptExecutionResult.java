package fr.ses10doigts.agentvps.model;

/**
 * Resultat brut de l'execution d'un script par ScriptExecutionService : jamais persiste
 * tel quel (voir RecurringTaskScheduler, qui en derive un RunStatus et un resume tronque
 * stockes sur RecurringTask) - pas d'annotations Jackson, contrairement a ClaudeCliResult.
 */
public record ScriptExecutionResult(int exitCode, String stdout, String stderr) {
}
