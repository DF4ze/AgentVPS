package fr.ses10doigts.agentvps.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.agentvps.config.JacksonConfig;
import fr.ses10doigts.agentvps.config.WorkspaceProperties;
import fr.ses10doigts.agentvps.model.NotificationPolicy;
import fr.ses10doigts.agentvps.model.RecurringTask;
import fr.ses10doigts.agentvps.model.RecurringTaskStatus;
import fr.ses10doigts.agentvps.model.RecurringTaskStore;
import fr.ses10doigts.agentvps.model.RunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JsonRecurringTaskStoreRepositoryTest {

    private final ObjectMapper objectMapper = new JacksonConfig().objectMapper();
    private WorkspaceProperties workspaceProperties;
    private JsonRecurringTaskStoreRepository repository;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        workspaceProperties = new WorkspaceProperties();
        workspaceProperties.setRootDir(tempDir.toString());
        repository = new JsonRecurringTaskStoreRepository(objectMapper, workspaceProperties);
    }

    @Test
    void loadReturnsEmptyStoreWhenFileIsMissing() {
        RecurringTaskStore store = repository.load();

        assertThat(store.getTasks()).isEmpty();
    }

    @Test
    void roundTripsTaskAndLastRunFieldsThroughDisk() {
        RecurringTaskStore store = new RecurringTaskStore();

        RecurringTask task = new RecurringTask();
        task.setName("healthcheck");
        task.setDescription("Health check quotidien du VPS");
        task.setProjectName("maintenance");
        task.setCommand("./health_check.sh");
        task.setCronExpression("0 0 6 * * *");
        task.setStatus(RecurringTaskStatus.ACTIVE);
        task.setNotificationPolicy(NotificationPolicy.ON_ISSUE);
        task.setCreatedAt(Instant.parse("2026-08-29T00:00:00Z"));
        task.setLastRunAt(Instant.parse("2026-08-29T06:00:00Z"));
        task.setLastExitCode(1);
        task.setLastRunStatus(RunStatus.WARNING);
        task.setLastOutputSummary("systemd degraded");
        store.getTasks().put("healthcheck", task);

        repository.save(store);
        RecurringTaskStore reloaded = repository.load();

        RecurringTask reloadedTask = reloaded.getTasks().get("healthcheck");
        assertThat(reloadedTask).isNotNull();
        assertThat(reloadedTask.getProjectName()).isEqualTo("maintenance");
        assertThat(reloadedTask.getCronExpression()).isEqualTo("0 0 6 * * *");
        assertThat(reloadedTask.getNotificationPolicy()).isEqualTo(NotificationPolicy.ON_ISSUE);
        assertThat(reloadedTask.getLastRunStatus()).isEqualTo(RunStatus.WARNING);
        assertThat(reloadedTask.getLastExitCode()).isEqualTo(1);
        assertThat(reloadedTask.getLastOutputSummary()).isEqualTo("systemd degraded");
    }

    @Test
    void savingTwiceDoesNotLeaveTemporaryFilesBehind() throws Exception {
        repository.save(new RecurringTaskStore());
        repository.save(new RecurringTaskStore());

        java.util.List<Path> filesInRootDir;
        try (java.util.stream.Stream<Path> paths = java.nio.file.Files.list(workspaceProperties.rootDirPath())) {
            filesInRootDir = paths.toList();
        }

        assertThat(filesInRootDir).extracting(p -> p.getFileName().toString())
                .noneMatch(name -> name.contains(".tmp"));
        assertThat(filesInRootDir).extracting(p -> p.getFileName().toString())
                .contains("recurring-tasks-store.json");
    }
}
