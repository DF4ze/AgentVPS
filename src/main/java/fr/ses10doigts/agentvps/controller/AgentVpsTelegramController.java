package fr.ses10doigts.agentvps.controller;

import fr.ses10doigts.agentvps.model.ClaudeCliResult;
import fr.ses10doigts.agentvps.model.Conversation;
import fr.ses10doigts.agentvps.model.Project;
import fr.ses10doigts.agentvps.model.ProjectStatus;
import fr.ses10doigts.agentvps.service.ClaudeCliException;
import fr.ses10doigts.agentvps.service.ClaudeCliService;
import fr.ses10doigts.agentvps.service.ProjectException;
import fr.ses10doigts.agentvps.service.ProjectOnboardingService;
import fr.ses10doigts.agentvps.service.ProjectService;
import fr.ses10doigts.telegrambots.model.TelegramUpdateContext;
import fr.ses10doigts.telegrambots.service.poller.handler.annot.Chat;
import fr.ses10doigts.telegrambots.service.poller.handler.annot.Command;
import fr.ses10doigts.telegrambots.service.poller.handler.annot.TelegramController;
import fr.ses10doigts.telegrambots.service.sender.TelegramSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Point d'entree Telegram d'AgentVPS (roadmap Phase 3, point 4) : commande /projet
 * (gestion des projets), commande /conv dediee (gestion des conversations du projet
 * actif) et @Chat (message libre transmis a "claude -p", avec ou sans --resume selon
 * la conversation courante du projet actif).
 *
 * Deux choix de conception a noter :
 *
 * 1. TelegramSender est injecte via ObjectProvider, pas directement. Le bean
 * TelegramSender (comme TelegramHandlerRegistry, voir TelegramBuiltinController dans
 * le module) n'existe que si telegram.enabled=true (TelegramAutoConfiguration). Ce
 * controleur est un @Component scanne par l'application quel que soit ce flag (voir
 * src/test/resources/application.yml : telegram.enabled=false expres pour le test de
 * contexte) ; une injection directe casserait donc le demarrage du contexte Spring en
 * test. ObjectProvider differe la resolution jusqu'au premier appel reel d'un handler,
 * qui ne se produit que si le dispatcher Telegram existe (donc si telegram est actif).
 *
 * 2. Tous les handlers renvoient void et envoient eux-memes leur reponse via
 * TelegramSender, plutot que de renvoyer une String/TelegramView. Le
 * TelegramUpdateDispatcher n'envoie RIEN a l'utilisateur si un handler leve une
 * exception (juste un log cote serveur) : toute la logique metier (ProjectException,
 * ClaudeCliException) est donc capturee ici et transformee en message explicite, sinon
 * un appel claude en echec resterait silencieux cote Telegram.
 */
@TelegramController
@RequiredArgsConstructor
@Slf4j
public class AgentVpsTelegramController {

    /** Nom du projet cree automatiquement si un message libre arrive sans qu'aucun projet n'existe encore. */
    static final String DEFAULT_PROJECT_NAME = "default";

    private final ProjectService projectService;
    private final ProjectOnboardingService onboardingService;
    private final ClaudeCliService claudeCliService;
    private final ObjectProvider<TelegramSender> telegramSenderProvider;

    private TelegramSender sender() {
        return telegramSenderProvider.getObject();
    }

    // ---------------------------------------------------------------- /projet

    @Command(value = "/projet", description = "Gérer les projets (list, new <nom>, delete, <nom>)")
    public void projet(TelegramUpdateContext context) {
        Long chatId = context.getChatId();
        List<String> args = context.getArgs();

        if (args.isEmpty()) {
            showActiveProject(chatId);
            return;
        }

        String sub = args.getFirst().toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> listProjects(chatId);
            case "new" -> createProject(chatId, joinFrom(args, 1));
            case "delete" -> deleteProject(chatId, joinFrom(args, 1));
            default -> switchProject(chatId, context.getCommandArgsRaw());
        }
    }

    private void showActiveProject(Long chatId) {
        Optional<Project> active = projectService.getActiveProject();
        if (active.isEmpty()) {
            sender().sendMessage(chatId, noActiveProjectHint());
            return;
        }
        sender().sendMessage(chatId, "Projet actif : " + active.get().getName());
    }

    private void listProjects(Long chatId) {
        List<Project> projects = projectService.listProjects();
        if (projects.isEmpty()) {
            sender().sendMessage(chatId, "Aucun projet pour l'instant. Utilise /projet new <nom> pour en créer un.");
            return;
        }

        String activeName = projectService.getActiveProject().map(Project::getName).orElse(null);
        StringBuilder sb = new StringBuilder("Projets :\n");
        for (Project p : projects) {
            boolean isActive = p.getName().equals(activeName);
            sb.append(isActive ? "> " : "  ").append(p.getName());
            if (isActive) {
                sb.append(" (actif)");
            } else if (p.getStatus() == ProjectStatus.ARCHIVED) {
                sb.append(" (archivé)");
            }
            sb.append('\n');
        }
        sender().sendMessage(chatId, sb.toString().trim());
    }

    private void createProject(Long chatId, String rawName) {
        if (rawName == null || rawName.isBlank()) {
            sender().sendMessage(chatId, "Usage : /projet new <nom>");
            return;
        }

        sender().sendTyping(chatId);
        try {
            ProjectOnboardingService.OnboardingResult result = onboardingService.createProjectAndStartOnboarding(rawName);
            sender().sendMessage(chatId,
                    "Projet '" + result.project().getName() + "' cree.\n\n" + result.firstClaudeMessage());
        } catch (ProjectException e) {
            sender().sendMessage(chatId, "Impossible de creer le projet : " + e.getMessage());
        } catch (ClaudeCliException e) {
            log.error("Echec de l'interview d'onboarding pour le nouveau projet '{}'", rawName, e);
            sender().sendMessage(chatId,
                    "Le projet a ete cree mais l'interview a echoue (" + e.getMessage() + "). "
                            + "Tu peux quand meme lui ecrire directement pour continuer.");
        }
    }

    private void deleteProject(Long chatId, String rawName) {
        String targetName = (rawName == null || rawName.isBlank())
                ? projectService.getActiveProject().map(Project::getName).orElse(null)
                : rawName;

        if (targetName == null) {
            sender().sendMessage(chatId, "Aucun projet actif a supprimer. Precise un nom : /projet delete <nom>.");
            return;
        }

        try {
            projectService.archiveProject(targetName);
            sender().sendMessage(chatId, "Projet '" + targetName + "' archive (reversible via /projet " + targetName + ").");
        } catch (ProjectException e) {
            sender().sendMessage(chatId, "Impossible d'archiver ce projet : " + e.getMessage());
        }
    }

    private void switchProject(Long chatId, String rawName) {
        if (rawName == null || rawName.isBlank()) {
            sender().sendMessage(chatId, "Usage : /projet <nom>");
            return;
        }
        try {
            Project project = projectService.switchProject(rawName);
            sender().sendMessage(chatId, "Projet actif : " + project.getName());
        } catch (ProjectException e) {
            sender().sendMessage(chatId, "Projet introuvable : " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ /conv

    @Command(value = "/conv", description = "Gérer les conversations du projet actif (list, new, <numero>)")
    public void conv(TelegramUpdateContext context) {
        Long chatId = context.getChatId();
        Optional<Project> activeOpt = projectService.getActiveProject();
        if (activeOpt.isEmpty()) {
            sender().sendMessage(chatId, noActiveProjectHint());
            return;
        }
        Project active = activeOpt.get();
        List<String> args = context.getArgs();

        if (args.isEmpty()) {
            showCurrentConversation(chatId, active);
            return;
        }

        String sub = args.getFirst().toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> listConversations(chatId, active);
            case "new" -> startNewConversation(chatId, active);
            default -> switchConversation(chatId, active, args.getFirst());
        }
    }

    private void showCurrentConversation(Long chatId, Project active) {
        Optional<Conversation> current = projectService.getCurrentConversation(active.getName());
        if (current.isEmpty()) {
            sender().sendMessage(chatId,
                    "Projet '" + active.getName() + "' : pas de conversation en cours (le prochain message en démarrera une nouvelle).");
            return;
        }
        sender().sendMessage(chatId, "Conversation en cours : " + describe(current.get()));
    }

    private void listConversations(Long chatId, Project active) {
        List<Conversation> conversations = projectService.listConversations(active.getName());
        if (conversations.isEmpty()) {
            sender().sendMessage(chatId, "Aucune conversation pour l'instant. Ecris un message pour en démarrer une.");
            return;
        }

        String currentSessionId = active.getCurrentSessionId();
        StringBuilder sb = new StringBuilder("Conversations de '" + active.getName() + "' :\n");
        for (int i = 0; i < conversations.size(); i++) {
            Conversation c = conversations.get(i);
            boolean isCurrent = c.getSessionId().equals(currentSessionId);
            sb.append(isCurrent ? "> " : "  ")
                    .append(i + 1).append(". ")
                    .append(describe(c))
                    .append('\n');
        }
        sender().sendMessage(chatId, sb.toString().trim());
    }

    private void startNewConversation(Long chatId, Project active) {
        projectService.startNewConversation(active.getName());
        sender().sendMessage(chatId,
                "Nouvelle conversation prete pour '" + active.getName() + "' : le prochain message en demarrera une nouvelle.");
    }

    private void switchConversation(Long chatId, Project active, String rawNumber) {
        int number;
        try {
            number = Integer.parseInt(rawNumber.trim());
        } catch (NumberFormatException e) {
            sender().sendMessage(chatId, "Argument invalide : attendu un numero de conversation (voir /conv list).");
            return;
        }
        try {
            Conversation conversation = projectService.switchConversation(active.getName(), number);
            sender().sendMessage(chatId, "Conversation courante : " + describe(conversation));
        } catch (ProjectException e) {
            sender().sendMessage(chatId, e.getMessage());
        }
    }

    private String describe(Conversation c) {
        String label = (c.getLabel() == null || c.getLabel().isBlank()) ? "(sans libelle)" : c.getLabel();
        String shortSessionId = c.getSessionId().length() > 8 ? c.getSessionId().substring(0, 8) : c.getSessionId();
        String lastUsed = c.getLastUsedAt() != null ? c.getLastUsedAt().truncatedTo(ChronoUnit.MINUTES).toString() : "?";
        return label + " (" + shortSessionId + "..., " + lastUsed + ")";
    }

    // ------------------------------------------------------------------ @Chat

    @Chat
    public void chat(TelegramUpdateContext context) {
        String text = context.getText();
        Long chatId = context.getChatId();
        if (text == null || text.isBlank() || chatId == null) {
            return;
        }

        Optional<Project> activeOpt = projectService.getActiveProject();
        if (activeOpt.isEmpty()) {
            handleChatWithoutActiveProject(chatId);
            return;
        }

        Project active = activeOpt.get();
        String sessionId = active.getCurrentSessionId();

        sender().sendTyping(chatId);
        try {
            ClaudeCliResult result = claudeCliService.call(text, sessionId, Path.of(active.getWorkingDirectory()));
            if (sessionId == null) {
                projectService.recordConversationStart(active.getName(), result.getSessionId(), null);
            } else {
                projectService.touchConversation(active.getName(), sessionId);
            }
            sender().sendMessage(chatId, result.getResult());
        } catch (ClaudeCliException e) {
            log.error("Echec de l'appel claude pour le projet '{}'", active.getName(), e);
            sender().sendMessage(chatId, "Erreur lors de l'appel a Claude : " + e.getMessage());
        }
    }

    /**
     * Aucun projet actif : si des projets existent deja (juste desactives/archives),
     * on demande a l'utilisateur de choisir plutot que de creer un projet "default" qui
     * masquerait son historique. Si aucun projet n'existe encore, un projet "default"
     * est cree automatiquement (avec interview CLAUDE.md) pour ne pas bloquer un tout
     * premier usage avant meme un /projet new explicite.
     */
    private void handleChatWithoutActiveProject(Long chatId) {
        if (!projectService.listProjects().isEmpty()) {
            sender().sendMessage(chatId, noActiveProjectHint());
            return;
        }

        sender().sendTyping(chatId);
        try {
            ProjectOnboardingService.OnboardingResult result =
                    onboardingService.createProjectAndStartOnboarding(DEFAULT_PROJECT_NAME);
            sender().sendMessage(chatId,
                    "Aucun projet n'existait encore : projet '" + result.project().getName() + "' cree automatiquement.\n\n"
                            + result.firstClaudeMessage());
        } catch (ProjectException | ClaudeCliException e) {
            log.error("Echec de la creation automatique du projet par defaut", e);
            sender().sendMessage(chatId, "Erreur lors de la creation automatique du projet : " + e.getMessage());
        }
    }

    private static String noActiveProjectHint() {
        return "Aucun projet actif. Utilise /projet list pour voir les projets existants, "
                + "ou /projet <nom> pour en choisir un (ou /projet new <nom> pour en creer un).";
    }

    private static String joinFrom(List<String> args, int fromIndex) {
        if (args.size() <= fromIndex) {
            return null;
        }
        return String.join(" ", args.subList(fromIndex, args.size()));
    }
}
