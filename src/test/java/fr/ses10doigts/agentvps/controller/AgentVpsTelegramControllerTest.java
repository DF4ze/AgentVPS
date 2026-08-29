package fr.ses10doigts.agentvps.controller;

import fr.ses10doigts.agentvps.model.ClaudeCliResult;
import fr.ses10doigts.agentvps.model.Conversation;
import fr.ses10doigts.agentvps.model.NotificationPolicy;
import fr.ses10doigts.agentvps.model.Project;
import fr.ses10doigts.agentvps.model.ProjectStatus;
import fr.ses10doigts.agentvps.model.RecurringTask;
import fr.ses10doigts.agentvps.model.RecurringTaskRunOutcome;
import fr.ses10doigts.agentvps.model.RecurringTaskStatus;
import fr.ses10doigts.agentvps.model.RecurringTaskTriggerType;
import fr.ses10doigts.agentvps.model.RunStatus;
import fr.ses10doigts.agentvps.service.ChatService;
import fr.ses10doigts.agentvps.service.ClaudeCliException;
import fr.ses10doigts.agentvps.service.ProjectException;
import fr.ses10doigts.agentvps.service.ProjectOnboardingService;
import fr.ses10doigts.agentvps.service.ProjectService;
import fr.ses10doigts.agentvps.service.RecurringTaskCreationWizard;
import fr.ses10doigts.agentvps.service.RecurringTaskException;
import fr.ses10doigts.agentvps.service.RecurringTaskManager;
import fr.ses10doigts.agentvps.service.RecurringTaskService;
import fr.ses10doigts.telegrambots.model.TelegramMessageReference;
import fr.ses10doigts.telegrambots.model.TelegramUpdateContext;
import fr.ses10doigts.telegrambots.service.sender.TelegramSender;
import fr.ses10doigts.telegrambots.service.sender.TelegramSenderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentVpsTelegramControllerTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private ProjectOnboardingService onboardingService;

    @Mock
    private ChatService chatService;

    @Mock
    private RecurringTaskService recurringTaskService;

    @Mock
    private RecurringTaskManager recurringTaskManager;

    @Mock
    private RecurringTaskCreationWizard recurringTaskWizard;

    @Mock
    private TelegramSender telegramSender;

    @Mock
    private ObjectProvider<TelegramSender> telegramSenderProvider;

    @Mock
    private TelegramSenderRegistry telegramSenderRegistry;

    @Mock
    private ObjectProvider<TelegramSenderRegistry> telegramSenderRegistryProvider;

    private AgentVpsTelegramController controller;

    @BeforeEach
    void setUp() {
        lenient().when(telegramSenderProvider.getObject()).thenReturn(telegramSender);
        // Utilise uniquement par le heartbeat "typing..." en tache de fond (voir point 4 du
        // javadoc de AgentVpsTelegramController) : jamais exerce dans ces tests synchrones
        // (l'appel a chatService.sendMessage mocke revient bien avant le premier declenchement
        // du heartbeat, 4s plus tard), lenient() evite donc une UnnecessaryStubbingException.
        lenient().when(telegramSenderRegistryProvider.getObject()).thenReturn(telegramSenderRegistry);
        lenient().when(telegramSenderRegistry.getDefaultBotSender()).thenReturn(telegramSender);
        controller = new AgentVpsTelegramController(
                projectService, onboardingService, chatService, recurringTaskService, recurringTaskManager,
                recurringTaskWizard, telegramSenderProvider, telegramSenderRegistryProvider);
    }

    // ---------------------------------------------------------------- /projet

    @Test
    void projetWithoutArgsShowsActiveProjectName() {
        when(projectService.getActiveProject()).thenReturn(Optional.of(project("mon-projet", ProjectStatus.ACTIVE, null)));

        controller.projet(context(10L, "/projet", List.of()));

        verify(telegramSender).sendMessage(10L, "Projet actif : mon-projet");
    }

    @Test
    void projetWithoutArgsAndNoActiveProjectShowsHint() {
        when(projectService.getActiveProject()).thenReturn(Optional.empty());

        controller.projet(context(10L, "/projet", List.of()));

        verify(telegramSender).sendMessage(eq(10L), org.mockito.ArgumentMatchers.contains("/projet list"));
    }

    @Test
    void projetListMarksActiveAndArchivedProjects() {
        when(projectService.listProjects()).thenReturn(List.of(
                project("mon-projet", ProjectStatus.ACTIVE, null),
                project("vieux-projet", ProjectStatus.ARCHIVED, null)
        ));
        when(projectService.getActiveProject()).thenReturn(Optional.of(project("mon-projet", ProjectStatus.ACTIVE, null)));

        controller.projet(context(10L, "/projet list", List.of("list")));

        verify(telegramSender).sendMessage(eq(10L), eq(
                "Projets :\n> mon-projet (actif)\n  vieux-projet (archive)"
        ));
    }

    @Test
    void projetListWhenEmptyInvitesToCreateOne() {
        when(projectService.listProjects()).thenReturn(List.of());

        controller.projet(context(10L, "/projet list", List.of("list")));

        verify(telegramSender).sendMessage(eq(10L), org.mockito.ArgumentMatchers.contains("/projet new"));
    }

    @Test
    void projetNewCreatesProjectAndSendsFirstOnboardingQuestion() {
        Project created = project("mon-projet", ProjectStatus.ACTIVE, null);
        Conversation conversation = new Conversation("session-1", Instant.now(), Instant.now(), "Mise en place initiale", 0);
        when(onboardingService.createProjectAndStartOnboarding("Mon Projet"))
                .thenReturn(new ProjectOnboardingService.OnboardingResult(created, conversation, "A quoi va servir ce projet ?"));

        controller.projet(context(10L, "/projet new Mon Projet", List.of("new", "Mon", "Projet")));

        verify(telegramSender).sendTyping(10L);
        verify(telegramSender).sendMessage(10L, "Projet 'mon-projet' cree.\n\nA quoi va servir ce projet ?");
    }

    @Test
    void projetNewWithoutNameShowsUsageAndDoesNotCallOnboarding() {
        controller.projet(context(10L, "/projet new", List.of("new")));

        verify(telegramSender).sendMessage(10L, "Usage : /projet new <nom>");
        verify(onboardingService, never()).createProjectAndStartOnboarding(any());
    }

    @Test
    void projetNewReportsProjectExceptionAsFriendlyMessage() {
        when(onboardingService.createProjectAndStartOnboarding("mon-projet"))
                .thenThrow(new ProjectException("Un projet nomme 'mon-projet' existe deja"));

        controller.projet(context(10L, "/projet new mon-projet", List.of("new", "mon-projet")));

        verify(telegramSender).sendMessage(10L, "Impossible de creer le projet : Un projet nomme 'mon-projet' existe deja");
    }

    @Test
    void projetNewReportsClaudeFailureButKeepsTheCreatedProject() {
        when(onboardingService.createProjectAndStartOnboarding("mon-projet"))
                .thenThrow(new ClaudeCliException("timeout"));

        controller.projet(context(10L, "/projet new mon-projet", List.of("new", "mon-projet")));

        verify(telegramSender).sendMessage(eq(10L), org.mockito.ArgumentMatchers.contains("a ete cree mais l'interview a echoue"));
    }

    @Test
    void projetDeleteArchivesTheActiveProjectWhenNoNameGiven() {
        when(projectService.getActiveProject()).thenReturn(Optional.of(project("mon-projet", ProjectStatus.ACTIVE, null)));

        controller.projet(context(10L, "/projet delete", List.of("delete")));

        verify(projectService).archiveProject("mon-projet");
        verify(telegramSender).sendMessage(eq(10L), org.mockito.ArgumentMatchers.contains("mon-projet"));
    }

    @Test
    void projetDeleteArchivesTheNamedProjectWhenGiven() {
        controller.projet(context(10L, "/projet delete autre-projet", List.of("delete", "autre-projet")));

        verify(projectService).archiveProject("autre-projet");
        verify(projectService, never()).getActiveProject();
    }

    @Test
    void projetDeleteWithoutActiveProjectAndNoNameDoesNothingDestructive() {
        when(projectService.getActiveProject()).thenReturn(Optional.empty());

        controller.projet(context(10L, "/projet delete", List.of("delete")));

        verify(projectService, never()).archiveProject(any());
        verify(telegramSender).sendMessage(eq(10L), org.mockito.ArgumentMatchers.contains("Precise un nom"));
    }

    @Test
    void projetWithNameSwitchesActiveProject() {
        when(projectService.switchProject("autre-projet")).thenReturn(project("autre-projet", ProjectStatus.ACTIVE, null));

        controller.projet(context(10L, "/projet autre-projet", List.of("autre-projet")));

        verify(telegramSender).sendMessage(10L, "Projet actif : autre-projet");
    }

    @Test
    void projetWithUnknownNameReportsNotFound() {
        when(projectService.switchProject("inconnu")).thenThrow(new ProjectException("Aucun projet nomme 'inconnu'"));

        controller.projet(context(10L, "/projet inconnu", List.of("inconnu")));

        verify(telegramSender).sendMessage(10L, "Projet introuvable : Aucun projet nomme 'inconnu'");
    }

    // ------------------------------------------------------------------ /conv

    @Test
    void convWithoutActiveProjectShowsHint() {
        when(projectService.getActiveProject()).thenReturn(Optional.empty());

        controller.conv(context(10L, "/conv", List.of()));

        verify(telegramSender).sendMessage(eq(10L), org.mockito.ArgumentMatchers.contains("/projet list"));
    }

    @Test
    void convWithoutArgsAndNoCurrentConversationExplainsANewOneWillStart() {
        when(projectService.getActiveProject()).thenReturn(Optional.of(project("mon-projet", ProjectStatus.ACTIVE, null)));
        when(projectService.getCurrentConversation("mon-projet")).thenReturn(Optional.empty());

        controller.conv(context(10L, "/conv", List.of()));

        verify(telegramSender).sendMessage(eq(10L), org.mockito.ArgumentMatchers.contains("pas de conversation en cours"));
    }

    @Test
    void convListMarksTheCurrentConversation() {
        // session_id realistes (36 caracteres) et distincts sur leurs 8 premiers
        // caracteres, puisque describe() tronque a 8 caracteres pour l'affichage.
        String sessionId1 = "11111111-aaaa-bbbb-cccc-111111111111";
        String sessionId2 = "22222222-aaaa-bbbb-cccc-222222222222";

        Project active = project("mon-projet", ProjectStatus.ACTIVE, sessionId2);
        when(projectService.getActiveProject()).thenReturn(Optional.of(active));
        Instant at = Instant.parse("2026-08-28T16:24:00Z");
        when(projectService.listConversations("mon-projet")).thenReturn(List.of(
                new Conversation(sessionId1, at, at, "Mise en place initiale", 0),
                new Conversation(sessionId2, at, at, null, 0)
        ));

        controller.conv(context(10L, "/conv list", List.of("list")));

        verify(telegramSender).sendMessage(eq(10L), eq(
                """
                        Conversations de 'mon-projet' :
                          1. Mise en place initiale (11111111..., 2026-08-28T16:24:00Z)
                        > 2. (sans libelle) (22222222..., 2026-08-28T16:24:00Z)"""
        ));
    }

    @Test
    void convNewStartsANewConversationForTheActiveProject() {
        when(projectService.getActiveProject()).thenReturn(Optional.of(project("mon-projet", ProjectStatus.ACTIVE, "session-1")));

        controller.conv(context(10L, "/conv new", List.of("new")));

        verify(projectService).startNewConversation("mon-projet");
        verify(telegramSender).sendMessage(eq(10L), org.mockito.ArgumentMatchers.contains("Nouvelle conversation prete"));
    }

    @Test
    void convWithNumberSwitchesToThatConversation() {
        when(projectService.getActiveProject()).thenReturn(Optional.of(project("mon-projet", ProjectStatus.ACTIVE, null)));
        Conversation conversation = new Conversation("session-1", Instant.now(), Instant.now(), "Label", 0);
        when(projectService.switchConversation("mon-projet", 2)).thenReturn(conversation);

        controller.conv(context(10L, "/conv 2", List.of("2")));

        verify(telegramSender).sendMessage(eq(10L), org.mockito.ArgumentMatchers.contains("Conversation courante"));
    }

    @Test
    void convWithNonNumericArgumentReportsInvalidArgumentWithoutCallingSwitch() {
        when(projectService.getActiveProject()).thenReturn(Optional.of(project("mon-projet", ProjectStatus.ACTIVE, null)));

        controller.conv(context(10L, "/conv abc", List.of("abc")));

        verify(telegramSender).sendMessage(eq(10L), org.mockito.ArgumentMatchers.contains("numero de conversation"));
        verify(projectService, never()).switchConversation(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ------------------------------------------------------------------ @Chat

    @Test
    void chatIgnoresBlankMessages() {
        controller.chat(context(10L, "   ", List.of()));

        verify(telegramSender, never()).sendMessage(any(), any());
        verify(chatService, never()).sendMessage(any(), any());
    }

    @Test
    void chatRoutesToTheWizardWhileACreationSessionIsActiveInsteadOfCallingClaude() {
        when(recurringTaskWizard.isActive(10L)).thenReturn(true);
        when(recurringTaskWizard.handleReply(10L, "healthcheck")).thenReturn("Quelle commande faut-il executer ?");

        controller.chat(context(10L, "healthcheck", List.of()));

        verify(telegramSender).sendMessage(10L, "Quelle commande faut-il executer ?");
        verify(chatService, never()).sendMessage(any(), any());
        verify(projectService, never()).getActiveProject();
    }

    @Test
    void chatWithActiveProjectDelegatesToChatServiceAndRepliesWithResult() {
        // Le choix --resume vs nouvelle conversation, l'enregistrement de la conversation
        // et le compteur de renforcement periodique sont geres par ChatService (voir
        // ChatServiceTest) : le controller se contente de lui transmettre le message et
        // d'envoyer le resultat, quel que soit l'etat de la conversation courante.
        Project active = project("mon-projet", ProjectStatus.ACTIVE, "session-1");
        when(projectService.getActiveProject()).thenReturn(Optional.of(active));
        when(telegramSender.sendMessageAndGetReference(10L, AgentVpsTelegramController.CHAT_PROCESSING_PLACEHOLDER))
                .thenReturn(TelegramMessageReference.builder().chatId(10L).messageId(42).build());
        ClaudeCliResult result = new ClaudeCliResult();
        result.setResult("Suite...");
        when(chatService.sendMessage(active, "Continue")).thenReturn(result);

        controller.chat(context(10L, "Continue", List.of()));

        verify(chatService).sendMessage(active, "Continue");
        verify(telegramSender).editMessage(10L, 42, "Suite...");
        verify(telegramSender, never()).sendMessage(eq(10L), any());
    }

    @Test
    void chatSendsProcessingPlaceholderBeforeCallingClaude() {
        // Le place-holder doit partir tout de suite (avant l'appel bloquant a claude -p, jusqu'a
        // 120s) pour que Clem sache que le bot est bien UP des la reception du message - voir le
        // point 3 du javadoc de la classe.
        Project active = project("mon-projet", ProjectStatus.ACTIVE, "session-1");
        when(projectService.getActiveProject()).thenReturn(Optional.of(active));
        when(telegramSender.sendMessageAndGetReference(10L, AgentVpsTelegramController.CHAT_PROCESSING_PLACEHOLDER))
                .thenReturn(TelegramMessageReference.builder().chatId(10L).messageId(99).build());
        ClaudeCliResult result = new ClaudeCliResult();
        result.setResult("Reponse");
        when(chatService.sendMessage(active, "Salut")).thenReturn(result);

        controller.chat(context(10L, "Salut", List.of()));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(telegramSender, chatService);
        order.verify(telegramSender).sendMessageAndGetReference(10L, AgentVpsTelegramController.CHAT_PROCESSING_PLACEHOLDER);
        order.verify(chatService).sendMessage(active, "Salut");
        order.verify(telegramSender).editMessage(10L, 99, "Reponse");
    }

    @Test
    void chatReportsClaudeFailureInsteadOfStayingSilent() {
        Project active = project("mon-projet", ProjectStatus.ACTIVE, "session-1");
        when(projectService.getActiveProject()).thenReturn(Optional.of(active));
        when(telegramSender.sendMessageAndGetReference(eq(10L), any()))
                .thenReturn(TelegramMessageReference.builder().chatId(10L).messageId(7).build());
        when(chatService.sendMessage(any(), any())).thenThrow(new ClaudeCliException("timeout"));

        controller.chat(context(10L, "Salut", List.of()));

        verify(telegramSender).editMessage(10L, 7, "Erreur lors de l'appel a Claude : timeout");
    }

    @Test
    void chatWithoutActiveProjectButExistingProjectsAsksToChooseOne() {
        when(projectService.getActiveProject()).thenReturn(Optional.empty());
        when(projectService.listProjects()).thenReturn(List.of(project("vieux-projet", ProjectStatus.ARCHIVED, null)));

        controller.chat(context(10L, "Salut", List.of()));

        verify(telegramSender).sendMessage(eq(10L), org.mockito.ArgumentMatchers.contains("/projet list"));
        verify(onboardingService, never()).createProjectAndStartOnboarding(any());
    }

    @Test
    void chatWithoutAnyProjectAutoCreatesTheDefaultProject() {
        when(projectService.getActiveProject()).thenReturn(Optional.empty());
        when(projectService.listProjects()).thenReturn(List.of());
        Project created = project("default", ProjectStatus.ACTIVE, null);
        when(onboardingService.createProjectAndStartOnboarding(AgentVpsTelegramController.DEFAULT_PROJECT_NAME))
                .thenReturn(new ProjectOnboardingService.OnboardingResult(
                        created, new Conversation("session-1", Instant.now(), Instant.now(), "Mise en place initiale", 0),
                        "Premiere question ?"));

        controller.chat(context(10L, "Salut", List.of()));

        verify(telegramSender).sendTyping(10L);
        verify(telegramSender).sendMessage(eq(10L), org.mockito.ArgumentMatchers.contains("Premiere question ?"));
    }

    @Test
    void chatAutoCreationFailureIsReportedNotSwallowed() {
        when(projectService.getActiveProject()).thenReturn(Optional.empty());
        when(projectService.listProjects()).thenReturn(List.of());
        when(onboardingService.createProjectAndStartOnboarding(AgentVpsTelegramController.DEFAULT_PROJECT_NAME))
                .thenThrow(new ClaudeCliException("pas authentifie"));

        controller.chat(context(10L, "Salut", List.of()));

        verify(telegramSender).sendMessage(eq(10L), org.mockito.ArgumentMatchers.contains("pas authentifie"));
    }

    // ------------------------------------------------------------------ /tache

    @Test
    void tacheWithoutArgsAndNoTasksShowsHint() {
        when(recurringTaskService.listTasks()).thenReturn(List.of());

        controller.tache(context(10L, "/tache", List.of()));

        verify(telegramSender).sendMessage(eq(10L), org.mockito.ArgumentMatchers.contains("/tache new"));
    }

    @Test
    void tacheListShowsStatusAndLastRun() {
        when(recurringTaskService.listTasks()).thenReturn(List.of(
                recurringTask("healthcheck", RecurringTaskStatus.ACTIVE, RunStatus.OK),
                recurringTask("vieille-tache", RecurringTaskStatus.DISABLED, null)
        ));

        controller.tache(context(10L, "/tache list", List.of("list")));

        verify(telegramSender).sendMessage(eq(10L), eq(
                "Taches recurrentes :\n> healthcheck - dernier run : OK\n  vieille-tache (desactivee) - jamais execute"
        ));
    }

    @Test
    void tacheListTagsOneTimeTasks() {
        RecurringTask oneTime = recurringTask("rappel", RecurringTaskStatus.ACTIVE, null);
        oneTime.setTriggerType(RecurringTaskTriggerType.ONE_TIME);
        oneTime.setCronExpression(null);
        oneTime.setScheduledAt(Instant.now().plusSeconds(3600));
        when(recurringTaskService.listTasks()).thenReturn(List.of(oneTime));

        controller.tache(context(10L, "/tache list", List.of("list")));

        verify(telegramSender).sendMessage(eq(10L), org.mockito.ArgumentMatchers.contains("rappel [ponctuelle]"));
    }

    @Test
    void tacheShowDisplaysScheduledDateForAOneTimeTask() {
        Instant scheduledAt = Instant.now().plusSeconds(3600);
        RecurringTask oneTime = recurringTask("rappel", RecurringTaskStatus.ACTIVE, null);
        oneTime.setTriggerType(RecurringTaskTriggerType.ONE_TIME);
        oneTime.setCronExpression(null);
        oneTime.setScheduledAt(scheduledAt);
        when(recurringTaskService.getTask("rappel")).thenReturn(oneTime);
        String expectedDate = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(scheduledAt);

        controller.tache(context(10L, "/tache show rappel", List.of("show", "rappel")));

        verify(telegramSender).sendMessage(eq(10L), org.mockito.ArgumentMatchers.argThat(msg ->
                msg.contains("Type : ponctuelle") && msg.contains("Prevue le : " + expectedDate)
        ));
    }

    @Test
    void tacheShowDisplaysCronForARecurringTask() {
        RecurringTask cronTask = recurringTask("healthcheck", RecurringTaskStatus.ACTIVE, null);
        when(recurringTaskService.getTask("healthcheck")).thenReturn(cronTask);

        controller.tache(context(10L, "/tache show healthcheck", List.of("show", "healthcheck")));

        verify(telegramSender).sendMessage(eq(10L), org.mockito.ArgumentMatchers.contains("Cron : 0 0 6 * * *"));
    }

    @Test
    void tacheNewStartsTheCreationWizard() {
        when(recurringTaskWizard.isActive(10L)).thenReturn(false);
        when(recurringTaskWizard.start(10L)).thenReturn("Quel nom veux-tu lui donner ?");

        controller.tache(context(10L, "/tache new", List.of("new")));

        verify(recurringTaskWizard).start(10L);
        verify(telegramSender).sendMessage(10L, "Quel nom veux-tu lui donner ?");
    }

    @Test
    void tacheNewWhenWizardAlreadyActiveDoesNotRestartIt() {
        when(recurringTaskWizard.isActive(10L)).thenReturn(true);

        controller.tache(context(10L, "/tache new", List.of("new")));

        verify(recurringTaskWizard, never()).start(any());
        verify(telegramSender).sendMessage(eq(10L), org.mockito.ArgumentMatchers.contains("deja en cours"));
    }

    @Test
    void tacheEnableReportsSuccess() {
        when(recurringTaskManager.enable("healthcheck"))
                .thenReturn(recurringTask("healthcheck", RecurringTaskStatus.ACTIVE, null));

        controller.tache(context(10L, "/tache enable healthcheck", List.of("enable", "healthcheck")));

        verify(telegramSender).sendMessage(10L, "Tache 'healthcheck' activee.");
    }

    @Test
    void tacheDisableReportsSuccess() {
        when(recurringTaskManager.disable("healthcheck"))
                .thenReturn(recurringTask("healthcheck", RecurringTaskStatus.DISABLED, null));

        controller.tache(context(10L, "/tache disable healthcheck", List.of("disable", "healthcheck")));

        verify(telegramSender).sendMessage(10L, "Tache 'healthcheck' desactivee.");
    }

    @Test
    void tacheDeleteReportsSuccess() {
        controller.tache(context(10L, "/tache delete healthcheck", List.of("delete", "healthcheck")));

        verify(recurringTaskManager).deleteTask("healthcheck");
        verify(telegramSender).sendMessage(10L, "Tache 'healthcheck' supprimee.");
    }

    @Test
    void tacheRunSendsTypingAndReportsOutcome() {
        when(recurringTaskManager.runNow("healthcheck"))
                .thenReturn(new RecurringTaskRunOutcome(RunStatus.WARNING, 1, "1 unite systemd en echec", null));

        controller.tache(context(10L, "/tache run healthcheck", List.of("run", "healthcheck")));

        verify(telegramSender).sendTyping(10L);
        verify(telegramSender).sendMessage(eq(10L), eq(
                "Tache 'healthcheck' executee : WARNING (code 1)\n1 unite systemd en echec"
        ));
    }

    @Test
    void tacheRunReportsExecutionFailure() {
        when(recurringTaskManager.runNow("healthcheck"))
                .thenThrow(new RecurringTaskException("Aucune tache recurrente nommee 'healthcheck'"));

        controller.tache(context(10L, "/tache run healthcheck", List.of("run", "healthcheck")));

        verify(telegramSender).sendMessage(eq(10L), org.mockito.ArgumentMatchers.contains("Impossible d'executer la tache"));
    }

    // ------------------------------------------------------------------ helpers

    private static TelegramUpdateContext context(Long chatId, String text, List<String> args) {
        String command = (text != null && text.startsWith("/")) ? text.split("\\s+")[0] : null;
        return new TelegramUpdateContext(
                "bot-1",
                null,
                null,
                null,
                chatId,
                1L,
                text,
                command,
                args.isEmpty() ? null : String.join(" ", args),
                args,
                false,
                null
        );
    }

    private static Project project(String name, ProjectStatus status, String currentSessionId) {
        Project project = new Project();
        project.setName(name);
        project.setStatus(status);
        project.setCreatedAt(Instant.now());
        project.setWorkingDirectory("/home/agentvps/AgentVPS/projects/" + name);
        project.setCurrentSessionId(currentSessionId);
        return project;
    }

    private static RecurringTask recurringTask(String name, RecurringTaskStatus status, RunStatus lastRunStatus) {
        RecurringTask task = new RecurringTask();
        task.setName(name);
        task.setProjectName("maintenance");
        task.setCommand("./health_check.sh");
        task.setCronExpression("0 0 6 * * *");
        task.setStatus(status);
        task.setNotificationPolicy(NotificationPolicy.ON_ISSUE);
        task.setCreatedAt(Instant.now());
        task.setLastRunStatus(lastRunStatus);
        return task;
    }
}
