package fr.ses10doigts.agentvps.service;

/**
 * Erreur lors de l'invocation du CLI claude : echec de demarrage du process,
 * depassement du timeout, code de sortie non nul, sortie JSON invalide, ou
 * reponse marquee en erreur par Claude Code lui-meme.
 */
public class ClaudeCliException extends RuntimeException {

    public ClaudeCliException(String message) {
        super(message);
    }

    public ClaudeCliException(String message, Throwable cause) {
        super(message, cause);
    }
}
