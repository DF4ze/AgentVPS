package fr.ses10doigts.agentvps.model;

/**
 * Statut d'un projet. ARCHIVED = "supprime" au sens de /projet delete (voir roadmap
 * Phase 3, point 4) : le projet et son historique de conversations restent intacts sur
 * disque, mais le projet n'est plus selectionnable comme projet actif sans etre d'abord
 * reactive (voir ProjectService.switchProject, qui reactive automatiquement).
 */
public enum ProjectStatus {
    ACTIVE,
    ARCHIVED
}
