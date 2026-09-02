package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.config.ClaudeCliProperties;
import fr.ses10doigts.agentvps.model.ClaudeCliResult;
import fr.ses10doigts.agentvps.model.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Voir la memoire projet "prompting_architecture" pour le contexte complet des decisions
 * testees ici : socle system prompt fixe (prod-vps-rules-system-prompt.txt, toujours
 * envoye via --append-system-prompt) + renforcement periodique (periodic-reinforcement-prefix.txt,
 * injecte en PREFIXE du message utilisateur, jamais via --append-system-prompt) - obligatoire
 * au premier message d'une conversation (sessionId null), sinon selon
 * ProjectService.isReinforcementDue (mocke ici, teste independamment dans ProjectServiceTest).
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private ClaudeCliService claudeCliService;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        ClaudeCliProperties properties = new ClaudeCliProperties();
        properties.setReinforcementEveryMessages(10);
        properties.setElevatedSettingsPath("/home/agentvps/.config/agentvps/claude-settings-system.json");
        chatService = new ChatService(projectService, claudeCliService, properties);
    }

    @Test
    void firstMessageOfAConversationIsAlwaysReinforcedAndStartsTheConversation() {
        Project project = project("mon-projet", null);
        when(projectService.isReinforcementDue("mon-projet", null, 10)).thenReturn(true);

        ClaudeCliResult claudeResult = new ClaudeCliResult();
        claudeResult.setSessionId("session-1");
        claudeResult.setResult("Bonjour !");
        when(claudeCliService.call(any(), isNull(), eq(Path.of(project.getWorkingDirectory())), any(), isNull(), isNull()))
                .thenReturn(claudeResult);

        ClaudeCliResult result = chatService.sendMessage(project, "Salut");

        assertThat(result).isEqualTo(claudeResult);
        verify(claudeCliService).call(
                argThat(prompt -> prompt.contains("Salut") && prompt.contains("mémoire durable")),
                isNull(), eq(Path.of(project.getWorkingDirectory())), any(), isNull(), isNull());
        verify(projectService).recordConversationStart("mon-projet", "session-1", null);
        verify(projectService, never()).touchConversation(any(), any());
        verify(projectService, never()).recordReinforcementOutcome(any(), any(), anyBoolean());
    }

    @Test
    void reinforcementDuePrependsThePrefixAndResetsTheCounter() {
        Project project = project("mon-projet", "session-1");
        when(projectService.isReinforcementDue("mon-projet", "session-1", 10)).thenReturn(true);

        ClaudeCliResult claudeResult = new ClaudeCliResult();
        claudeResult.setResult("Ok");
        when(claudeCliService.call(any(), eq("session-1"), any(), any(), isNull(), isNull())).thenReturn(claudeResult);

        chatService.sendMessage(project, "Continue");

        verify(claudeCliService).call(
                argThat(prompt -> prompt.contains("Continue") && prompt.contains("mémoire durable")),
                eq("session-1"), eq(Path.of(project.getWorkingDirectory())), any(), isNull(), isNull());
        verify(projectService).touchConversation("mon-projet", "session-1");
        verify(projectService).recordReinforcementOutcome("mon-projet", "session-1", true);
        verify(projectService, never()).recordConversationStart(any(), any(), any());
    }

    @Test
    void reinforcementNotDueSendsTheRawMessageUnchanged() {
        Project project = project("mon-projet", "session-1");
        when(projectService.isReinforcementDue("mon-projet", "session-1", 10)).thenReturn(false);

        ClaudeCliResult claudeResult = new ClaudeCliResult();
        claudeResult.setResult("Ok");
        when(claudeCliService.call(eq("Continue"), eq("session-1"), any(), any(), isNull(), isNull())).thenReturn(claudeResult);

        chatService.sendMessage(project, "Continue");

        verify(claudeCliService).call(eq("Continue"), eq("session-1"), eq(Path.of(project.getWorkingDirectory())), any(), isNull(), isNull());
        verify(projectService).recordReinforcementOutcome("mon-projet", "session-1", false);
    }

    @Test
    void appendSystemPromptComesFromTheProdVpsRulesResource() {
        Project project = project("mon-projet", null);
        when(projectService.isReinforcementDue(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        ClaudeCliResult claudeResult = new ClaudeCliResult();
        claudeResult.setSessionId("session-1");
        claudeResult.setResult("ok");
        when(claudeCliService.call(any(), any(), any(), any(), any(), any())).thenReturn(claudeResult);

        chatService.sendMessage(project, "Salut");

        verify(claudeCliService).call(any(), any(), any(), argThat(prompt ->
                prompt.contains("agent personnel de Clem") && prompt.contains("Telegram affiche du texte brut")),
                any(), any());
    }

    @Test
    void aFailedClaudeCallDoesNotTouchProjectServiceState() {
        Project project = project("mon-projet", "session-1");
        when(projectService.isReinforcementDue("mon-projet", "session-1", 10)).thenReturn(false);
        when(claudeCliService.call(any(), any(), any(), any(), any(), any())).thenThrow(new ClaudeCliException("timeout"));

        assertThatThrownBy(() -> chatService.sendMessage(project, "Continue"))
                .isInstanceOf(ClaudeCliException.class);

        verify(projectService, never()).touchConversation(any(), any());
        verify(projectService, never()).recordReinforcementOutcome(any(), any(), anyBoolean());
        verify(projectService, never()).recordConversationStart(any(), any(), any());
    }

    @Test
    void elevatedProjectUsesTheElevatedSettingsPath() {
        Project project = project("system", null);
        project.setElevated(true);
        when(projectService.isReinforcementDue("system", null, 10)).thenReturn(true);

        ClaudeCliResult claudeResult = new ClaudeCliResult();
        claudeResult.setSessionId("session-1");
        claudeResult.setResult("Ok");
        when(claudeCliService.call(any(), isNull(), any(), any(), isNull(),
                eq("/home/agentvps/.config/agentvps/claude-settings-system.json")))
                .thenReturn(claudeResult);

        chatService.sendMessage(project, "Salut");

        verify(claudeCliService).call(any(), isNull(), eq(Path.of(project.getWorkingDirectory())), any(),
                isNull(), eq("/home/agentvps/.config/agentvps/claude-settings-system.json"));
    }

    @Test
    void nonElevatedProjectOmitsTheSettingsPathOverride() {
        Project project = project("mon-projet", null);
        when(projectService.isReinforcementDue("mon-projet", null, 10)).thenReturn(true);

        ClaudeCliResult claudeResult = new ClaudeCliResult();
        claudeResult.setSessionId("session-1");
        claudeResult.setResult("Ok");
        when(claudeCliService.call(any(), isNull(), any(), any(), isNull(), isNull())).thenReturn(claudeResult);

        chatService.sendMessage(project, "Salut");

        verify(claudeCliService).call(any(), isNull(), eq(Path.of(project.getWorkingDirectory())), any(),
                isNull(), isNull());
    }

    private static Project project(String name, String currentSessionId) {
        Project project = new Project();
        project.setName(name);
        project.setWorkingDirectory("/home/agentvps/AgentVPS/projects/" + name);
        project.setCurrentSessionId(currentSessionId);
        return project;
    }
}
