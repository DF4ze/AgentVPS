package fr.ses10doigts.agentvps.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Racine du workspace partage AgentVPS (voir roadmap-implementation.md, Phase 3,
 * points 5/6) : un sous-dossier par projet (contenant son CLAUDE.md) sous
 * projectsDir(), et un fichier JSON de persistance de la table
 * projet -> conversations/session_id sous storeFile().
 *
 * Liee en String (pas Path) pour eviter toute surprise de conversion Spring,
 * comme pour les autres @ConfigurationProperties de ce projet (voir
 * ClaudeCliProperties) ; la conversion en Path se fait explicitement ici.
 */
@Data
@ConfigurationProperties(prefix = "agentvps.workspace")
public class WorkspaceProperties {

    /** Racine du workspace (un sous-dossier "projects" + le fichier d'etat projets/sessions). */
    private String rootDir = System.getProperty("user.home") + "/AgentVPS";

    public Path rootDirPath() {
        return Path.of(rootDir);
    }

    /** Dossier contenant un sous-dossier par projet (CLAUDE.md, fichiers de travail, cwd du ProcessBuilder). */
    public Path projectsDir() {
        return rootDirPath().resolve("projects");
    }

    /** Fichier JSON de persistance de la table projet -> conversations/session_id (voir ProjectStore). */
    public Path storeFile() {
        return rootDirPath().resolve("projects-store.json");
    }

    /**
     * Fichier JSON de persistance des taches recurrentes (roadmap Phase 7, voir
     * RecurringTaskStore) - fichier separe de storeFile() : cycle de vie et frequence
     * d'ecriture differents (un run planifie peut ecrire bien plus souvent qu'une
     * mutation de projet/conversation).
     */
    public Path recurringTasksStoreFile() {
        return rootDirPath().resolve("recurring-tasks-store.json");
    }

    /**
     * Dossier des logs de conversation captures en JSONL (flux complet claude, raisonnement
     * inclus quand le provider le permet - voir ClaudeCliProperties.captureConversationLogs
     * et la memoire projet "continuous_improvement_capture") : un fichier par projet sous
     * conversationLogsDir()/<projet>/chat.jsonl, alimente en continu (append-only).
     */
    public Path conversationLogsDir() {
        return rootDirPath().resolve("conversation-logs");
    }
}
