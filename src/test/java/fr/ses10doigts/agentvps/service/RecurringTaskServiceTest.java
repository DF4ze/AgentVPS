package fr.ses10doigts.agentvps.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.agentvps.config.JacksonConfig;
import fr.ses10doigts.agentvps.config.WorkspaceProperties;
import fr.ses10doigts.agentvps.model.NotificationPolicy;
import fr.ses10doigts.agentvps.model.RecurringTask;
import fr.ses10doigts.agentvps.model.RecurringTaskExecutionMode;
import fr.ses10doigts.agentvps.model.RecurringTaskStatus;
import fr.ses10doigts.agentvps.model.RecurringTaskTriggerType;
import fr.ses10doigts.agentvps.model.RunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurringTaskServiceTest {

    private ProjectService projectService;
    private RecurringTaskService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        ObjectMapper objectMapper = new JacksonConfig().objectMapper();
        WorkspaceProperties workspaceProperties = new WorkspaceProperties();
        workspaceProperties.setRootDir(tempDir.toString());

        ProjectStoreRepository projectRepository = new JsonProjectStoreRepository(objectMapper, workspaceProperties);
        projectService = new ProjectService(projectRepository, workspaceProperties);
        projectService.createProject("maintenance");

        RecurringTaskStoreRepository taskRepository = new JsonRecurringTaskStoreRepository(objectMapper, workspaceProperties);
        service = new RecurringTaskService(taskRepository, projectService);
    }

    @Test
    void createTaskSucceedsForAnExistingProjectAndValidCron() {
        RecurringTask task = service.createTask(
                "healthcheck", "maintenance", "./health_check.sh", "0 0 6 * * *", NotificationPolicy.ON_ISSUE, "Health check quotidien");

        assertThat(task.getName()).isEqualTo("healthcheck");
        assertThat(task.getStatus()).isEqualTo(RecurringTaskStatus.ACTIVE);
        assertThat(task.getNotificationPolicy()).isEqualTo(NotificationPolicy.ON_ISSUE);
        assertThat(service.getTask("healthcheck")).isEqualTo(task);
    }

    @Test
    void createTaskAcceptsCronMacros() {
        RecurringTask task = service.createTask(
                "healthcheck", "maintenance", "./health_check.sh", "@daily", null, null);

        assertThat(task.getCronExpression()).isEqualTo("@daily");
        assertThat(task.getNotificationPolicy()).isEqualTo(NotificationPolicy.ON_ISSUE);
    }

    @Test
    void createTaskSlugifiesTheName() {
        RecurringTask task = service.createTask(
                "  Vérif Santé VPS !! ", "maintenance", "./health_check.sh", "@daily", null, null);

        assertThat(task.getName()).isEqualTo("verif-sante-vps");
    }

    @Test
    void createTaskRejectsUnknownProject() {
        assertThatThrownBy(() -> service.createTask(
                "healthcheck", "inconnu", "./health_check.sh", "@daily", null, null))
                .isInstanceOf(RecurringTaskException.class)
                .hasMessageContaining("inconnu");
    }

    @Test
    void createTaskRejectsInvalidCron() {
        assertThatThrownBy(() -> service.createTask(
                "healthcheck", "maintenance", "./health_check.sh", "pas un cron", null, null))
                .isInstanceOf(RecurringTaskException.class);
    }

    @Test
    void createTaskRejectsBlankCommand() {
        assertThatThrownBy(() -> service.createTask(
                "healthcheck", "maintenance", "   ", "@daily", null, null))
                .isInstanceOf(RecurringTaskException.class);
    }

    @Test
    void createTaskRejectsDuplicateName() {
        service.createTask("healthcheck", "maintenance", "./health_check.sh", "@daily", null, null);

        assertThatThrownBy(() -> service.createTask(
                "HealthCheck", "maintenance", "./health_check.sh", "@daily", null, null))
                .isInstanceOf(RecurringTaskException.class);
    }

    // -------------------------------------------------------- createOneTimeTask (mode ponctuel)

    @Test
    void createOneTimeTaskSucceedsForAFutureInstant() {
        Instant scheduledAt = Instant.now().plusSeconds(3600);

        RecurringTask task = service.createOneTimeTask(
                "rappel", "maintenance", "./notify.sh", scheduledAt, NotificationPolicy.ON_ISSUE, "Rappel ponctuel");

        assertThat(task.getName()).isEqualTo("rappel");
        assertThat(task.getTriggerType()).isEqualTo(RecurringTaskTriggerType.ONE_TIME);
        assertThat(task.getScheduledAt()).isEqualTo(scheduledAt);
        assertThat(task.getCronExpression()).isNull();
        assertThat(task.getStatus()).isEqualTo(RecurringTaskStatus.ACTIVE);
    }

    @Test
    void createOneTimeTaskRejectsANullScheduledAt() {
        assertThatThrownBy(() -> service.createOneTimeTask(
                "rappel", "maintenance", "./notify.sh", null, null, null))
                .isInstanceOf(RecurringTaskException.class);
    }

    @Test
    void createOneTimeTaskRejectsAPastScheduledAt() {
        assertThatThrownBy(() -> service.createOneTimeTask(
                "rappel", "maintenance", "./notify.sh", Instant.now().minusSeconds(60), null, null))
                .isInstanceOf(RecurringTaskException.class);
    }

    @Test
    void createOneTimeTaskRejectsUnknownProject() {
        assertThatThrownBy(() -> service.createOneTimeTask(
                "rappel", "inconnu", "./notify.sh", Instant.now().plusSeconds(60), null, null))
                .isInstanceOf(RecurringTaskException.class)
                .hasMessageContaining("inconnu");
    }

    @Test
    void createOneTimeTaskRejectsBlankCommand() {
        assertThatThrownBy(() -> service.createOneTimeTask(
                "rappel", "maintenance", "   ", Instant.now().plusSeconds(60), null, null))
                .isInstanceOf(RecurringTaskException.class);
    }

    @Test
    void createOneTimeTaskRejectsDuplicateName() {
        service.createOneTimeTask("rappel", "maintenance", "./notify.sh", Instant.now().plusSeconds(60), null, null);

        assertThatThrownBy(() -> service.createOneTimeTask(
                "Rappel", "maintenance", "./notify.sh", Instant.now().plusSeconds(120), null, null))
                .isInstanceOf(RecurringTaskException.class);
    }

    @Test
    void createTaskStillDefaultsToCronTriggerType() {
        RecurringTask task = service.createTask(
                "healthcheck", "maintenance", "./health_check.sh", "0 0 6 * * *", NotificationPolicy.ON_ISSUE, null);

        assertThat(task.getTriggerType()).isEqualTo(RecurringTaskTriggerType.CRON);
        assertThat(task.getScheduledAt()).isNull();
    }

    // -------------------------------------------------------- mode mission agent

    @Test
    void createAgentMissionTaskSucceedsAndLeavesCommandNull() {
        RecurringTask task = service.createAgentMissionTask(
                "crypto-analysis", "maintenance", "Analyse BTC tous les matins et resume la tendance",
                "0 0 8 * * *", NotificationPolicy.ALWAYS, "Analyse crypto quotidienne");

        assertThat(task.getExecutionMode()).isEqualTo(RecurringTaskExecutionMode.AGENT_MISSION);
        assertThat(task.getMissionPrompt()).isEqualTo("Analyse BTC tous les matins et resume la tendance");
        assertThat(task.getCommand()).isNull();
        assertThat(task.getCronExpression()).isEqualTo("0 0 8 * * *");
    }

    @Test
    void createAgentMissionTaskRejectsBlankMissionPrompt() {
        assertThatThrownBy(() -> service.createAgentMissionTask(
                "crypto-analysis", "maintenance", "   ", "@daily", null, null))
                .isInstanceOf(RecurringTaskException.class);
    }

    @Test
    void createAgentMissionTaskRejectsUnknownProject() {
        assertThatThrownBy(() -> service.createAgentMissionTask(
                "crypto-analysis", "inconnu", "Analyse BTC", "@daily", null, null))
                .isInstanceOf(RecurringTaskException.class)
                .hasMessageContaining("inconnu");
    }

    @Test
    void createOneTimeAgentMissionTaskSucceedsAndLeavesCommandNull() {
        Instant scheduledAt = Instant.now().plusSeconds(3600);

        RecurringTask task = service.createOneTimeAgentMissionTask(
                "rappel-analyse", "maintenance", "Analyse BTC une fois", scheduledAt, NotificationPolicy.ALWAYS, null);

        assertThat(task.getExecutionMode()).isEqualTo(RecurringTaskExecutionMode.AGENT_MISSION);
        assertThat(task.getTriggerType()).isEqualTo(RecurringTaskTriggerType.ONE_TIME);
        assertThat(task.getMissionPrompt()).isEqualTo("Analyse BTC une fois");
        assertThat(task.getCommand()).isNull();
        assertThat(task.getScheduledAt()).isEqualTo(scheduledAt);
    }

    @Test
    void createOneTimeAgentMissionTaskRejectsAPastScheduledAt() {
        assertThatThrownBy(() -> service.createOneTimeAgentMissionTask(
                "rappel-analyse", "maintenance", "Analyse BTC", Instant.now().minusSeconds(60), null, null))
                .isInstanceOf(RecurringTaskException.class);
    }

    @Test
    void recordAgentRunResultUpdatesLastRunFieldsWithoutExitCode() {
        service.createAgentMissionTask(
                "crypto-analysis", "maintenance", "Analyse BTC", "@daily", null, null);

        service.recordAgentRunResult("crypto-analysis", Instant.parse("2026-08-29T08:00:00Z"), "BTC en range.");

        RecurringTask task = service.getTask("crypto-analysis");
        assertThat(task.getLastRunAt()).isEqualTo(Instant.parse("2026-08-29T08:00:00Z"));
        assertThat(task.getLastExitCode()).isNull();
        assertThat(task.getLastRunStatus()).isEqualTo(RunStatus.OK);
        assertThat(task.getLastOutputSummary()).isEqualTo("BTC en range.");
        assertThat(task.getLastErrorMessage()).isNull();
    }

    @Test
    void setEnabledTogglesStatus() {
        service.createTask("healthcheck", "maintenance", "./health_check.sh", "@daily", null, null);

        service.setEnabled("healthcheck", false);
        assertThat(service.getTask("healthcheck").getStatus()).isEqualTo(RecurringTaskStatus.DISABLED);

        service.setEnabled("healthcheck", true);
        assertThat(service.getTask("healthcheck").getStatus()).isEqualTo(RecurringTaskStatus.ACTIVE);
    }

    @Test
    void deleteTaskRemovesItFromTheStore() {
        service.createTask("healthcheck", "maintenance", "./health_check.sh", "@daily", null, null);

        service.deleteTask("healthcheck");

        assertThat(service.findTask("healthcheck")).isEmpty();
    }

    @Test
    void deleteTaskRejectsUnknownName() {
        assertThatThrownBy(() -> service.deleteTask("inconnue")).isInstanceOf(RecurringTaskException.class);
    }

    @Test
    void recordRunResultUpdatesLastRunFieldsAndClearsPreviousError() {
        service.createTask("healthcheck", "maintenance", "./health_check.sh", "@daily", null, null);
        service.recordRunError("healthcheck", Instant.parse("2026-08-29T05:00:00Z"), "timeout");

        service.recordRunResult("healthcheck", Instant.parse("2026-08-29T06:00:00Z"), 1, RunStatus.WARNING, "systemd degraded");

        RecurringTask task = service.getTask("healthcheck");
        assertThat(task.getLastRunAt()).isEqualTo(Instant.parse("2026-08-29T06:00:00Z"));
        assertThat(task.getLastExitCode()).isEqualTo(1);
        assertThat(task.getLastRunStatus()).isEqualTo(RunStatus.WARNING);
        assertThat(task.getLastOutputSummary()).isEqualTo("systemd degraded");
        assertThat(task.getLastErrorMessage()).isNull();
    }

    @Test
    void recordRunErrorSetsErrorStatusWithoutExitCode() {
        service.createTask("healthcheck", "maintenance", "./health_check.sh", "@daily", null, null);

        service.recordRunError("healthcheck", Instant.parse("2026-08-29T06:00:00Z"), "Timeout depasse");

        RecurringTask task = service.getTask("healthcheck");
        assertThat(task.getLastRunStatus()).isEqualTo(RunStatus.ERROR);
        assertThat(task.getLastExitCode()).isNull();
        assertThat(task.getLastErrorMessage()).isEqualTo("Timeout depasse");
    }

    @Test
    void recordRunResultOnDeletedTaskIsIgnoredSilently() {
        service.recordRunResult("jamais-cree", Instant.now(), 0, RunStatus.OK, "ok");

        assertThat(service.findTask("jamais-cree")).isEmpty();
    }
}
