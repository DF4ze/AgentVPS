package fr.ses10doigts.agentvps.controller;

import fr.ses10doigts.agentvps.model.ClaudeCliResult;
import fr.ses10doigts.agentvps.model.Conversation;
import fr.ses10doigts.agentvps.model.NotificationPolicy;
import fr.ses10doigts.agentvps.model.Project;
import fr.ses10doigts.agentvps.model.ProjectStatus;
import fr.ses10doigts.agentvps.model.RecurringTask;
import fr.ses10doigts.agentvps.model.RecurringTaskExecutionMode;
import fr.ses10doigts.agentvps.model.RecurringTaskRunOutcome;
import fr.ses10doigts.agentvps.model.RecurringTaskStatus;
import fr.ses10doigts.agentvps.model.RecurringTaskTriggerType;
import fr.ses10doigts.agentvps.service.ChatService;
import fr.ses10doigts.agentvps.service.ClaudeCliException;
import fr.ses10doigts.agentvps.service.ProjectException;
import fr.ses10doigts.agentvps.service.ProjectOnboardingService;
import fr.ses10doigts.agentvps.service.ProjectService;
import fr.ses10doigts.agentvps.service.ProjectThreadService;
import fr.ses10doigts.agentvps.service.RecurringTaskCreationWizard;
import fr.ses10doigts.agentvps.service.RecurringTaskException;
import fr.ses10doigts.agentvps.service.RecurringTaskManager;
import fr.ses10doigts.agentvps.service.RecurringTaskService;
import fr.ses10doigts.telegrambots.model.TelegramButtonView;
import fr.ses10doigts.telegrambots.model.TelegramMessageReference;
import fr.ses10doigts.telegrambots.model.TelegramUpdateContext;
import fr.ses10doigts.telegrambots.model.TelegramView;
import fr.ses10doigts.telegrambots.service.poller.handler.annot.CallbackQuery;
import fr.ses10doigts.telegrambots.service.poller.handler.annot.Chat;
import fr.ses10doigts.telegrambots.service.poller.handler.annot.Command;
import fr.ses10doigts.telegrambots.service.poller.handler.annot.TelegramController;
import fr.ses10doigts.telegrambots.service.sender.TelegramMarkdownUtils;
import fr.ses10doigts.telegrambots.service.sender.TelegramSender;
import fr.ses10doigts.telegrambots.service.sender.TelegramSenderRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Point d'entree Telegram d'AgentVPS (roadmap Phase 3, point 4) : commande /projet
 * (gestion des projets), commande /conv dediee (gestion des conversations du projet
 * actif) et @Chat (message libre transmis a "claude -p", avec ou sans --resume selon
 * la conversation courante du projet actif).
 *
 * Quatre choix de conception a noter :
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
 *
 * 3. Le handler @Chat envoie d'abord un message place-holder (CHAT_PROCESSING_PLACEHOLDER)
 * avant l'appel a claude -p, puis l'ecrase (TelegramSender.editMessage) avec la vraie
 * reponse (ou le message d'erreur) une fois l'appel termine - un appel peut prendre jusqu'a
 * 120s et le seul autre signal cote Telegram est le "typing..." ephemere de sendTyping.
 * Ca donne a Clem un ACK immediat que le bot a bien recu le message et est UP.
 *
 * 4. Le "typing..." de Telegram s'efface tout seul au bout de ~5s : chat() le relance donc
 * en tache de fond (startTypingHeartbeat) pendant toute la duree de l'appel claude, pour un
 * signal visuel continu. Ce heartbeat tourne sur un thread dedie (pas le thread Telegram
 * courant, bloque par l'appel claude) et doit donc passer par TelegramSenderRegistry.
 * getDefaultBotSender() plutot que sender() : sender() renvoie un ContextAwareTelegramSender
 * qui resout via un ThreadLocal (CurrentTelegramBotContext) lie uniquement au thread de
 * traitement de l'update Telegram entrant - meme piege deja rencontre et corrige dans
 * RecurringTaskNotifier (memoire projet "phase7_scheduler_implementation").
 */
@TelegramController
@RequiredArgsConstructor
@Slf4j
public class AgentVpsTelegramController {

    /** Nom du projet cree automatiquement si un message libre arrive sans qu'aucun projet n'existe encore. */
    static final String DEFAULT_PROJECT_NAME = "default";

    /**
     * Place-holder envoye immediatement a la reception d'un message libre, avant l'appel a
     * claude -p (potentiellement long, jusqu'a 120s) - permet a Clem de savoir que le bot est
     * bien UP des la reception du message, sans attendre la vraie reponse. Une fois celle-ci
     * disponible (ou en cas d'echec), ce message est ecrase via TelegramSender.editMessage
     * (voir chat() ci-dessous), jamais renvoye comme un nouveau message.
     */
    static final String CHAT_PROCESSING_PLACEHOLDER = "Message recu, je m'en occupe...";

    /** Intervalle de relance du heartbeat "typing..." pendant un appel claude (voir point 4 du javadoc). */
    private static final long TYPING_HEARTBEAT_INTERVAL_SECONDS = 4;

    /** Format d'affichage d'une date/heure de tache ponctuelle (voir showTask/listTasks). */
    private static final DateTimeFormatter SCHEDULED_AT_DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

    private final ProjectService projectService;
    private final ProjectOnboardingService onboardingService;
    private final ChatService chatService;
    private final RecurringTaskService recurringTaskService;
    private final RecurringTaskManager recurringTaskManager;
    private final RecurringTaskCreationWizard recurringTaskWizard;
    private final ProjectThreadService projectThreadService;
    private final ObjectProvider<TelegramSender> telegramSenderProvider;
    private final ObjectProvider<TelegramSenderRegistry> telegramSenderRegistryProvider;

    /**
     * Suppressions de projet en attente de confirmation (feature Threads = projets du
     * 02/09/2026, voir requestProjectDeletion/confirmProjectDeletion/cancelProjectDeletion) :
     * cle = chatId + messageThreadId (voir pendingDeletionKey), valeur = nom (slug) du
     * projet vise. @CallbackQuery ne route que sur une valeur EXACTE de callbackData (pas
     * de pattern/wildcard, voir TelegramHandlerRegistry) : impossible d'y encoder le nom du
     * projet directement, d'ou cet etat cote serveur, meme pattern que
     * RecurringTaskCreationWizard pour /tache new. ConcurrentHashMap : les updates Telegram
     * peuvent etre traites sur des threads differents (long-polling, voir
     * TelegramUpdateDispatcher).
     */
    private final Map<String, String> pendingProjectDeletions = new ConcurrentHashMap<>();

    private TelegramSender sender() {
        return telegramSenderProvider.getObject();
    }

    /**
     * Demarre le heartbeat "typing..." (voir point 4 du javadoc de la classe) : a annuler dans
     * tous les cas (finally) une fois l'appel claude termine, succes ou echec.
     */
    private ScheduledExecutorService startTypingHeartbeat(Long chatId, Integer messageThreadId) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chat-typing-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(() -> {
            try {
                telegramSenderRegistryProvider.getObject().getDefaultBotSender().sendTyping(chatId, messageThreadId);
            } catch (Exception e) {
                log.warn("Echec du heartbeat 'typing...' pour chatId={}", chatId, e);
            }
        }, TYPING_HEARTBEAT_INTERVAL_SECONDS, TYPING_HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        return executor;
    }

    // ------------------------------------------------------------- en-tete

    /**
     * Icone d'en-tete (voir {@link #header(Project)}) : purement decorative, ne fait
     * pas partie du Markdown a echapper.
     */
    private static final String HEADER_ICON = "📁"; // 📁

    /**
     * Construit l'en-tete "projet / conversation" (une ligne, gras sur le nom du projet,
     * italique sur la conversation courante - demande de Clem du 02/09/2026 pour se
     * reperer immediatement en revenant sur une discussion Telegram). Les deux parties
     * dynamiques sont echappees via {@link TelegramMarkdownUtils#escapeMarkdownV2}, les
     * marqueurs *gras_italique_ eux-memes ne le sont pas : cette chaine n'est donc
     * valide QUE combinee avec {@link TelegramSender#sendFormattedMessage} (jamais avec
     * sendMarkdownMessage, qui echapperait aussi ces marqueurs et detruirait le formatage).
     */
    private String header(Project project) {
        return HEADER_ICON + " *" + TelegramMarkdownUtils.escapeMarkdownV2(project.getName()) + "* · _"
                + TelegramMarkdownUtils.escapeMarkdownV2(currentConversationLabel(project)) + "_";
    }

    /**
     * Prefixe {@code body} (echappe pour un envoi MarkdownV2 sans risque, quel que soit
     * son contenu - typiquement la reponse libre de Claude) avec l'en-tete de
     * {@link #header(Project)}. A envoyer uniquement via sendFormattedMessage/
     * editFormattedMessage (voir header(Project)).
     */
    private String withHeader(Project project, String body) {
        return header(project) + "\n" + TelegramMarkdownUtils.escapeMarkdownV2(body);
    }

    /**
     * Court libelle de la conversation courante du projet pour l'en-tete : le libelle
     * s'il existe (ex. "Mise en place initiale"), sinon son numero dans l'historique
     * (coherent avec la numerotation de /conv list, 1 = la plus ancienne), sinon un
     * texte generique si la conversation n'est pas (encore) retrouvee dans l'historique.
     */
    private static String currentConversationLabel(Project project) {
        String sessionId = project.getCurrentSessionId();
        if (sessionId == null) {
            return "nouvelle conversation";
        }
        List<Conversation> conversations = project.getConversations();
        for (int i = 0; i < conversations.size(); i++) {
            Conversation c = conversations.get(i);
            if (sessionId.equals(c.getSessionId())) {
                String label = c.getLabel();
                return (label != null && !label.isBlank()) ? label : "conv #" + (i + 1);
            }
        }
        return "conversation en cours";
    }

    // ---------------------------------------------------------------- /projet

    @Command(value = "/projet",
            description = "Gerer les projets (list, new <nom>, delete, <nom> - reduit a new/delete si les Threads sont actifs)")
    public void projet(TelegramUpdateContext context) {
        Long chatId = context.getChatId();
        MDC.put("chatId", String.valueOf(chatId));
        try {
            boolean threadsActive = projectThreadService.isForumChat(chatId);
            List<String> args = context.getArgs();

            if (args.isEmpty()) {
                if (threadsActive) {
                    sender().sendMessage(chatId, projetDisabledInForumMessage());
                    return;
                }
                showActiveProject(chatId);
                return;
            }

            String sub = args.getFirst().toLowerCase(Locale.ROOT);
            switch (sub) {
                case "new" -> createProject(chatId, joinFrom(args, 1));
                case "delete" -> requestProjectDeletion(context, joinFrom(args, 1));
                case "list" -> {
                    if (threadsActive) {
                        sender().sendMessage(chatId, projetDisabledInForumMessage());
                    } else {
                        listProjects(chatId);
                    }
                }
                default -> {
                    if (threadsActive) {
                        sender().sendMessage(chatId, projetDisabledInForumMessage());
                    } else {
                        switchProject(chatId, context.getCommandArgsRaw());
                    }
                }
            }
        } finally {
            MDC.remove("chatId");
        }
    }

    /**
     * Message renvoye pour toute sous-commande /projet autre que new/delete quand les
     * Threads sont actifs (voir projet() ci-dessus) : list/&lt;nom&gt; n'ont plus de sens
     * des que chaque projet a son propre Thread (routage automatique par
     * messageThreadId, voir resolveContextProject) - demande explicite de Clem du
     * 02/09/2026.
     */
    private static String projetDisabledInForumMessage() {
        return "Cette sous-commande est desactivee dans ce groupe : chaque projet a son propre Thread. "
                + "Ecris directement dans le Thread du projet concerne, ou utilise /projet new <nom> "
                + "/ /projet delete pour creer ou supprimer un projet.";
    }

    /**
     * Affiche le projet actif et, s'il a deja au moins une conversation, leur liste
     * complete avec un tag sur la courante (demande de Clem du 02/09/2026 : vue "ou j'en
     * suis" complete en une seule commande, sans avoir a enchainer avec /conv list).
     */
    private void showActiveProject(Long chatId) {
        Optional<Project> activeOpt = projectService.getActiveProject();
        if (activeOpt.isEmpty()) {
            sender().sendMessage(chatId, noActiveProjectHint(chatId));
            return;
        }

        Project active = activeOpt.get();
        List<Conversation> conversations = projectService.listConversations(active.getName());
        StringBuilder sb = new StringBuilder("Projet actif : " + active.getName());
        if (!conversations.isEmpty()) {
            sb.append("\n\nConversations :\n");
            appendConversationList(sb, conversations, active.getCurrentSessionId());
        }
        sender().sendMessage(chatId, sb.toString().trim());
    }

    private void listProjects(Long chatId) {
        List<Project> projects = projectService.listProjects().stream()
                .filter(this::isVisibleInTelegram)
                .toList();
        if (projects.isEmpty()) {
            sender().sendMessage(chatId, "Aucun projet pour l'instant. Utilise /projet new <nom> pour en creer un.");
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
                sb.append(" (archive)");
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

        MDC.put("project", rawName);
        try {
            sender().sendTyping(chatId);
            try {
                ProjectOnboardingService.OnboardingResult result = onboardingService.createProjectAndStartOnboarding(rawName);
                Project created = result.project();
                sender().sendMessage(chatId,
                        "Projet '" + created.getName() + "' cree." + attachForumTopicNote(created)
                                + "\n\n" + result.firstClaudeMessage());
            } catch (ProjectException e) {
                sender().sendMessage(chatId, "Impossible de creer le projet : " + e.getMessage());
            } catch (ClaudeCliException e) {
                log.error("Echec de l'interview d'onboarding pour le nouveau projet '{}'", rawName, e);
                sender().sendMessage(chatId,
                        "Le projet a ete cree mais l'interview a echoue (" + e.getMessage() + "). "
                                + "Tu peux quand meme lui ecrire directement pour continuer.");
            }
        } finally {
            MDC.remove("project");
        }
    }

    /**
     * Cree le Thread Telegram du projet si les Threads sont configures (voir
     * ProjectThreadService), quel que soit le chat depuis lequel /projet new a ete
     * invoque (DM ou groupe) - demande de Clem : "A chaque creation de projet, sera
     * automatiquement cree un Thread". Chaine vide si les Threads ne sont pas
     * configures (comportement legacy inchange) ; message explicite en cas d'echec cote
     * Telegram (droits manquants, groupe pas configure en forum...) plutot que de le
     * passer sous silence.
     */
    private String attachForumTopicNote(Project project) {
        if (!projectThreadService.isEnabled()) {
            return "";
        }
        return projectThreadService.createTopicForProject(project).isPresent()
                ? "\nThread Telegram cree pour ce projet."
                : "\n(Impossible de creer le Thread Telegram pour ce projet - voir les logs.)";
    }

    /**
     * Etape 1 de la suppression d'un projet (feature Threads = projets du 02/09/2026,
     * decision Clem) : /projet delete ne supprime plus rien directement, il demande
     * confirmation via un clavier inline (voir confirmProjectDeletion/
     * cancelProjectDeletion ci-dessous) - suppression reelle et irreversible (fichiers,
     * historique de conversations, Thread Telegram), contrairement au comportement
     * precedent (archiveProject, reversible - conserve dans ProjectService mais plus
     * appele depuis ce controller). Sans nom explicite, cible le projet du Thread
     * courant (voir resolveContextProject) si on est dans un Thread, sinon le projet
     * actif global.
     */
    private void requestProjectDeletion(TelegramUpdateContext context, String rawName) {
        Long chatId = context.getChatId();
        Project target;
        try {
            target = (rawName == null || rawName.isBlank())
                    ? resolveContextProject(context).orElse(null)
                    : projectService.getProject(rawName);
        } catch (ProjectException e) {
            sender().sendMessage(chatId, "Projet introuvable : " + e.getMessage());
            return;
        }

        if (target == null) {
            sender().sendMessage(chatId, "Aucun projet a supprimer. Precise un nom : /projet delete <nom>.");
            return;
        }

        pendingProjectDeletions.put(pendingDeletionKey(context), target.getName());

        String threadWarning = target.getTelegramThreadId() != null ? " et son Thread Telegram" : "";
        TelegramView confirmation = TelegramView.builder()
                .text("Supprimer le projet '" + target.getName() + "' ? Action IRREVERSIBLE : tout son "
                        + "historique de conversations, les fichiers du projet" + threadWarning
                        + " seront definitivement supprimes.")
                .buttons(List.of(List.of(
                        new TelegramButtonView("Oui, supprimer", "projet:delete:confirm"),
                        new TelegramButtonView("Annuler", "projet:delete:cancel")
                )))
                .build();
        sender().sendView(chatId, confirmation);
    }

    /**
     * Etape 2 (confirmation positive) de requestProjectDeletion ci-dessus : supprime
     * reellement le projet (ProjectService.deleteProjectPermanently) puis son Thread
     * Telegram s'il en avait un (ProjectThreadService.deleteTopic, best-effort).
     */
    @CallbackQuery("projet:delete:confirm")
    public void confirmProjectDeletion(TelegramUpdateContext context) {
        Long chatId = context.getChatId();
        String targetName = pendingProjectDeletions.remove(pendingDeletionKey(context));
        if (targetName == null) {
            sender().sendMessage(chatId, "Rien a confirmer (la demande a peut-etre expire). Relance /projet delete.");
            return;
        }

        try {
            Integer threadId = projectService.getProject(targetName).getTelegramThreadId();
            projectService.deleteProjectPermanently(targetName);
            projectThreadService.deleteTopic(threadId);

            // Le Thread courant vient potentiellement d'etre supprime a l'instant : la
            // reponse finale part explicitement hors Thread (sujet "General"), sender()
            // etant sinon lie au Thread du message d'origine (CurrentTelegramThreadContext,
            // voir TelegramUpdateDispatcher) - un envoi cible sur ce Thread echouerait.
            sender().sendMessage(chatId, (Integer) null,
                    "Projet '" + targetName + "' supprime definitivement."
                            + (threadId != null ? " Thread Telegram supprime." : ""));
        } catch (ProjectException e) {
            sender().sendMessage(chatId, "Impossible de supprimer ce projet : " + e.getMessage());
        }
    }

    /** Etape 2 (annulation) de requestProjectDeletion ci-dessus. */
    @CallbackQuery("projet:delete:cancel")
    public void cancelProjectDeletion(TelegramUpdateContext context) {
        pendingProjectDeletions.remove(pendingDeletionKey(context));
        sender().sendMessage(context.getChatId(), "Suppression annulee.");
    }

    /** Cle de pendingProjectDeletions : voir le javadoc du champ pour le detail. */
    private static String pendingDeletionKey(TelegramUpdateContext context) {
        Integer threadId = context.getMessageThreadId();
        return context.getChatId() + ":" + (threadId != null ? threadId : "general");
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

    // ---------------------------------------------------------------- /projets init

    @Command(value = "/projets",
            description = "Creer les Threads Telegram manquants pour les projets existants (init)")
    public void projets(TelegramUpdateContext context) {
        Long chatId = context.getChatId();
        MDC.put("chatId", String.valueOf(chatId));
        try {
            List<String> args = context.getArgs();
            if (args.size() != 1 || !"init".equalsIgnoreCase(args.getFirst())) {
                sender().sendMessage(chatId, "Usage : /projets init");
                return;
            }
            initProjectThreads(chatId);
        } finally {
            MDC.remove("chatId");
        }
    }

    /**
     * /projets init (feature Threads = projets du 02/09/2026, demande explicite de
     * Clem) : cree le Thread Telegram manquant de chaque projet existant. Pour un
     * projet qui a deja un messageThreadId enregistre, verifie d'abord qu'il existe
     * toujours reellement cote Telegram (ProjectThreadService.topicStillExists) avant
     * de le considerer comme "deja fait" - un sujet peut avoir ete supprime
     * manuellement cote Telegram sans que AgentVPS ne le sache, auquel cas il est
     * recree.
     */
    private void initProjectThreads(Long chatId) {
        if (!projectThreadService.isEnabled()) {
            sender().sendMessage(chatId,
                    "Threads non configures (agentvps.telegram.forum-chat-id manquant) : rien a faire.");
            return;
        }

        sender().sendTyping(chatId);
        int created = 0;
        int recreated = 0;
        int kept = 0;
        int failed = 0;

        for (Project project : projectService.listProjects()) {
            if (!isVisibleInTelegram(project)) {
                continue;
            }
            Integer existingThreadId = project.getTelegramThreadId();
            if (existingThreadId != null && projectThreadService.topicStillExists(existingThreadId)) {
                kept++;
                continue;
            }

            boolean wasRecreate = existingThreadId != null;
            if (projectThreadService.createTopicForProject(project).isPresent()) {
                if (wasRecreate) {
                    recreated++;
                } else {
                    created++;
                }
            } else {
                failed++;
            }
        }

        StringBuilder sb = new StringBuilder("Initialisation des Threads terminee.\n");
        sb.append("Crees : ").append(created).append('\n');
        sb.append("Recrees (Thread manquant cote Telegram) : ").append(recreated).append('\n');
        sb.append("Deja en place : ").append(kept).append('\n');
        if (failed > 0) {
            sb.append("Echecs : ").append(failed).append(" (voir les logs)\n");
        }
        sender().sendMessage(chatId, sb.toString().trim());
    }

    /**
     * Resout le projet concerne par ce message (feature Threads = projets du
     * 02/09/2026) : dans le groupe configure (voir ProjectThreadService.isForumChat),
     * le Thread dans lequel le message a ete recu determine directement le projet
     * (ProjectService.findProjectByThreadId, y compris vide si le message vient du
     * sujet "General", sans Thread), independamment de toute notion de "projet actif" ;
     * partout ailleurs (DM, autre groupe...), comportement legacy inchange : le projet
     * actif global (ProjectService.getActiveProject()). Utilise par /conv, @Chat et
     * /projet delete sans nom explicite.
     */
    private Optional<Project> resolveContextProject(TelegramUpdateContext context) {
        if (projectThreadService.isForumChat(context.getChatId())) {
            return projectService.findProjectByThreadId(context.getMessageThreadId())
                    .filter(this::isVisibleInTelegram);
        }
        return projectService.getActiveProject();
    }

    private boolean isVisibleInTelegram(Project project) {
        return !ProjectService.ELEVATED_PROJECT_SLUG.equals(project.getName())
                || projectThreadService.isVisibleInTelegram(project);
    }

    // ------------------------------------------------------------------ /conv

    @Command(value = "/conv", description = "Gerer les conversations du projet actif (list, new, <numero>)")
    public void conv(TelegramUpdateContext context) {
        Long chatId = context.getChatId();
        MDC.put("chatId", String.valueOf(chatId));
        try {
            Optional<Project> activeOpt = resolveContextProject(context);
            if (activeOpt.isEmpty()) {
                sender().sendMessage(chatId, noActiveProjectHint(chatId));
                return;
            }
            Project active = activeOpt.get();
            MDC.put("project", active.getName());
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
        } finally {
            MDC.remove("project");
            MDC.remove("chatId");
        }
    }

    private void showCurrentConversation(Long chatId, Project active) {
        Optional<Conversation> current = projectService.getCurrentConversation(active.getName());
        String body = current.isEmpty()
                ? "Projet '" + active.getName() + "' : pas de conversation en cours (le prochain message en demarrera une nouvelle)."
                : "Conversation en cours : " + describe(current.get());
        sender().sendFormattedMessage(chatId, withHeader(active, body));
    }

    private void listConversations(Long chatId, Project active) {
        List<Conversation> conversations = projectService.listConversations(active.getName());
        if (conversations.isEmpty()) {
            sender().sendFormattedMessage(chatId,
                    withHeader(active, "Aucune conversation pour l'instant. Ecris un message pour en demarrer une."));
            return;
        }

        StringBuilder sb = new StringBuilder("Conversations de '" + active.getName() + "' :\n");
        appendConversationList(sb, conversations, active.getCurrentSessionId());
        sender().sendFormattedMessage(chatId, withHeader(active, sb.toString().trim()));
    }

    /**
     * Rend la liste numerotee de conversations (utilisee par /conv list et par /projet
     * seul, voir showActiveProject) : "&gt; " + numero sur la conversation courante,
     * "  " + numero sinon, 1 = la plus ancienne (coherent avec /conv &lt;numero&gt;).
     */
    private void appendConversationList(StringBuilder sb, List<Conversation> conversations, String currentSessionId) {
        for (int i = 0; i < conversations.size(); i++) {
            Conversation c = conversations.get(i);
            boolean isCurrent = c.getSessionId().equals(currentSessionId);
            sb.append(isCurrent ? "> " : "  ")
                    .append(i + 1).append(". ")
                    .append(describe(c))
                    .append('\n');
        }
    }

    private void startNewConversation(Long chatId, Project active) {
        projectService.startNewConversation(active.getName());
        sender().sendFormattedMessage(chatId, withHeader(active,
                "Nouvelle conversation prete pour '" + active.getName() + "' : le prochain message en demarrera une nouvelle."));
    }

    private void switchConversation(Long chatId, Project active, String rawNumber) {
        int number;
        try {
            number = Integer.parseInt(rawNumber.trim());
        } catch (NumberFormatException e) {
            sender().sendFormattedMessage(chatId,
                    withHeader(active, "Argument invalide : attendu un numero de conversation (voir /conv list)."));
            return;
        }
        try {
            Conversation conversation = projectService.switchConversation(active.getName(), number);
            sender().sendFormattedMessage(chatId, withHeader(active, "Conversation courante : " + describe(conversation)));
        } catch (ProjectException e) {
            sender().sendFormattedMessage(chatId, withHeader(active, e.getMessage()));
        }
    }

    private String describe(Conversation c) {
        String label = (c.getLabel() == null || c.getLabel().isBlank()) ? "(sans libelle)" : c.getLabel();
        String shortSessionId = c.getSessionId().length() > 8 ? c.getSessionId().substring(0, 8) : c.getSessionId();
        String lastUsed = c.getLastUsedAt() != null ? c.getLastUsedAt().truncatedTo(ChronoUnit.MINUTES).toString() : "?";
        return label + " (" + shortSessionId + "..., " + lastUsed + ")";
    }

    // ----------------------------------------------------------------- /tache

    @Command(value = "/tache", description = "Gerer les taches recurrentes (list, show <nom>, new ..., enable/disable/delete/run <nom>)")
    public void tache(TelegramUpdateContext context) {
        Long chatId = context.getChatId();
        MDC.put("chatId", String.valueOf(chatId));
        try {
            List<String> args = context.getArgs();

            if (args.isEmpty()) {
                listTasks(chatId);
                return;
            }

            String sub = args.getFirst().toLowerCase(Locale.ROOT);
            switch (sub) {
                case "list" -> listTasks(chatId);
                case "show" -> showTask(chatId, joinFrom(args, 1));
                case "new" -> startTaskWizard(chatId);
                case "enable" -> enableTask(chatId, joinFrom(args, 1));
                case "disable" -> disableTask(chatId, joinFrom(args, 1));
                case "delete" -> deleteTask(chatId, joinFrom(args, 1));
                case "run" -> runTask(chatId, joinFrom(args, 1));
                default -> sender().sendMessage(chatId,
                        "Sous-commande inconnue. Utilise /tache list, show <nom>, new ..., enable/disable/delete/run <nom>.");
            }
        } finally {
            MDC.remove("chatId");
        }
    }

    private void listTasks(Long chatId) {
        List<RecurringTask> tasks = recurringTaskService.listTasks();
        if (tasks.isEmpty()) {
            sender().sendMessage(chatId, "Aucune tache recurrente pour l'instant. Utilise /tache new pour en creer une.");
            return;
        }

        StringBuilder sb = new StringBuilder("Taches recurrentes :\n");
        for (RecurringTask task : tasks) {
            boolean disabled = task.getStatus() == RecurringTaskStatus.DISABLED;
            sb.append(disabled ? "  " : "> ").append(task.getName());
            if (task.getTriggerType() == RecurringTaskTriggerType.ONE_TIME) {
                sb.append(" [ponctuelle]");
            }
            if (task.getExecutionMode() == RecurringTaskExecutionMode.AGENT_MISSION) {
                sb.append(" [mission]");
            }
            if (disabled) {
                sb.append(" (desactivee)");
            }
            sb.append(" - ").append(task.getLastRunStatus() != null
                    ? "dernier run : " + task.getLastRunStatus()
                    : "jamais execute");
            sb.append('\n');
        }
        sender().sendMessage(chatId, sb.toString().trim());
    }

    private void showTask(Long chatId, String rawName) {
        if (rawName == null || rawName.isBlank()) {
            sender().sendMessage(chatId, "Usage : /tache show <nom>");
            return;
        }
        try {
            RecurringTask task = recurringTaskService.getTask(rawName);
            StringBuilder sb = new StringBuilder();
            sb.append("Tache '").append(task.getName()).append("'\n");
            sb.append("Projet : ").append(task.getProjectName()).append('\n');
            if (task.getExecutionMode() == RecurringTaskExecutionMode.AGENT_MISSION) {
                sb.append("Mode : mission agent\n");
                sb.append("Mission : ").append(task.getMissionPrompt()).append('\n');
            } else {
                sb.append("Commande : ").append(task.getCommand()).append('\n');
            }
            if (task.getTriggerType() == RecurringTaskTriggerType.ONE_TIME) {
                sb.append("Type : ponctuelle (une seule fois)\n");
                sb.append("Prevue le : ").append(task.getScheduledAt() != null
                        ? SCHEDULED_AT_DISPLAY_FORMAT.format(task.getScheduledAt())
                        : "?").append('\n');
            } else {
                sb.append("Cron : ").append(task.getCronExpression()).append('\n');
            }
            sb.append("Statut : ").append(task.getStatus() == RecurringTaskStatus.ACTIVE ? "active" : "desactivee").append('\n');
            sb.append("Notification : ").append(describePolicy(task.getNotificationPolicy())).append('\n');
            if (task.getDescription() != null && !task.getDescription().isBlank()) {
                sb.append("Description : ").append(task.getDescription()).append('\n');
            }
            sb.append('\n');
            if (task.getLastRunAt() == null) {
                sb.append("Aucun run pour l'instant.");
            } else {
                sb.append("Dernier run (").append(task.getLastRunAt().truncatedTo(ChronoUnit.MINUTES)).append(") : ")
                        .append(task.getLastRunStatus());
                if (task.getLastExitCode() != null) {
                    sb.append(" (code ").append(task.getLastExitCode()).append(')');
                }
                String detail = task.getLastErrorMessage() != null ? task.getLastErrorMessage() : task.getLastOutputSummary();
                if (detail != null && !detail.isBlank()) {
                    sb.append('\n').append(detail);
                }
            }
            sender().sendMessage(chatId, sb.toString());
        } catch (RecurringTaskException e) {
            sender().sendMessage(chatId, e.getMessage());
        }
    }

    /**
     * "/tache new" ne prend plus d'arguments positionnels (trop rebutant, en particulier
     * taper une expression cron a la main - demande de Clem le 29/08/2026). Lance a la
     * place l'assistant conversationnel : voir RecurringTaskCreationWizard, et
     * l'interception faite dans chat() ci-dessous tant qu'une session est active.
     */
    private void startTaskWizard(Long chatId) {
        if (recurringTaskWizard.isActive(chatId)) {
            sender().sendMessage(chatId,
                    "Une creation de tache est deja en cours. Reponds a la question precedente, "
                            + "ou tape \"annuler\" pour recommencer.");
            return;
        }
        sender().sendMessage(chatId, recurringTaskWizard.start(chatId));
    }

    private void enableTask(Long chatId, String rawName) {
        if (rawName == null || rawName.isBlank()) {
            sender().sendMessage(chatId, "Usage : /tache enable <nom>");
            return;
        }
        try {
            RecurringTask task = recurringTaskManager.enable(rawName);
            sender().sendMessage(chatId, "Tache '" + task.getName() + "' activee.");
        } catch (RecurringTaskException e) {
            sender().sendMessage(chatId, e.getMessage());
        }
    }

    private void disableTask(Long chatId, String rawName) {
        if (rawName == null || rawName.isBlank()) {
            sender().sendMessage(chatId, "Usage : /tache disable <nom>");
            return;
        }
        try {
            RecurringTask task = recurringTaskManager.disable(rawName);
            sender().sendMessage(chatId, "Tache '" + task.getName() + "' desactivee.");
        } catch (RecurringTaskException e) {
            sender().sendMessage(chatId, e.getMessage());
        }
    }

    private void deleteTask(Long chatId, String rawName) {
        if (rawName == null || rawName.isBlank()) {
            sender().sendMessage(chatId, "Usage : /tache delete <nom>");
            return;
        }
        try {
            recurringTaskManager.deleteTask(rawName);
            sender().sendMessage(chatId, "Tache '" + rawName + "' supprimee.");
        } catch (RecurringTaskException e) {
            sender().sendMessage(chatId, e.getMessage());
        }
    }

    private void runTask(Long chatId, String rawName) {
        if (rawName == null || rawName.isBlank()) {
            sender().sendMessage(chatId, "Usage : /tache run <nom>");
            return;
        }
        try {
            sender().sendTyping(chatId);
            RecurringTaskRunOutcome outcome = recurringTaskManager.runNow(rawName);
            StringBuilder sb = new StringBuilder("Tache '" + rawName + "' executee : " + outcome.status());
            if (outcome.exitCode() != null) {
                sb.append(" (code ").append(outcome.exitCode()).append(')');
            }
            String detail = outcome.errorMessage() != null ? outcome.errorMessage() : outcome.outputSummary();
            if (detail != null && !detail.isBlank()) {
                sb.append('\n').append(detail);
            }
            sender().sendMessage(chatId, sb.toString());
        } catch (RecurringTaskException e) {
            sender().sendMessage(chatId, "Impossible d'executer la tache : " + e.getMessage());
        }
    }

    private static String describePolicy(NotificationPolicy policy) {
        return switch (policy) {
            case ALWAYS -> "toujours";
            case ON_ISSUE -> "en cas de souci";
            case NEVER -> "jamais";
        };
    }

    // ------------------------------------------------------------------ @Chat

    @Chat
    public void chat(TelegramUpdateContext context) {
        String text = context.getText();
        Long chatId = context.getChatId();
        if (text == null || text.isBlank() || chatId == null) {
            return;
        }

        MDC.put("chatId", String.valueOf(chatId));
        try {
            if (recurringTaskWizard.isActive(chatId)) {
                sender().sendMessage(chatId, recurringTaskWizard.handleReply(chatId, text));
                return;
            }

            Optional<Project> activeOpt = resolveContextProject(context);
            if (activeOpt.isEmpty()) {
                handleChatWithoutActiveProject(context);
                return;
            }

            Project active = activeOpt.get();
            MDC.put("project", active.getName());

            sender().sendTyping(chatId);
            TelegramMessageReference placeholder = sender().sendFormattedMessageAndGetReference(
                    chatId, withHeader(active, CHAT_PROCESSING_PLACEHOLDER));
            ScheduledExecutorService typingHeartbeat = startTypingHeartbeat(chatId, context.getMessageThreadId());
            try {
                ClaudeCliResult result = chatService.sendMessage(active, text);
                sender().editFormattedMessage(chatId, placeholder.getMessageId(), withHeader(active, result.getResult()));
            } catch (ClaudeCliException e) {
                log.error("Echec de l'appel claude pour le projet '{}'", active.getName(), e);
                sender().editFormattedMessage(chatId, placeholder.getMessageId(),
                        withHeader(active, "Erreur lors de l'appel a Claude : " + e.getMessage()));
            } finally {
                typingHeartbeat.shutdownNow();
            }
        } finally {
            MDC.remove("project");
            MDC.remove("chatId");
        }
    }

    /**
     * Aucun projet actif : si des projets existent deja (juste desactives/archives),
     * on demande a l'utilisateur de choisir plutot que de creer un projet "default" qui
     * masquerait son historique. Si aucun projet n'existe encore, un projet "default"
     * est cree automatiquement (avec interview CLAUDE.md) pour ne pas bloquer un tout
     * premier usage avant meme un /projet new explicite.
     */
    private void handleChatWithoutActiveProject(TelegramUpdateContext context) {
        Long chatId = context.getChatId();
        if (projectService.listProjects().stream().anyMatch(this::isVisibleInTelegram)) {
            sender().sendMessage(chatId, noActiveProjectHint(chatId));
            return;
        }

        MDC.put("project", DEFAULT_PROJECT_NAME);
        try {
            sender().sendTyping(chatId);
            try {
                ProjectOnboardingService.OnboardingResult result =
                        onboardingService.createProjectAndStartOnboarding(DEFAULT_PROJECT_NAME);
                Project created = result.project();
                sender().sendFormattedMessage(chatId, withHeader(created,
                        "Aucun projet n'existait encore : projet '" + created.getName() + "' cree automatiquement."
                                + attachForumTopicNote(created) + "\n\n" + result.firstClaudeMessage()));
            } catch (ProjectException | ClaudeCliException e) {
                log.error("Echec de la creation automatique du projet par defaut", e);
                sender().sendMessage(chatId, "Erreur lors de la creation automatique du projet : " + e.getMessage());
            }
        } finally {
            MDC.remove("project");
        }
    }

    /**
     * Message affiche quand aucun projet ne peut etre resolu pour ce contexte (voir
     * resolveContextProject) : distingue le cas "dans le groupe Threads, hors de tout
     * Thread projet" (sujet "General", ou Thread non mappe) du cas legacy (DM, autre
     * groupe...), ou la notion de "projet actif" global garde son sens.
     */
    private String noActiveProjectHint(Long chatId) {
        if (projectThreadService.isForumChat(chatId)) {
            return "Aucun projet associe a ce Thread. Ecris dans le Thread d'un projet existant, "
                    + "ou utilise /projet new <nom> pour en creer un nouveau (un Thread sera cree automatiquement).";
        }
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
