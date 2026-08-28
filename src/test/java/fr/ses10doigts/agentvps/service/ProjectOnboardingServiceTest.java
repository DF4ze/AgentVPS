package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.model.ClaudeCliResult;
import fr.ses10doigts.agentvps.model.Conversation;
import fr.ses10doigts.agentvps.model.Project;
import fr.ses10doigts.agentvps.model.ProjectStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectOnboardingServiceTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private ClaudeCliService claudeCliService;

    private ProjectOnboardingService onboardingService;

    @BeforeEach
    void setUp() {
        onboardingService = new ProjectOnboardingService(projectService, claudeCliService);
    }

    @Test
    void createsProjectThenCallsClaudeWithOnboardingSystemPromptInProjectDirectory() {
        Project project = project("mon-projet", "/home/agentvps/AgentVPS/projects/mon-projet");
        when(projectService.createProject("Mon Projet")).thenReturn(project);

        ClaudeCliResult claudeResult = new ClaudeCliResult();
        claudeResult.setSessionId("session-1");
        claudeResult.setResult("A quoi va servir ce projet ?");
        when(claudeCliService.call(any(), isNull(), eq(Path.of(project.getWorkingDirectory())), any()))
                .thenReturn(claudeResult);

        Conversation conversation = new Conversation("session-1", Instant.now(), Instant.now(), "Mise en place initiale");
        when(projectService.recordConversationStart("mon-projet", "session-1", "Mise en place initiale"))
                .thenReturn(conversation);

        ProjectOnboardingService.OnboardingResult result = onboardingService.createProjectAndStartOnboarding("Mon Projet");

        assertThat(result.project()).isEqualTo(project);
        assertThat(result.conversation()).isEqualTo(conversation);
        assertThat(result.firstClaudeMessage()).isEqualTo("A quoi va servir ce projet ?");

        verify(claudeCliService).call(
                eq(ProjectOnboardingService.KICKOFF_PROMPT), isNull(), eq(Path.of(project.getWorkingDirectory())), any());
        verify(projectService).recordConversationStart("mon-projet", "session-1", "Mise en place initiale");
    }

    @Test
    void appendSystemPromptComesFromTheOnboardingPromptResource() {
        Project project = project("mon-projet", "/home/agentvps/AgentVPS/projects/mon-projet");
        when(projectService.createProject(any())).thenReturn(project);
        ClaudeCliResult claudeResult = new ClaudeCliResult();
        claudeResult.setSessionId("session-1");
        when(claudeCliService.call(any(), any(), any(), any())).thenReturn(claudeResult);

        onboardingService.createProjectAndStartOnboarding("mon-projet");

        verify(claudeCliService).call(any(), any(), any(), org.mockito.ArgumentMatchers.contains("CLAUDE.md"));
    }

    @Test
    void doesNotRecordAConversationWhenTheClaudeCallFails() {
        Project project = project("mon-projet", "/home/agentvps/AgentVPS/projects/mon-projet");
        when(projectService.createProject(any())).thenReturn(project);
        when(claudeCliService.call(any(), any(), any(), any()))
                .thenThrow(new ClaudeCliException("timeout"));

        assertThatThrownBy(() -> onboardingService.createProjectAndStartOnboarding("mon-projet"))
                .isInstanceOf(ClaudeCliException.class);

        verify(projectService, never()).recordConversationStart(any(), any(), any());
    }

    private static Project project(String name, String workingDirectory) {
        Project project = new Project();
        project.setName(name);
        project.setStatus(ProjectStatus.ACTIVE);
        project.setCreatedAt(Instant.now());
        project.setWorkingDirectory(workingDirectory);
        return project;
    }
}
