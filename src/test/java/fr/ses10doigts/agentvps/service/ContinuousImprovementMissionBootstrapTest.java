package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.model.NotificationPolicy;
import fr.ses10doigts.agentvps.model.Project;
import fr.ses10doigts.agentvps.model.RecurringTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Voir la memoire projet "continuous_improvement_capture" (etape 2/3 : extraction) pour le
 * contexte complet : cette tache est seedee automatiquement au demarrage, mais DOIT rester
 * DISABLED juste apres sa creation (demande explicite de Clem, 03/09/2026) - c'est le
 * comportement le plus important a couvrir ici.
 */
@ExtendWith(MockitoExtension.class)
class ContinuousImprovementMissionBootstrapTest {

    @Mock
    private RecurringTaskService recurringTaskService;

    @Mock
    private ProjectService projectService;

    private ContinuousImprovementMissionBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bootstrap = new ContinuousImprovementMissionBootstrap(recurringTaskService, projectService);
    }

    @Test
    void seedsTheTaskDisabledWhenTheSystemProjectExistsAndTheTaskIsMissing() {
        when(recurringTaskService.findTask(ContinuousImprovementMissionBootstrap.TASK_NAME))
                .thenReturn(Optional.empty());
        when(projectService.findProjectByName("system")).thenReturn(Optional.of(new Project()));

        bootstrap.seedIfNeeded();

        verify(recurringTaskService).createAgentMissionTask(
                eq(ContinuousImprovementMissionBootstrap.TASK_NAME), eq("system"), any(), eq("@weekly"),
                eq(NotificationPolicy.ALWAYS), any());
        verify(recurringTaskService).setEnabled(ContinuousImprovementMissionBootstrap.TASK_NAME, false);
    }

    @Test
    void doesNothingWhenTheTaskAlreadyExists() {
        RecurringTask existing = new RecurringTask();
        existing.setName(ContinuousImprovementMissionBootstrap.TASK_NAME);
        when(recurringTaskService.findTask(ContinuousImprovementMissionBootstrap.TASK_NAME))
                .thenReturn(Optional.of(existing));

        bootstrap.seedIfNeeded();

        verify(recurringTaskService, never()).createAgentMissionTask(
                any(), any(), any(), any(), any(), any());
        verify(recurringTaskService, never()).setEnabled(any(), anyBoolean());
        verify(projectService, never()).findProjectByName(any());
    }

    @Test
    void doesNothingWhenTheSystemProjectDoesNotExistYet() {
        when(recurringTaskService.findTask(ContinuousImprovementMissionBootstrap.TASK_NAME))
                .thenReturn(Optional.empty());
        when(projectService.findProjectByName("system")).thenReturn(Optional.empty());

        bootstrap.seedIfNeeded();

        verify(recurringTaskService, never()).createAgentMissionTask(
                any(), any(), any(), any(), any(), any());
        verify(recurringTaskService, never()).setEnabled(any(), anyBoolean());
    }

    @Test
    void neverThrowsWhenTaskCreationFails() {
        when(recurringTaskService.findTask(ContinuousImprovementMissionBootstrap.TASK_NAME))
                .thenReturn(Optional.empty());
        when(projectService.findProjectByName("system")).thenReturn(Optional.of(new Project()));
        when(recurringTaskService.createAgentMissionTask(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RecurringTaskException("projet introuvable"));

        assertThatCode(() -> bootstrap.seedIfNeeded()).doesNotThrowAnyException();

        verify(recurringTaskService, never()).setEnabled(any(), anyBoolean());
    }
}
