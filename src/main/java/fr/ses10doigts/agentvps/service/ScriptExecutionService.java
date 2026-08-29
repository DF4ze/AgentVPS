package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.config.RecurringTaskProperties;
import fr.ses10doigts.agentvps.model.ScriptExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Execute une commande de tache recurrente (RecurringTask.command) via ProcessBuilder,
 * dans le repertoire de travail du projet associe. Meme pattern que ClaudeCliService
 * (ProcessBuilder + StreamGobbler + timeout, stdout/stderr draines en parallele pour
 * eviter un deadlock), mais SANS passer par le CLI claude ni son systeme de permissions :
 * le script s'execute directement avec les privileges du process AgentVPS (utilisateur
 * systeme agentvps), voir RecurringTask pour le detail de cette distinction.
 *
 * Contrairement a ClaudeCliService.call(), un code de sortie non nul N'EST PAS une erreur
 * ici : c'est le resultat normal attendu d'un script de supervision (WARNING/CRITICAL,
 * voir RunStatus.fromExitCode) - seul un echec de DEMARRAGE ou un timeout leve
 * ScriptExecutionException (voir RecurringTaskScheduler pour la distinction avec
 * RecordRunError).
 *
 * Limite assumee (documentee, pas un bug) : la commande est decoupee naivement sur les
 * espaces (pas d'interpretation shell) - pas de pipes/redirections/guillemets avec
 * espaces internes. Suffisant pour un script + arguments simples ; a revoir si un futur
 * cas d'usage de la Phase 7 en a besoin.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScriptExecutionService {

    private final RecurringTaskProperties properties;

    public ScriptExecutionResult run(String command, Path workingDirectory) {
        if (command == null || command.isBlank()) {
            throw new ScriptExecutionException("La commande a executer ne peut pas etre vide");
        }

        List<String> tokens = Arrays.stream(command.trim().split("\\s+")).toList();
        log.info("Execution tache recurrente : {} (cwd={})", tokens, workingDirectory);

        ProcessBuilder processBuilder = new ProcessBuilder(tokens);
        processBuilder.directory(workingDirectory.toFile());

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException e) {
            throw new ScriptExecutionException(
                    "Impossible de demarrer la commande '" + command + "' dans " + workingDirectory, e);
        }

        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // pas bloquant
        }

        StreamGobbler stdoutGobbler = new StreamGobbler(process.getInputStream());
        StreamGobbler stderrGobbler = new StreamGobbler(process.getErrorStream());
        Thread stdoutThread = new Thread(stdoutGobbler, "recurring-task-stdout");
        Thread stderrThread = new Thread(stderrGobbler, "recurring-task-stderr");
        stdoutThread.start();
        stderrThread.start();

        boolean finished;
        try {
            finished = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ScriptExecutionException("Execution de '" + command + "' interrompue", e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new ScriptExecutionException(
                    "Timeout (" + properties.getTimeoutSeconds() + "s) depasse pour la commande '" + command + "'");
        }

        joinQuietly(stdoutThread);
        joinQuietly(stderrThread);

        int exitCode = process.exitValue();
        String stdout = stdoutGobbler.getOutput();
        String stderr = stderrGobbler.getOutput();

        log.info("Tache recurrente terminee : exitCode={}, cwd={}", exitCode, workingDirectory);
        return new ScriptExecutionResult(exitCode, stdout, stderr);
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Draine un flux (stdout ou stderr) dans un buffer, dans un thread dedie - copie de ClaudeCliService.StreamGobbler. */
    private static class StreamGobbler implements Runnable {
        private final InputStream inputStream;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        StreamGobbler(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        @Override
        public void run() {
            try {
                inputStream.transferTo(buffer);
            } catch (IOException ignored) {
                // le process s'est termine/a ete tue pendant la lecture ; pas bloquant
            }
        }

        String getOutput() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
