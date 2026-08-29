package fr.ses10doigts.agentvps.service;

/**
 * Echec d'EXECUTION d'un script de tache recurrente (process introuvable/non executable,
 * timeout depasse) - distinct d'un code de sortie non nul du script lui-meme, qui est un
 * resultat normal (WARNING/CRITICAL, voir RunStatus) et non une exception.
 */
public class ScriptExecutionException extends RuntimeException {

    public ScriptExecutionException(String message) {
        super(message);
    }

    public ScriptExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
