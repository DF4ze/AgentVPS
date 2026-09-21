package fr.ses10doigts.agentvps.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.agentvps.config.ClaudeCliProperties;
import fr.ses10doigts.agentvps.config.ClaudeProvider;
import fr.ses10doigts.agentvps.model.ClaudeCliResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Invoque le CLI Claude Code en mode script (claude -p ... --output-format json)
 * via ProcessBuilder, capture stdout/stderr avec timeout configurable, et parse
 * la sortie JSON. Voir roadmap-implementation.md (Phase 3, point 3) et
 * agent-vps-notes.md (section 5.2/5.3) pour le contexte et le protocole exact.
 *
 * Depuis fin aout 2026, peut aussi router l'appel via Ori Harness vers un modele
 * OpenRouter (ClaudeCliProperties.provider=OPENROUTER) - voir buildCommand() et la
 * memoire projet "ori_openrouter_integration" pour le detail de la validation.
 *
 * Depuis le 03/09/2026, peut aussi capturer le flux complet d'un appel (raisonnement
 * inclus quand le provider le permet) en JSONL - voir la surcharge de call() a 7
 * arguments et la memoire projet "continuous_improvement_capture".
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
        return call(prompt, resumeSessionId, workingDirectory, null);
    }

    /**
     * Variante de call() acceptant un system-prompt additionnel (--append-system-prompt),
     * utilisee pour l'interview de creation de projet (voir ProjectOnboardingService) :
     * pas de flag --system-prompt-file dans cette version de claude (voir
     * roadmap-implementation.md, Phase 0 point 4), le texte est donc passe tel quel en
     * argument - pas de souci d'echappement, ProcessBuilder(List) ne passe pas par un shell.
     *
     * @param appendSystemPrompt texte ajoute au system prompt par defaut de claude, ou null/vide pour l'omettre
     */
    public ClaudeCliResult call(String prompt, String resumeSessionId, Path workingDirectory, String appendSystemPrompt) {
        return call(prompt, resumeSessionId, workingDirectory, appendSystemPrompt, null);
    }

    /**
     * Variante de call() acceptant en plus un timeout dedie (secondes), qui remplace
     * agentvps.claude.timeout-seconds pour cet appel uniquement - utilisee par
     * AgentMissionExecutionService (roadmap Phase 7, mode "mission agent", ajoute le
     * 29/08/2026) : une mission autonome peut enchainer plusieurs appels reseau/MCP,
     * largement au-dela du delai raisonnable pour une reponse de chat interactif.
     *
     * @param timeoutSecondsOverride delai maximum (secondes) avant destruction forcee du
     *                                process pour cet appel, ou null pour garder
     *                                properties.getTimeoutSeconds() (comportement des
     *                                autres overloads de call())
     */
    public ClaudeCliResult call(String prompt, String resumeSessionId, Path workingDirectory,
                                 String appendSystemPrompt, Integer timeoutSecondsOverride) {
        return call(prompt, resumeSessionId, workingDirectory, appendSystemPrompt, timeoutSecondsOverride, null);
    }

    /**
     * Variante de call() acceptant en plus un chemin --settings dedie, qui remplace
     * agentvps.claude.settings-path pour cet appel uniquement - utilisee par ChatService
     * pour un projet a droits elargis (Project.elevated, voir ClaudeCliProperties.elevatedSettingsPath
     * et la memoire projet "god_mode_system_project"). Les autres appelants (onboarding,
     * mission agent) passent par les overloads existants et continuent d'utiliser
     * properties.getSettingsPath() sans rien changer a leur comportement.
     *
     * Delegue vers la surcharge a 7 arguments avec captureLogPath=null : comportement
     * strictement inchange pour tous les appelants existants de CETTE surcharge
     * (--output-format json, pas de capture).
     *
     * @param settingsPathOverride chemin --settings a utiliser pour cet appel, ou null/vide
     *                              pour garder properties.getSettingsPath() (comportement des
     *                              autres overloads de call())
     */
    public ClaudeCliResult call(String prompt, String resumeSessionId, Path workingDirectory,
                                 String appendSystemPrompt, Integer timeoutSecondsOverride,
                                 String settingsPathOverride) {
        return call(prompt, resumeSessionId, workingDirectory, appendSystemPrompt, timeoutSecondsOverride,
                settingsPathOverride, null);
    }

    /**
     * Variante de call() acceptant en plus un chemin de capture JSONL du flux complet de
     * l'appel (voir la memoire projet "continuous_improvement_capture") - utilisee par
     * ChatService quand ClaudeCliProperties.captureConversationLogs est actif.
     *
     * Quand captureLogPath est non-null : la commande passe en --output-format stream-json
     * --verbose (voir buildCommand()) au lieu de json ; CHAQUE ligne du flux (raisonnement
     * inclus quand le provider le permet, tool_use/tool_result, texte visible, evenement
     * final "result") est ecrite telle quelle (append) dans ce fichier ; le resultat
     * structure retourne par cette methode est reconstruit a partir de la DERNIERE ligne du
     * flux (type "result"), qui a exactement les memes champs que l'objet unique renvoye par
     * --output-format json (verifie en conditions reelles le 03/09/2026) - ClaudeCliResult/
     * parseResult() sont donc reutilises tels quels, sans nouveau modele de parsing.
     *
     * La capture est best-effort : un echec d'ecriture du fichier JSONL ne fait jamais
     * echouer l'appel reel (meme philosophie que RecurringTaskNotifier pour les
     * notifications Telegram, voir la memoire projet "phase7_scheduler_implementation").
     *
     * @param captureLogPath chemin du fichier JSONL a alimenter (append-only, cree si besoin
     *                        y compris son dossier parent), ou null pour le comportement
     *                        standard (--output-format json, pas de capture)
     */
    public ClaudeCliResult call(String prompt, String resumeSessionId, Path workingDirectory,
                                 String appendSystemPrompt, Integer timeoutSecondsOverride,
                                 String settingsPathOverride, Path captureLogPath) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Le prompt ne peut pas etre vide");
        }

        boolean streamJson = captureLogPath != null;
        int effectiveTimeoutSeconds = timeoutSecondsOverride != null ? timeoutSecondsOverride : properties.getTimeoutSeconds();

        List<String> command = buildCommand(prompt, resumeSessionId, appendSystemPrompt, settingsPathOverride, streamJson);
        log.info("Appel claude CLI (provider={}, resume={}, cwd={}, appendSystemPrompt={}, timeoutSeconds={}, capture={})",
                properties.getProvider(), resumeSessionId != null, workingDirectory,
                appendSystemPrompt != null && !appendSystemPrompt.isBlank(), effectiveTimeoutSeconds, streamJson);
        log.debug("Commande : {}", command);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            processBuilder.directory(workingDirectory.toFile());
        }
        applyEnvironment(processBuilder);

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException e) {
            throw new ClaudeCliException(
                    "Impossible de demarrer le process claude (binaire : " + command.getFirst() + ")", e);
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
            finished = process.waitFor(effectiveTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ClaudeCliException("Appel claude CLI interrompu", e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new ClaudeCliException(
                    "Timeout (" + effectiveTimeoutSeconds + "s) depasse lors de l'appel claude CLI");
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

        if (captureLogPath != null) {
            appendCapture(captureLogPath, stdout);
        }
        ClaudeCliResult result = parseResult(streamJson ? lastNonBlankLine(stdout) : stdout);

        if (result.isError()) {
            throw new ClaudeCliException("claude a renvoye une erreur (subtype=%s) : %s"
                    .formatted(result.getSubtype(), result.getResult()));
        }

        log.info("Appel claude CLI reussi (session_id={}, num_turns={}, duration_ms={}, cost_usd={})",
                result.getSessionId(), result.getNumTurns(), result.getDurationMs(), result.getTotalCostUsd());

        return result;
    }

    List<String> buildCommand(String prompt, String resumeSessionId) {
        return buildCommand(prompt, resumeSessionId, null);
    }

    /**
     * Construit la commande a executer. En provider=ANTHROPIC (defaut), c'est le
     * binaire claude directement. En provider=OPENROUTER, c'est "ori claude
     * [--model <id>]" - Ori Harness localise le vrai binaire claude, pose les
     * variables d'environnement necessaires (ANTHROPIC_BASE_URL vers OpenRouter,
     * etc.) puis l'execute en lui passant tous les flags qui suivent tels quels
     * (-p, --output-format json, --resume, --permission-mode, --settings...) - voir
     * la memoire projet "ori_openrouter_integration" pour la verification faite le
     * 29/08/2026 (appel -p reel avec openai/gpt-5, meme schema JSON en sortie).
     */
    List<String> buildCommand(String prompt, String resumeSessionId, String appendSystemPrompt) {
        return buildCommand(prompt, resumeSessionId, appendSystemPrompt, null);
    }

    /**
     * Variante de buildCommand() acceptant en plus un chemin --settings dedie (voir le
     * call() a 6 arguments ci-dessus pour le contexte complet). Delegue vers la surcharge
     * a 5 arguments avec streamJson=false (--output-format json, comportement inchange).
     *
     * @param settingsPathOverride chemin --settings a utiliser, ou null/vide pour garder
     *                              properties.getSettingsPath()
     */
    List<String> buildCommand(String prompt, String resumeSessionId, String appendSystemPrompt,
                               String settingsPathOverride) {
        return buildCommand(prompt, resumeSessionId, appendSystemPrompt, settingsPathOverride, false);
    }

    /**
     * Variante de buildCommand() acceptant en plus streamJson : quand true, remplace
     * "--output-format json" par "--output-format stream-json --verbose" (necessaire pour
     * la capture du flux complet, voir call() a 7 arguments et la memoire projet
     * "continuous_improvement_capture" - --verbose confirme necessaire en conditions
     * reelles le 03/09/2026, pas seulement documente comme requis pour les options
     * avancees).
     */
    List<String> buildCommand(String prompt, String resumeSessionId, String appendSystemPrompt,
                               String settingsPathOverride, boolean streamJson) {
        List<String> command = new ArrayList<>();
        if (properties.getProvider() == ClaudeProvider.OPENROUTER) {
            command.add(properties.getOpenRouterBinaryPath());
            command.add("claude");
            if (properties.getOpenRouterModel() != null && !properties.getOpenRouterModel().isBlank()) {
                command.add("--model");
                command.add(properties.getOpenRouterModel());
            }
        } else {
            command.add(properties.getBinaryPath());
        }
        command.add("-p");
        command.add(prompt);
        command.add("--output-format");
        command.add(streamJson ? "stream-json" : "json");
        if (streamJson) {
            command.add("--verbose");
        }
        if (resumeSessionId != null && !resumeSessionId.isBlank()) {
            command.add("--resume");
            command.add(resumeSessionId);
        }
        if (appendSystemPrompt != null && !appendSystemPrompt.isBlank()) {
            command.add("--append-system-prompt");
            command.add(appendSystemPrompt);
        }
        if (properties.getPermissionMode() != null && !properties.getPermissionMode().isBlank()) {
            command.add("--permission-mode");
            command.add(properties.getPermissionMode());
        }
        String effectiveSettingsPath = (settingsPathOverride != null && !settingsPathOverride.isBlank())
                ? settingsPathOverride
                : properties.getSettingsPath();
        if (effectiveSettingsPath != null && !effectiveSettingsPath.isBlank()) {
            command.add("--settings");
            command.add(effectiveSettingsPath);
        }
        return command;
    }

    /**
     * Applique les variables d'environnement necessaires au sous-processus claude,
     * en plus de celles heritees du process Java (ProcessBuilder.environment() est
     * une copie mutable de l'environnement courant, pas un environnement vide).
     *
     * - CLAUDE_CODE_DISABLE_AUTO_MEMORY (conditionnel, voir ClaudeCliProperties.disableAutoMemory)
     *   desactive l'"auto memory" native de Claude Code (decouverte du 28/08/2026 -
     *   cette memoire etait bloquee par le settings.json de la Phase 1 securite, ce
     *   qui faisait perdre des tours a claude).
     * - DISABLE_TELEMETRY / CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC / DISABLE_GROWTHBOOK
     *   (poses en dur, inconditionnels) reduisent le trafic reseau non essentiel et la
     *   telemetrie envoyee par Claude Code. DISABLE_GROWTHBOOK=0 est deliberement pose a
     *   cote de DISABLE_TELEMETRY=1 : GrowthBook sert aussi a la livraison de
     *   "killswitches" distants (cf issue anthropics/claude-code#58383 - DISABLE_TELEMETRY
     *   coupe silencieusement GrowthBook en entier), donc on le reactive explicitement
     *   pour ne pas perdre cette couverture. C'est exactement la meme combinaison que
     *   celle posee par Ori Harness pour ses propres lancements (verifiee par capture
     *   d'environnement le 29/08/2026), appliquee ici que le provider soit ANTHROPIC ou
     *   OPENROUTER.
     */
    void applyEnvironment(ProcessBuilder processBuilder) {
        if (properties.isDisableAutoMemory()) {
            processBuilder.environment().put("CLAUDE_CODE_DISABLE_AUTO_MEMORY", "1");
        }
        processBuilder.environment().put("DISABLE_TELEMETRY", "1");
        processBuilder.environment().put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
        processBuilder.environment().put("DISABLE_GROWTHBOOK", "0");
    }

    ClaudeCliResult parseResult(String stdout) {
        try {
            return objectMapper.readValue(stdout, ClaudeCliResult.class);
        } catch (JsonProcessingException e) {
            throw new ClaudeCliException("Sortie JSON invalide renvoyee par claude CLI : " + truncate(stdout), e);
        }
    }

    /**
     * Extrait la derniere ligne non-vide d'une sortie --output-format stream-json
     * (une ligne JSON par evenement) - c'est toujours l'evenement final de type
     * "result", structurellement compatible avec ClaudeCliResult (voir call() a 7
     * arguments). Renvoie la chaine entiere telle quelle si aucune ligne non-vide
     * n'est trouvee (parseResult() produira alors une erreur explicite plutot que de
     * silencieusement traiter une chaine vide).
     */
    static String lastNonBlankLine(String stdout) {
        String[] lines = stdout.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i];
            }
        }
        return stdout;
    }

    /**
     * Ecrit le flux brut d'un appel (une ligne JSON par evenement, deja separees par des
     * retours a la ligne par claude CLI) a la fin du fichier de capture, en creant le
     * dossier parent si besoin. Best-effort : un echec ne remonte jamais d'exception (voir
     * javadoc de call() a 7 arguments) - seul un warning est logue.
     */
    void appendCapture(Path captureLogPath, String rawStreamOutput) {
        try {
            if (captureLogPath.getParent() != null) {
                Files.createDirectories(captureLogPath.getParent());
            }
            String toWrite = rawStreamOutput.endsWith("\n") ? rawStreamOutput : rawStreamOutput + "\n";
            Files.writeString(captureLogPath, toWrite, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Echec de l'ecriture du log de conversation JSONL ({}) : {}", captureLogPath, e.getMessage());
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
