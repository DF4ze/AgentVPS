package fr.ses10doigts.agentvps.service;

/**
 * Erreur de gestion des taches recurrentes : nom invalide, tache ou projet associe
 * introuvable, tache deja existante, expression cron invalide, ou echec de
 * lecture/ecriture du fichier de persistance (voir JsonRecurringTaskStoreRepository).
 */
public class RecurringTaskException extends RuntimeException {

    public RecurringTaskException(String message) {
        super(message);
    }

    public RecurringTaskException(String message, Throwable cause) {
        super(message, cause);
    }
}
