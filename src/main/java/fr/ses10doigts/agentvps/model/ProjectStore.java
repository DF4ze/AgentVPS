package fr.ses10doigts.agentvps.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Racine persistee du fichier JSON de la table projet -> conversations/session_id
 * (voir WorkspaceProperties.storeFile() et JsonProjectStoreRepository).
 *
 * Mono-utilisateur pour l'instant (decision Clem du 28/08/2026) : un seul "projet
 * actif" global, pas de notion d'utilisateur Telegram dans cette structure.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectStore {

    /** Nom (slug) du projet actif, ou null si aucun (ex. apres archivage du dernier projet actif). */
    private String activeProjectName;

    /** Cle = Project.name (slug). LinkedHashMap pour conserver l'ordre de creation dans le JSON. */
    private Map<String, Project> projects = new LinkedHashMap<>();
}
