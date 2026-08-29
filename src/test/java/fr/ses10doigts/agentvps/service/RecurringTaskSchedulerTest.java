package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.model.NotificationPolicy;
import fr.ses10doigts.agentvps.model.Project;
import fr.ses10doigts.agentvps.model.RecurringTask;
import fr.ses10doigts.agentvps.model.RecurringTaskRunOutcome;
import fr.ses10doigts.agentvps.model.RecurringTaskStatus;
import fr.ses10doigts.agentvps.model.RunStatus;
import fr.ses10doigts.agentvps.model.ScriptExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecurringTaskSchedulerTest {

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private RecurringTaskService recurringTaskService;

    @Mock
    private ProjectService projectService;

    @Mock
    private ScriptExecutionService scriptExecutionService;

    @Mock
    private RecurringTaskNotifier notifier;

    private RecurringTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new RecurringTaskScheduler(taskScheduler, recurringTaskService, projectService, scriptExecutionService, notifier);
        lenient().when(recurringTaskService.findTask("healthcheck")).thenReturn(Optional.of(task("healthcheck")));
        lenient().when(projectService.getProject("maintenance")).thenReturn(project("maintenance"));
    }

    @Test
    void runNowRecordsResultAndDoesNotNotify() {
        when(scriptExecutionService.run("./health_check.sh", java.nio.file.Path.of("/home/agentvps/AgentVPS/projects/maintenance")))
                .thenReturn(new ScriptExecutionResult(1, "systemd degraded", ""));

        RecurringTaskRunOutcome outcome = scheduler.runNow("healthcheck");

        assertThat(outcome.status()).isEqualTo(RunStatus.WARNING);
        assertThat(outcome.exitCode()).isEqualTo(1);
        verify(recurringTaskService).recordRunResult(eq("healthcheck"), any(Instant.class), eq(1), eq(RunStatus.WARNING), eq("systemd degraded"));
        verify(notifier, never()).notify(any(), any());
    }

    @Test
    void runNowRecordsErrorWhenScriptExecutionFails() {
        when(scriptExecutionService.run(any(), any())).thenThrow(new ScriptExecutionException("Timeout depasse"));

        RecurringTaskRunOutcome outcome = scheduler.runNow("healthcheck");

        assertThat(outcome.status()).isEqualTo(RunStatus.ERROR);
        assertThat(outcome.exitCode()).isNull();
        verify(recurringTaskService).recordRunError(eq("healthcheck"), any(Instant.class), eq("Timeout depasse"));
    }

    @Test
    void scheduledExecutionNotifiesAccordingToPolicy() {
        when(scriptExecutionService.run(any(), any())).thenReturn(new ScriptExecutionResult(0, "ok", ""));
        RecurringTask activeTask = task("healthcheck");
        when(recurringTaskService.findTask("healthcheck")).thenReturn(Optional.of(activeTask));
        withScheduledFuture();

        scheduler.schedule(activeTask);
        Runnable scheduledRunnable = captureScheduledRunnable();
        scheduledRunnable.run();

        verify(notifier).notify(eq(activeTask), any(RecurringTaskRunOutcome.class));
    }

    @Test
    void overlappingExecutionOfTheSameTaskIsIgnored() {
        when(scriptExecutionService.run(any(), any())).thenAnswer(invocation -> {
            // Simule un second declenchement (ex. /tache run manuel) pendant que le premier tourne encore.
            RecurringTaskRunOutcome nested = scheduler.runNow("healthcheck");
            assertThat(nested.status()).isEqualTo(RunStatus.UNKNOWN);
            assertThat(nested.errorMessage()).contains("Deja en cours");
            return new ScriptExecutionResult(0, "ok", "");
        });

        RecurringTaskRunOutcome outer = scheduler.runNow("healthcheck");

        assertThat(outer.status()).isEqualTo(RunStatus.OK);
    }

    @Test
    void scheduleCancelsAnyPreviousFutureForTheSameTask() {
        ScheduledFuture<?> firstFuture = withScheduledFuture();

        scheduler.schedule(task("healthcheck"));
        scheduler.schedule(task("healthcheck"));

        verify(firstFuture).cancel(false);
    }

    @Test
    void unscheduleCancelsTheLiveFuture() {
        ScheduledFuture<?> future = withScheduledFuture();
        scheduler.schedule(task("healthcheck"));

        scheduler.unschedule("healthcheck");

        verify(future).cancel(false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ScheduledFuture<?> withScheduledFuture() {
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class))).thenReturn((ScheduledFuture) future);
        return future;
    }

    @SuppressWarnings("unchecked")
    private Runnable captureScheduledRunnable() {
        org.mockito.ArgumentCaptor<Runnable> captor = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(captor.capture(), any(Trigger.class));
        return captor.getValue();
    }

    private static RecurringTask task(String name) {
        RecurringTask task = new RecurringTask();
        task.setName(name);
        task.setProjectName("maintenance");
        task.setCommand("./health_check.sh");
        task.setCronExpression("0 0 6 * * *");
        task.setStatus(RecurringTaskStatus.ACTIVE);
        task.setNotificationPolicy(NotificationPolicy.ON_ISSUE);
        task.setCreatedAt(Instant.now());
        return task;
    }

    private static Project project(String name) {
        Project project = new Project();
        project.setName(name);
        project.setWorkingDirectory("/home/agentvps/AgentVPS/projects/" + name);
        return project;
    }
}
