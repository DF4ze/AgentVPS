package fr.ses10doigts.agentvps.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.agentvps.config.WorkspaceProperties;
import fr.ses10doigts.agentvps.model.RecurringTaskStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Persistance du RecurringTaskStore dans un unique fichier JSON
 * (WorkspaceProperties.recurringTasksStoreFile()) - meme choix et meme mecanisme
 * d'ecriture atomique (fichier temporaire + Files.move ATOMIC_MOVE) que
 * JsonProjectStoreRepository, pour la meme raison (process tue en plein ecriture
 * sous systemd Restart=on-failure).
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class JsonRecurringTaskStoreRepository implements RecurringTaskStoreRepository {

    private final ObjectMapper objectMapper;
    private final WorkspaceProperties workspaceProperties;

    @Override
    public RecurringTaskStore load() {
        Path file = workspaceProperties.recurringTasksStoreFile();
        if (!Files.exists(file)) {
            log.info("Aucun fichier de taches recurrentes existant ({}), demarrage avec un store vide", file);
            return new RecurringTaskStore();
        }
        try {
            return objectMapper.readValue(file.toFile(), RecurringTaskStore.class);
        } catch (IOException e) {
            throw new RecurringTaskException("Impossible de lire le fichier de taches recurrentes : " + file, e);
        }
    }

    @Override
    public void save(RecurringTaskStore store) {
        Path file = workspaceProperties.recurringTasksStoreFile();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), store);
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (JsonProcessingException e) {
            throw new RecurringTaskException("Impossible de serialiser le store de taches recurrentes", e);
        } catch (IOException e) {
            throw new RecurringTaskException("Impossible d'ecrire le fichier de taches recurrentes : " + file, e);
        }
    }
}
