package fr.ses10doigts.agentvps.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.agentvps.config.WorkspaceProperties;
import fr.ses10doigts.agentvps.model.ProjectStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Persistance du ProjectStore dans un unique fichier JSON (WorkspaceProperties.storeFile()),
 * decision retenue avec Clem le 28/08/2026 plutot qu'une base de donnees (SQLite ou autre) :
 * le volume et la concurrence d'ecriture restent ceux d'un usage mono-utilisateur, mono-process
 * (meme JVM pour le bot Telegram et, plus tard, l'interface web de la Phase 6).
 *
 * Ecriture atomique (fichier temporaire + Files.move ATOMIC_MOVE) pour eviter un fichier
 * corrompu si le process est tue en plein ecriture (le service tournera sous systemd avec
 * Restart=on-failure en production, voir roadmap Phase 4).
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class JsonProjectStoreRepository implements ProjectStoreRepository {

    private final ObjectMapper objectMapper;
    private final WorkspaceProperties workspaceProperties;

    @Override
    public ProjectStore load() {
        Path file = workspaceProperties.storeFile();
        if (!Files.exists(file)) {
            log.info("Aucun fichier de projets existant ({}), demarrage avec un store vide", file);
            return new ProjectStore();
        }
        try {
            return objectMapper.readValue(file.toFile(), ProjectStore.class);
        } catch (IOException e) {
            throw new ProjectException("Impossible de lire le fichier de projets : " + file, e);
        }
    }

    @Override
    public void save(ProjectStore store) {
        Path file = workspaceProperties.storeFile();
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
            throw new ProjectException("Impossible de serialiser le store de projets", e);
        } catch (IOException e) {
            throw new ProjectException("Impossible d'ecrire le fichier de projets : " + file, e);
        }
    }
}
