package fr.ses10doigts.agentvps.service;

/**
 * Erreur de gestion de projet : nom invalide, projet ou conversation introuvable,
 * projet deja existant, echec de creation du dossier de travail, ou echec de
 * lecture/ecriture du fichier de persistance (voir JsonProjectStoreRepository).
 */
public class ProjectException extends RuntimeException {

    public ProjectException(String message) {
        super(message);
    }

    public ProjectException(String message, Throwable cause) {
        super(message, cause);
    }
}
