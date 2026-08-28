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
}
