package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.config.RecurringTaskProperties;
import fr.ses10doigts.agentvps.model.ScriptExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Utilise le binaire "java" lui-meme (deja sur le PATH pendant le build Maven, sur
 * n'importe quel OS - contrairement a echo/sh/cmd) pour tester une vraie execution de
 * process sans dependre d'un shell specifique a la plateforme (le poste de dev de Clem
 * est Windows, le VPS cible est Linux - voir memoire projet "build_warnings_fix").
 */
class ScriptExecutionServiceTest {

    private final RecurringTaskProperties properties = new RecurringTaskProperties();
    private ScriptExecutionService service;

    @BeforeEach
    void setUp() {
        service = new ScriptExecutionService(properties);
    }

    @Test
    void runsARealCommandAndCapturesExitCodeAndOutput(@TempDir Path tempDir) {
        ScriptExecutionResult result = service.run("java -version", tempDir);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout() + result.stderr()).contains("version");
    }

    @Test
    void capturesANonZeroExitCode(@TempDir Path tempDir) {
        ScriptExecutionResult result = service.run("java --this-flag-does-not-exist", tempDir);

        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void throwsWhenTheCommandCannotBeStarted(@TempDir Path tempDir) {
        assertThatThrownBy(() -> service.run("this-binary-does-not-exist-agentvps-test", tempDir))
                .isInstanceOf(ScriptExecutionException.class);
    }

    @Test
    void throwsWhenCommandIsBlank(@TempDir Path tempDir) {
        assertThatThrownBy(() -> service.run("   ", tempDir))
                .isInstanceOf(ScriptExecutionException.class);
    }
}
