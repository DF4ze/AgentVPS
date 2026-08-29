package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.config.RecurringTaskProperties;
import fr.ses10doigts.agentvps.model.ClaudeCliResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * Voir RecurringTaskExecutionMode.AGENT_MISSION et la memoire projet
 * "phase7_scheduler_implementation" pour le contexte : une mission agent delegue a
 * ClaudeCliService (comme ChatService pour le chat interactif), mais toujours a froid
 * (resumeSessionId=null) et avec un timeout dedie (RecurringTaskProperties.agentMissionTimeoutSeconds).
 */
@ExtendWith(MockitoExtension.class)
class AgentMissionExecutionServiceTest {

    @Mock
    private ClaudeCliService claudeCliService;

    private AgentMissionExecutionService service;

    @BeforeEach
    void setUp() {
        RecurringTaskProperties properties = new RecurringTaskProperties();
        properties.setAgentMissionTimeoutSeconds(600);
        service = new AgentMissionExecutionService(claudeCliService, properties);
    }

    @Test
    void runDelegatesToClaudeCliServiceWithoutResumeAndWithTheDedicatedTimeout() {
        Path cwd = Path.of("/home/agentvps/AgentVPS/projects/maintenance");
        ClaudeCliResult expected = new ClaudeCliResult();
        expected.setResult("BTC en range, RSI neutre.");
        when(claudeCliService.call(eq("Analyse BTC"), isNull(), eq(cwd), any(), eq(600)))
                .thenReturn(expected);

        ClaudeCliResult result = service.run("Analyse BTC", cwd);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void runUsesTheProdVpsRulesSocleAndAMissionSpecificAddendum() {
        Path cwd = Path.of("/home/agentvps/AgentVPS/projects/maintenance");
        ClaudeCliResult expected = new ClaudeCliResult();
        when(claudeCliService.call(eq("Analyse BTC"), isNull(), eq(cwd), argThat(prompt ->
                prompt.contains("agent personnel de Clem") && prompt.contains("mission planifiee")), eq(600)))
                .thenReturn(expected);

        service.run("Analyse BTC", cwd);
    }

    @Test
    void rejectsBlankMissionPrompt() {
        assertThatThrownBy(() -> service.run("   ", Path.of("/tmp")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
