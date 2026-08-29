package fr.ses10doigts.agentvps.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.agentvps.config.JacksonConfig;
import fr.ses10doigts.agentvps.config.WorkspaceProperties;
import fr.ses10doigts.agentvps.model.Conversation;
import fr.ses10doigts.agentvps.model.Project;
import fr.ses10doigts.agentvps.model.ProjectStatus;
import fr.ses10doigts.agentvps.model.ProjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JsonProjectStoreRepositoryTest {

    private final ObjectMapper objectMapper = new JacksonConfig().objectMapper();
    private WorkspaceProperties workspaceProperties;
    private JsonProjectStoreRepository repository;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        workspaceProperties = new WorkspaceProperties();
        workspaceProperties.setRootDir(tempDir.toString());
        repository = new JsonProjectStoreRepository(objectMapper, workspaceProperties);
    }

    @Test
    void loadReturnsEmptyStoreWhenFileIsMissing() {
        ProjectStore store = repository.load();

        assertThat(store.getActiveProjectName()).isNull();
        assertThat(store.getProjects()).isEmpty();
    }

    @Test
    void roundTripsProjectsAndConversationsThroughDisk() {
        ProjectStore store = new ProjectStore();
        store.setActiveProjectName("mon-projet");

        Project project = new Project();
        project.setName("mon-projet");
        project.setStatus(ProjectStatus.ACTIVE);
        project.setCreatedAt(Instant.parse("2026-08-28T10:00:00Z"));
        project.setWorkingDirectory(workspaceProperties.projectsDir().resolve("mon-projet").toString());
        project.setCurrentSessionId("session-2");
        project.getConversations().add(new Conversation(
                "session-1", Instant.parse("2026-08-28T10:00:00Z"), Instant.parse("2026-08-28T10:05:00Z"), "Mise en place initiale", 0));
        project.getConversations().add(new Conversation(
                "session-2", Instant.parse("2026-08-28T11:00:00Z"), Instant.parse("2026-08-28T11:00:00Z"), null, 0));
        store.getProjects().put("mon-projet", project);

        repository.save(store);
        ProjectStore reloaded = repository.load();

        assertThat(reloaded.getActiveProjectName()).isEqualTo("mon-projet");
        Project reloadedProject = reloaded.getProjects().get("mon-projet");
        assertThat(reloadedProject).isNotNull();
        assertThat(reloadedProject.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(reloadedProject.getCurrentSessionId()).isEqualTo("session-2");
        assertThat(reloadedProject.getConversations()).hasSize(2);
        assertThat(reloadedProject.getConversations().get(0).getSessionId()).isEqualTo("session-1");
        assertThat(reloadedProject.getConversations().get(0).getLabel()).isEqualTo("Mise en place initiale");
        assertThat(reloadedProject.getConversations().get(1).getLabel()).isNull();
    }

    @Test
    void savingTwiceDoesNotLeaveTemporaryFilesBehind() throws Exception {
        repository.save(new ProjectStore());
        repository.save(new ProjectStore());

        java.util.List<Path> filesInRootDir;
        try (java.util.stream.Stream<Path> paths = java.nio.file.Files.list(workspaceProperties.rootDirPath())) {
            filesInRootDir = paths.toList();
        }

        assertThat(filesInRootDir).extracting(p -> p.getFileName().toString())
                .noneMatch(name -> name.contains(".tmp"));
        assertThat(filesInRootDir).extracting(p -> p.getFileName().toString())
                .contains("projects-store.json");
    }
}
