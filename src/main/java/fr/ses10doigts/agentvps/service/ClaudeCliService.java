package fr.ses10doigts.agentvps.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.agentvps.config.ClaudeCliProperties;
import fr.ses10doigts.agentvps.model.ClaudeCliResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Invoque le CLI Claude Code en mode script (claude -p ... --output-format json)
 * via ProcessBuilder, capture stdout/stderr avec timeout configurable, et parse
 * la sortie JSON. Voir roadmap-implementation.md (Phase 3, point 3) et
 * agent-vps-notes.md (section 5.2/5.3) pour le contexte et le protocole exact.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClaudeCliService {

    private final ClaudeCliProperties properties;
    private final ObjectMapper objectMapper;

    /** Premier appel d'une nouvelle conversation, sans session a reprendre. */
    public ClaudeCliResult call(String prompt) {
        return call(prompt, null, null);
    }

    /**
     * Appelle claude -p, en reprenant eventuellement une session existante
     * (continuite de conversation, voir agent-vps-notes.md section 5.3) et/ou dans un
     * repertoire de travail donne (essence de projet via CLAUDE.md, section 5.5).
     *
     * @param prompt           message a envoyer, non vide
     * @param resumeSessionId  session_id a reprendre, ou null/vide pour une nouvelle conversation
     * @param workingDirectory repertoire de travail du process, ou null pour le repertoire courant de l'application
     */
    public ClaudeCliResult call(String prompt, String resumeSessionId, Path workingDirectory) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Le prompt ne peut pas etre vide");
        }

        List<String> command = buildCommand(prompt, resumeSessionId);
        log.info("Appel claude CLI (resume={}, cwd={})", resumeSessionId != null, workingDirectory);
        log.debug("Commande : {}", command);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            processBuilder.directory(workingDirectory.toFile());
        }

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException e) {
            throw new ClaudeCliException(
                    "Impossible de demarrer le process claude (binaire : " + properties.getBinaryPath() + ")", e);
        }

        // Le prompt est deja passe en argument : on ferme stdin immediatement pour
        // eviter que claude n'attende inutilement des donnees sur l'entree standard.
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // pas bloquant
        }

        // stdout et stderr doivent etre draines en parallele pour eviter un deadlock
        // si l'un des deux tampons se remplit avant que le process ne se termine.
        StreamGobbler stdoutGobbler = new StreamGobbler(process.getInputStream());
        StreamGobbler stderrGobbler = new StreamGobbler(process.getErrorStream());
        Thread stdoutThread = new Thread(stdoutGobbler, "claude-cli-stdout");
        Thread stderrThread = new Thread(stderrGobbler, "claude-cli-stderr");
        stdoutThread.start();
        stderrThread.start();

        boolean finished;
        try {
            finished = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ClaudeCliException("Appel claude CLI interrompu", e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new ClaudeCliException(
                    "Timeout (" + properties.getTimeoutSeconds() + "s) depasse lors de l'appel claude CLI");
        }

        joinQuietly(stdoutThread);
        joinQuietly(stderrThread);

        String stdout = stdoutGobbler.getOutput();
        String stderr = stderrGobbler.getOutput();
        int exitCode = process.exitValue();

        if (exitCode != 0) {
            throw new ClaudeCliException("claude CLI a termine avec le code %d. stderr : %s"
                    .formatted(exitCode, stderr.isBlank() ? "(vide)" : stderr.strip()));
        }

        ClaudeCliResult result = parseResult(stdout);

        if (result.isError()) {
            throw new ClaudeCliException("claude a renvoye une erreur (subtype=%s) : %s"
                    .formatted(result.getSubtype(), result.getResult()));
        }

        return result;
    }

    List<String> buildCommand(String prompt, String resumeSessionId) {
        List<String> command = new ArrayList<>();
        command.add(properties.getBinaryPath());
        command.add("-p");
        command.add(prompt);
        command.add("--output-format");
        command.add("json");
        if (resumeSessionId != null && !resumeSessionId.isBlank()) {
            command.add("--resume");
            command.add(resumeSessionId);
        }
        if (properties.getPermissionMode() != null && !properties.getPermissionMode().isBlank()) {
            command.add("--permission-mode");
            command.add(properties.getPermissionMode());
        }
        if (properties.getSettingsPath() != null && !properties.getSettingsPath().isBlank()) {
            command.add("--settings");
            command.add(properties.getSettingsPath());
        }
        return command;
    }

    ClaudeCliResult parseResult(String stdout) {
        try {
            return objectMapper.readValue(stdout, ClaudeCliResult.class);
        } catch (JsonProcessingException e) {
            throw new ClaudeCliException("Sortie JSON invalide renvoyee par claude CLI : " + truncate(stdout), e);
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    /** Draine un flux (stdout ou stderr) dans un buffer, dans un thread dedie. */
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
