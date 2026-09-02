package fr.ses10doigts.agentvps.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration de l'invocation du CLI Claude Code (claude -p ...).
 * Voir roadmap-implementation.md, Phase 3, point 3, et Phase 1 (securite).
 */
@Data
@ConfigurationProperties(prefix = "agentvps.claude")
public class ClaudeCliProperties {

    /** Chemin absolu du binaire claude (depend de l'utilisateur systeme, voir Phase 0bis). */
    private String binaryPath = "/home/agentvps/.local/bin/claude";

    /** Delai maximum d'attente d'une reponse, avant destruction forcee du process. */
    private int timeoutSeconds = 240;

    /** Si active, un appel de test est effectue au demarrage de l'application (voir ClaudeCliSmokeTestRunner). */
    private boolean smokeTestEnabled = false;

    /** Prompt utilise pour l'appel de test au demarrage. */
    private String smokeTestPrompt = "Reponds uniquement avec le mot : pong";

    /**
     * Mode de permission passe a claude via --permission-mode (Phase 1 de la roadmap).
     * "dontAsk" refuse automatiquement tout ce qui n'est pas pre-autorise dans le
     * settings.json (voir settingsPath) - indispensable en headless, sans personne
     * pour repondre a une demande de confirmation. Vide/blank = flag non transmis
     * (comportement par defaut de claude, deconseille en production : sans mode
     * explicite, claude ne peut faire AUCUNE action sur le filesystem en headless,
     * voir le test reel du 28/08/2026).
     */
    private String permissionMode = "dontAsk";

    /**
     * Chemin du fichier settings.json (regles allow/ask/deny) passe via --settings.
     * Vide/blank = flag non transmis.
     */
    private String settingsPath = "/home/agentvps/.config/agentvps/claude-settings.json";

    /**
     * Fichier --settings utilise a la place de settingsPath pour un projet a droits
     * elargis (Project.elevated, voir ProjectService.ELEVATED_PROJECT_SLUG et
     * ChatService.sendMessage). Meme deny qu'en production (secrets ~/.ssh, ~/.claude,
     * ~/.ori toujours proteges, /etc et /root toujours en lecture seule, sudo/rm -rf
     * toujours refuses) - la difference tient au working directory du projet "system"
     * (remonte a la racine du workspace au lieu d'un sous-dossier isole, ce qui etend
     * la portee des regles Read/Write/Edit(**), deja relatives au cwd) et a quelques
     * commandes Bash de diagnostic supplementaires (ps, df, du, uname...). Voir la
     * memoire projet "god_mode_system_project" pour le detail de la conception :
     * accede volontairement PAS aux autres applications du VPS (CourseCrawler,
     * Instabot, CristalBot tournent sous l'utilisateur systeme oklm, home 700,
     * invisible pour agentvps quel que soit ce fichier) - ca reste une decision
     * separee (ACL/groupe Linux dedie, ou operations gateway SSH curatees).
     */
    private String elevatedSettingsPath = "/home/agentvps/.config/agentvps/claude-settings-system.json";

    /**
     * Coupe l'"auto memory" native de Claude Code (notes que claude redige de sa
     * propre initiative entre sessions dans ~/.claude/projects/<projet>/memory/,
     * fonctionnalite distincte de CLAUDE.md - voir <a href="https://code.claude.com/docs/en/memory">...</a>).
     * Decouverte le 28/08/2026 : cette memoire est bloquee par les regles deny sur
     * ~/.claude/** du settings.json (Phase 1 securite), ce qui fait perdre du temps/des
     * tours a claude qui tente d'y ecrire puis echoue. Plutot que d'ouvrir une exception
     * de permission, on desactive la fonctionnalite elle-meme via la variable
     * d'environnement CLAUDE_CODE_DISABLE_AUTO_MEMORY (voir ClaudeCliService), qui evite
     * meme que l'outil soit propose au modele. CLAUDE.md reste la memoire de chaque
     * projet ; les regles fixes (ex. "VPS de production, sois prudent") passent par
     * --append-system-prompt, pas par cette memoire. Actif par defaut.
     */
    private boolean disableAutoMemory = true;

    /**
     * Fournisseur utilise pour l'appel : ANTHROPIC (API native, defaut) ou OPENROUTER
     * (route via Ori Harness, voir openRouterBinaryPath/openRouterModel ci-dessous).
     * Ori a ete installe et valide de bout en bout sur le VPS fin aout 2026 (memoire
     * projet "ori_openrouter_integration") : "ori claude --model <id> -p ..." se
     * comporte comme "claude -p ..." (memes flags, meme schema JSON de sortie), seul
     * le modele qui repond change.
     */
    private ClaudeProvider provider = ClaudeProvider.ANTHROPIC;

    /**
     * Chemin absolu du binaire ori, utilise uniquement quand provider=OPENROUTER.
     * Installe sous l'utilisateur agentvps dans ~/.local/bin, hors du PATH par defaut
     * d'un ProcessBuilder/systemd (qui ne source pas .bashrc) - d'ou un chemin absolu,
     * meme raison que pour binaryPath.
     */
    private String openRouterBinaryPath = "/home/agentvps/.local/bin/ori";

    /**
     * Modele OpenRouter a utiliser (ex "openai/gpt-5"), passe en "--model <valeur>" a
     * "ori claude". Utilise uniquement quand provider=OPENROUTER ; vide/blank = flag
     * non transmis (ori utilise alors son modele par defaut).
     */
    private String openRouterModel;

    /**
     * Frequence (en nombre de messages @Chat traites sur une meme conversation) du
     * renforcement periodique (rappel appuye CLAUDE.md + permissions, injecte en prefixe
     * du message utilisateur par ChatService - PAS via --append-system-prompt, voir la
     * memoire projet "prompting_architecture"). Le premier message d'une conversation est
     * toujours renforce independamment de ce seuil. Valeur de depart proposee (29/08/2026),
     * a ajuster a l'usage reel.
     */
    private int reinforcementEveryMessages = 10;
}
