package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.model.ProjectStore;

/**
 * Charge/persiste le ProjectStore. Interface separee de l'implementation JSON
 * (JsonProjectStoreRepository) pour permettre de changer de mecanisme de
 * persistance plus tard (ex. SQLite) sans toucher a ProjectService - voir la
 * discussion avec Clem du 28/08/2026 : un simple fichier JSON suffit tant que le
 * volume/la concurrence restent ceux d'un usage mono-utilisateur.
 */
public interface ProjectStoreRepository {

    /** Charge le store depuis le disque, ou renvoie un store vide si le fichier n'existe pas encore. */
    ProjectStore load();

    /** Persiste l'integralite du store sur disque, de maniere atomique. */
    void save(ProjectStore store);
}
