package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.config.ClaudeCliProperties;
import fr.ses10doigts.agentvps.model.ClaudeCliResult;
import fr.ses10doigts.agentvps.model.NotificationPolicy;
import fr.ses10doigts.agentvps.model.Project;
import fr.ses10doigts.agentvps.model.RecurringTask;
import fr.ses10doigts.agentvps.model.RecurringTaskExecutionMode;
import fr.ses10doigts.agentvps.model.RecurringTaskRunOutcome;
import fr.ses10doigts.agentvps.model.RecurringTaskStatus;
import fr.ses10doigts.agentvps.model.RecurringTaskTriggerType;
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
import static org.assertj.core.api.Assertions.assertThatCode;
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

    @Mock
    private AgentMissionExecutionService agentMissionExecutionService;

    private RecurringTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new RecurringTaskScheduler(
                taskScheduler, recurringTaskService, projectService, scriptExecutionService, notifier,
                agentMissionExecutionService, new ClaudeCliProperties());
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

    // ------------------------------------------------------------ mode mission agent

    @Test
    void runNowExecutesAgentMissionAndRecordsResult() {
        RecurringTask mission = agentMissionTask("crypto-analysis");
        when(recurringTaskService.findTask("crypto-analysis")).thenReturn(Optional.of(mission));
        ClaudeCliResult result = new ClaudeCliResult();
        result.setResult("BTC en range, RSI neutre.");
        when(agentMissionExecutionService.run(
                eq("Analyse BTC quotidiennement"), eq(java.nio.file.Path.of("/home/agentvps/AgentVPS/projects/maintenance"))))
                .thenReturn(result);

        RecurringTaskRunOutcome outcome = scheduler.runNow("crypto-analysis");

        assertThat(outcome.status()).isEqualTo(RunStatus.OK);
        assertThat(outcome.exitCode()).isNull();
        assertThat(outcome.outputSummary()).isEqualTo("BTC en range, RSI neutre.");
        verify(recurringTaskService).recordAgentRunResult(
                eq("crypto-analysis"), any(Instant.class), eq("BTC en range, RSI neutre."));
        verify(scriptExecutionService, never()).run(any(), any());
    }

    @Test
    void runNowRecordsErrorWhenAgentMissionCallFails() {
        RecurringTask mission = agentMissionTask("crypto-analysis");
        when(recurringTaskService.findTask("crypto-analysis")).thenReturn(Optional.of(mission));
        when(agentMissionExecutionService.run(any(), any())).thenThrow(new ClaudeCliException("timeout"));

        RecurringTaskRunOutcome outcome = scheduler.runNow("crypto-analysis");

        assertThat(outcome.status()).isEqualTo(RunStatus.ERROR);
        assertThat(outcome.exitCode()).isNull();
        verify(recurringTaskService).recordRunError(eq("crypto-analysis"), any(Instant.class), eq("timeout"));
    }

    private static RecurringTask agentMissionTask(String name) {
        RecurringTask task = task(name);
        task.setExecutionMode(RecurringTaskExecutionMode.AGENT_MISSION);
        task.setCommand(null);
        task.setMissionPrompt("Analyse BTC quotidiennement");
        return task;
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

    /**
     * Bug reel trouve en prod le 29/08/2026 (premier declenchement cron de healthcheck) :
     * notifier.notify() a leve une IllegalStateException (voir RecurringTaskNotifier pour
     * le detail et le fix). Un echec de NOTIFICATION ne doit jamais faire remonter comme
     * un echec du declenchement planifie lui-meme (TaskUtils$LoggingErrorHandler cote
     * Spring) ni empecher le prochain declenchement de la meme tache de s'executer
     * normalement (verifie ici via /tache run juste apres, qui echouerait si le verrou
     * runningTasks n'avait pas ete relache par le finally de executeTask malgre l'exception).
     */
    @Test
    void notificationFailureDuringAScheduledRunIsSwallowedAndDoesNotBlockTheNextRun() {
        when(scriptExecutionService.run(any(), any())).thenReturn(new ScriptExecutionResult(0, "ok", ""));
        RecurringTask activeTask = task("healthcheck");
        when(recurringTaskService.findTask("healthcheck")).thenReturn(Optional.of(activeTask));
        withScheduledFuture();
        org.mockito.Mockito.doThrow(new IllegalStateException("No current Telegram bot is bound to the current thread"))
                .when(notifier).notify(eq(activeTask), any(RecurringTaskRunOutcome.class));

        scheduler.schedule(activeTask);
        Runnable scheduledRunnable = captureScheduledRunnable();

        assertThatCode(scheduledRunnable::run).doesNotThrowAnyException();

        RecurringTaskRunOutcome nextRun = scheduler.runNow("healthcheck");
        assertThat(nextRun.status()).isEqualTo(RunStatus.OK);
    }

    // -------------------------------------------------------- mode ponctuel

    @Test
    void scheduleUsesTheInstantOverloadForAOneTimeTask() {
        Instant scheduledAt = Instant.now().plusSeconds(3600);
        RecurringTask oneTime = oneTimeTask("rappel", scheduledAt);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        when(taskScheduler.schedule(any(Runnable.class), eq(scheduledAt))).thenReturn((ScheduledFuture) future);

        scheduler.schedule(oneTime);

        verify(taskScheduler).schedule(any(Runnable.class), eq(scheduledAt));
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    void scheduleSkipsAOneTimeTaskWithAPastScheduledAt() {
        RecurringTask oneTime = oneTimeTask("rappel", Instant.now().minusSeconds(60));

        scheduler.schedule(oneTime);

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void scheduleSkipsAOneTimeTaskWithNoScheduledAt() {
        RecurringTask oneTime = oneTimeTask("rappel", null);

        scheduler.schedule(oneTime);

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void firingAOneTimeTaskAutoDisablesItAfterExecution() {
        when(scriptExecutionService.run(any(), any())).thenReturn(new ScriptExecutionResult(0, "ok", ""));
        Instant scheduledAt = Instant.now().plusSeconds(3600);
        RecurringTask oneTime = oneTimeTask("rappel", scheduledAt);
        when(recurringTaskService.findTask("rappel")).thenReturn(Optional.of(oneTime));
        @SuppressWarnings({"unchecked", "rawtypes"})
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        when(taskScheduler.schedule(any(Runnable.class), eq(scheduledAt))).thenReturn((ScheduledFuture) future);
        scheduler.schedule(oneTime);
        org.mockito.ArgumentCaptor<Runnable> captor = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(captor.capture(), eq(scheduledAt));

        captor.getValue().run();

        verify(recurringTaskService).setEnabled("rappel", false);
        verify(future).cancel(false);
    }

    @Test
    void autoDisableFailureAfterAOneTimeRunDoesNotPropagate() {
        when(scriptExecutionService.run(any(), any())).thenReturn(new ScriptExecutionResult(0, "ok", ""));
        Instant scheduledAt = Instant.now().plusSeconds(3600);
        RecurringTask oneTime = oneTimeTask("rappel", scheduledAt);
        when(recurringTaskService.findTask("rappel")).thenReturn(Optional.of(oneTime));
        org.mockito.Mockito.doThrow(new RuntimeException("ecriture disque impossible"))
                .when(recurringTaskService).setEnabled("rappel", false);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        when(taskScheduler.schedule(any(Runnable.class), eq(scheduledAt))).thenReturn((ScheduledFuture) future);
        scheduler.schedule(oneTime);
        org.mockito.ArgumentCaptor<Runnable> captor = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(captor.capture(), eq(scheduledAt));

        assertThatCode(() -> captor.getValue().run()).doesNotThrowAnyException();
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

    private static RecurringTask oneTimeTask(String name, Instant scheduledAt) {
        RecurringTask task = task(name);
        task.setTriggerType(RecurringTaskTriggerType.ONE_TIME);
        task.setCronExpression(null);
        task.setScheduledAt(scheduledAt);
        return task;
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
