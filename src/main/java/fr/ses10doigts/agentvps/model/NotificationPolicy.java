package fr.ses10doigts.agentvps.model;

/**
 * Seuil de notification Telegram automatique apres l'execution d'une tache recurrente
 * (decision Clem du 29/08/2026 : configurable par tache plutot qu'un reglage global -
 * voir memoire projet "phase7_maintenance_project" / roadmap Phase 7).
 */
public enum NotificationPolicy {

    /** Un message est envoye a chaque execution, meme si le resultat est OK. */
    ALWAYS,

    /** Un message n'est envoye que si le resultat n'est pas OK (WARNING/CRITICAL/UNKNOWN/ERROR). */
    ON_ISSUE,

    /** Aucune notification automatique ; le dernier resultat reste consultable via /tache show. */
    NEVER
}
