package fr.ses10doigts.agentvps.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Racine persistee du fichier JSON des taches recurrentes (voir
 * WorkspaceProperties.recurringTasksStoreFile() et JsonRecurringTaskStoreRepository).
 * Meme pattern que ProjectStore (fichier JSON unique, pas de base de donnees - decision
 * Clem du 28/08/2026 reconduite ici faute de volume/concurrence justifiant autre chose).
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecurringTaskStore {

    /** Cle = RecurringTask.name (slug). LinkedHashMap pour conserver l'ordre de creation dans le JSON. */
    private Map<String, RecurringTask> tasks = new LinkedHashMap<>();
}
