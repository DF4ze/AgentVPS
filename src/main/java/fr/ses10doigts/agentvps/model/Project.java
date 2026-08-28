package fr.ses10doigts.agentvps.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Un projet AgentVPS : un dossier de travail dedie (CLAUDE.md + fichiers, voir
 * roadmap Phase 3 point 6) et l'historique complet de ses conversations Claude.
 * Le "nom" sert de cle stable (slug, voir ProjectService.slugify) et de nom du
 * sous-dossier sous WorkspaceProperties.projectsDir().
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Project {

    private String name;

    private ProjectStatus status = ProjectStatus.ACTIVE;

    private Instant createdAt;

    /** Chemin absolu du dossier de travail (cwd du ProcessBuilder pour ce projet). */
    private String workingDirectory;

    /** Historique complet des conversations, ordre chronologique (la plus ancienne en premier). */
    private List<Conversation> conversations = new ArrayList<>();

    /**
     * session_id de la conversation courante (celle utilisee avec --resume au prochain
     * appel), ou null si une nouvelle conversation a ete demandee (/projet ... new
     * conversation) mais qu'aucun appel claude n'a encore renvoye de session_id pour
     * la remplacer (voir ProjectService.startNewConversation / recordConversationStart).
     */
    private String currentSessionId;
}
