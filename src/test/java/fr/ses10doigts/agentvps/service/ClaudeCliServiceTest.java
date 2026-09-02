package fr.ses10doigts.agentvps.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.agentvps.config.ClaudeCliProperties;
import fr.ses10doigts.agentvps.config.ClaudeProvider;
import fr.ses10doigts.agentvps.model.ClaudeCliResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeCliServiceTest {

    private final ClaudeCliProperties properties = new ClaudeCliProperties();
    private ClaudeCliService service;

    @BeforeEach
    void setUp() {
        properties.setBinaryPath("/home/agentvps/.local/bin/claude");
        // Desactives par defaut dans ces tests : on isole la construction de la
        // commande de base. Les valeurs par defaut (dontAsk + settings-path) sont
        // couvertes par les tests dedies ci-dessous.
        properties.setPermissionMode("");
        properties.setSettingsPath("");
        service = new ClaudeCliService(properties, new ObjectMapper());
    }

    @Test
    void buildsCommandForNewConversation() {
        List<String> command = service.buildCommand("bonjour", null);

        assertThat(command).containsExactly(
                "/home/agentvps/.local/bin/claude", "-p", "bonjour", "--output-format", "json");
    }

    @Test
    void buildsCommandWithResume() {
        List<String> command = service.buildCommand("suite", "abc-123");

        assertThat(command).containsExactly(
                "/home/agentvps/.local/bin/claude", "-p", "suite", "--output-format", "json",
                "--resume", "abc-123");
    }

    @Test
    void buildsCommandWithoutResumeWhenSessionIdBlank() {
        List<String> command = service.buildCommand("bonjour", "  ");

        assertThat(command).doesNotContain("--resume");
    }

    @Test
    void buildsCommandWithAppendSystemPrompt() {
        List<String> command = service.buildCommand("bonjour", null, "instructions additionnelles");

        assertThat(command).containsExactly(
                "/home/agentvps/.local/bin/claude", "-p", "bonjour", "--output-format", "json",
                "--append-system-prompt", "instructions additionnelles");
    }

    @Test
    void omitsAppendSystemPromptWhenBlank() {
        List<String> command = service.buildCommand("bonjour", null, "   ");

        assertThat(command).doesNotContain("--append-system-prompt");
    }

        @Test
    void buildsCommandWithPermissionModeAndSettingsWhenConfigured() {
        properties.setPermissionMode("dontAsk");
        properties.setSettingsPath("/home/agentvps/.config/agentvps/claude-settings.json");

        List<String> command = service.buildCommand("bonjour", null);

        assertThat(command).containsExactly(
                "/home/agentvps/.local/bin/claude", "-p", "bonjour", "--output-format", "json",
                "--permission-mode", "dontAsk",
                "--settings", "/home/agentvps/.config/agentvps/claude-settings.json");
    }

    @Test
    void buildsCommandWithSettingsPathOverrideInsteadOfDefault() {
        properties.setSettingsPath("/home/agentvps/.config/agentvps/claude-settings.json");

        List<String> command = service.buildCommand("bonjour", null, null,
                "/home/agentvps/.config/agentvps/claude-settings-system.json");

        assertThat(command).contains(
                "--settings", "/home/agentvps/.config/agentvps/claude-settings-system.json");
        assertThat(command).doesNotContain("/home/agentvps/.config/agentvps/claude-settings.json");
    }

    @Test
    void buildsCommandWithDefaultSettingsPathWhenOverrideIsBlank() {
        properties.setSettingsPath("/home/agentvps/.config/agentvps/claude-settings.json");

        List<String> command = service.buildCommand("bonjour", null, null, "   ");

        assertThat(command).contains("--settings", "/home/agentvps/.config/agentvps/claude-settings.json");
    }

    @Test
    void omitsPermissionModeAndSettingsWhenBlank() {
        properties.setPermissionMode("  ");
        properties.setSettingsPath(null);

        List<String> command = service.buildCommand("bonjour", null);

        assertThat(command).doesNotContain("--permission-mode", "--settings");
    }

    @Test
    void defaultPropertiesEnablePermissionModeAndSettingsByDefault() {
        ClaudeCliProperties defaults = new ClaudeCliProperties();

        assertThat(defaults.getPermissionMode()).isEqualTo("dontAsk");
        assertThat(defaults.getSettingsPath()).isNotBlank();
    }

    @Test
    void defaultPropertiesHaveADistinctElevatedSettingsPath() {
        ClaudeCliProperties defaults = new ClaudeCliProperties();

        assertThat(defaults.getElevatedSettingsPath())
                .isNotBlank()
                .isNotEqualTo(defaults.getSettingsPath());
    }

    @Test
    void defaultProviderIsAnthropic() {
        ClaudeCliProperties defaults = new ClaudeCliProperties();

        assertThat(defaults.getProvider()).isEqualTo(ClaudeProvider.ANTHROPIC);
        assertThat(defaults.getOpenRouterBinaryPath()).isNotBlank();
        assertThat(defaults.getOpenRouterModel()).isNull();
    }

    @Test
    void buildsCommandForOpenRouterProviderWithModel() {
        properties.setProvider(ClaudeProvider.OPENROUTER);
        properties.setOpenRouterBinaryPath("/home/agentvps/.local/bin/ori");
        properties.setOpenRouterModel("openai/gpt-5");

        List<String> command = service.buildCommand("bonjour", null);

        assertThat(command).containsExactly(
                "/home/agentvps/.local/bin/ori", "claude", "--model", "openai/gpt-5",
                "-p", "bonjour", "--output-format", "json");
    }

    @Test
    void buildsCommandForOpenRouterProviderWithoutModelWhenBlank() {
        properties.setProvider(ClaudeProvider.OPENROUTER);
        properties.setOpenRouterBinaryPath("/home/agentvps/.local/bin/ori");
        properties.setOpenRouterModel("  ");

        List<String> command = service.buildCommand("bonjour", null);

        assertThat(command).containsExactly(
                "/home/agentvps/.local/bin/ori", "claude", "-p", "bonjour", "--output-format", "json");
        assertThat(command).doesNotContain("--model");
    }

    @Test
    void anthropicProviderIgnoresOpenRouterProperties() {
        properties.setOpenRouterBinaryPath("/home/agentvps/.local/bin/ori");
        properties.setOpenRouterModel("openai/gpt-5");

        List<String> command = service.buildCommand("bonjour", null);

        assertThat(command.getFirst()).isEqualTo("/home/agentvps/.local/bin/claude");
        assertThat(command).doesNotContain("ori", "--model");
    }

    @Test
    void appliesDisableAutoMemoryEnvironmentVariableByDefault() {
        ProcessBuilder processBuilder = new ProcessBuilder(List.of("true"));

        service.applyEnvironment(processBuilder);

        Map<String, String> env = processBuilder.environment();
        assertThat(env).containsEntry("CLAUDE_CODE_DISABLE_AUTO_MEMORY", "1");
    }

    @Test
    void omitsDisableAutoMemoryEnvironmentVariableWhenDisabled() {
        properties.setDisableAutoMemory(false);
        ProcessBuilder processBuilder = new ProcessBuilder(List.of("true"));

        service.applyEnvironment(processBuilder);

        Map<String, String> env = processBuilder.environment();
        assertThat(env).doesNotContainKey("CLAUDE_CODE_DISABLE_AUTO_MEMORY");
    }

    @Test
    void appliesTelemetryAndTrafficEnvironmentVariablesUnconditionally() {
        ProcessBuilder processBuilder = new ProcessBuilder(List.of("true"));

        service.applyEnvironment(processBuilder);

        Map<String, String> env = processBuilder.environment();
        assertThat(env).containsEntry("DISABLE_TELEMETRY", "1");
        assertThat(env).containsEntry("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
        // DISABLE_GROWTHBOOK=0 pose deliberement a cote de DISABLE_TELEMETRY=1 : GrowthBook
        // sert aussi a la livraison de killswitches distants (issue anthropics/claude-code#58383,
        // DISABLE_TELEMETRY le coupe silencieusement) - on evite de perdre cette couverture.
        assertThat(env).containsEntry("DISABLE_GROWTHBOOK", "0");
    }

    @Test
    void defaultPropertiesDisableAutoMemoryByDefault() {
        ClaudeCliProperties defaults = new ClaudeCliProperties();

        assertThat(defaults.isDisableAutoMemory()).isTrue();
    }

    @Test
    void parsesRawClaudeJsonOutput() {
        String json = "{\"is_error\":false,\"session_id\":\"s-1\",\"result\":\"pong\",\"subtype\":\"success\"}";

        ClaudeCliResult result = service.parseResult(json);

        assertThat(result.isError()).isFalse();
        assertThat(result.getSessionId()).isEqualTo("s-1");
        assertThat(result.getResult()).isEqualTo("pong");
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> service.parseResult("not json"))
                .isInstanceOf(ClaudeCliException.class);
    }

    @Test
    void rejectsBlankPrompt() {
        assertThatThrownBy(() -> service.call("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
