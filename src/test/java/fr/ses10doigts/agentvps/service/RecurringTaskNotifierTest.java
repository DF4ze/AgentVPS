package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.config.RecurringTaskProperties;
import fr.ses10doigts.agentvps.model.NotificationPolicy;
import fr.ses10doigts.agentvps.model.Project;
import fr.ses10doigts.agentvps.model.RecurringTask;
import fr.ses10doigts.agentvps.model.RecurringTaskRunOutcome;
import fr.ses10doigts.agentvps.model.RunStatus;
import fr.ses10doigts.telegrambots.service.sender.TelegramSender;
import fr.ses10doigts.telegrambots.service.sender.TelegramSenderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Depuis le 29/08/2026 (voir RecurringTaskNotifier), le notifier passe par
 * TelegramSenderRegistry.getDefaultBotSender() plutot que par le bean TelegramSender
 * (ContextAwareTelegramSender) injecte ailleurs dans le code - le bean TelegramSender lui-
 * meme n'est donc plus mocke ici, seul son "sender par defaut" obtenu via le registry l'est.
 *
 * Depuis le 02/09/2026 (feature Threads = projets, voir ProjectThreadService), le notifier
 * route en priorite vers le Thread Telegram du projet concerne (projectThreadService.forumChatId()
 * non null) - projectThreadService/projectService renvoient null/vide par defaut (mock non
 * stubbe) dans tous les tests existants ci-dessous, qui continuent donc de couvrir le seul
 * fallback notification-chat-id, sans le savoir.
 */
@ExtendWith(MockitoExtension.class)
class RecurringTaskNotifierTest {

    @Mock
    private ObjectProvider<TelegramSenderRegistry> telegramSenderRegistryProvider;

    @Mock
    private TelegramSenderRegistry telegramSenderRegistry;

    @Mock
    private TelegramSender telegramSender;

    @Mock
    private ProjectService projectService;

    @Mock
    private ProjectThreadService projectThreadService;

    private RecurringTaskProperties properties;
    private RecurringTaskNotifier notifier;

    @BeforeEach
    void setUp() {
        properties = new RecurringTaskProperties();
        properties.setNotificationChatId("1595302518");
        notifier = new RecurringTaskNotifier(telegramSenderRegistryProvider, properties, projectService, projectThreadService);
        lenient().when(telegramSenderRegistryProvider.getIfAvailable()).thenReturn(telegramSenderRegistry);
        lenient().when(telegramSenderRegistry.getDefaultBotSender()).thenReturn(telegramSender);
    }

    @Test
    void whenTelegramSenderRegistryUnavailableSkipsSilentlyWithoutThrowing() {
        when(telegramSenderRegistryProvider.getIfAvailable()).thenReturn(null);

        notifier.notify(task(NotificationPolicy.ALWAYS), new RecurringTaskRunOutcome(RunStatus.OK, 0, "ok", null));

        verify(telegramSender, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    void alwaysPolicySendsEvenWhenOk() {
        notifier.notify(task(NotificationPolicy.ALWAYS), new RecurringTaskRunOutcome(RunStatus.OK, 0, "tout va bien", null));

        verify(telegramSender).sendMessage(eq(1595302518L), anyString());
    }

    @Test
    void onIssuePolicySkipsWhenOk() {
        notifier.notify(task(NotificationPolicy.ON_ISSUE), new RecurringTaskRunOutcome(RunStatus.OK, 0, "tout va bien", null));

        verify(telegramSender, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    void onIssuePolicySendsWhenWarning() {
        notifier.notify(task(NotificationPolicy.ON_ISSUE), new RecurringTaskRunOutcome(RunStatus.WARNING, 1, "systemd degraded", null));

        verify(telegramSender).sendMessage(eq(1595302518L), anyString());
    }

    @Test
    void neverPolicyNeverSends() {
        notifier.notify(task(NotificationPolicy.NEVER), new RecurringTaskRunOutcome(RunStatus.CRITICAL, 2, "disque plein", null));

        verify(telegramSender, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    void missingChatIdSkipsSilentlyWithoutThrowing() {
        properties.setNotificationChatId("");

        notifier.notify(task(NotificationPolicy.ALWAYS), new RecurringTaskRunOutcome(RunStatus.OK, 0, "ok", null));

        verify(telegramSender, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    void routesToTheProjectThreadWhenTheProjectHasOne() {
        RecurringTask task = task(NotificationPolicy.ALWAYS);
        task.setProjectName("maintenance");
        Project withThread = new Project();
        withThread.setName("maintenance");
        withThread.setTelegramThreadId(99);
        when(projectThreadService.forumChatId()).thenReturn(-1004352885219L);
        when(projectService.findProjectByName("maintenance")).thenReturn(java.util.Optional.of(withThread));

        notifier.notify(task, new RecurringTaskRunOutcome(RunStatus.OK, 0, "tout va bien", null));

        verify(telegramSender).sendMessage(eq(-1004352885219L), eq(99), anyString());
        verify(telegramSender, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    void fallsBackToNotificationChatIdWhenThreadsAreConfiguredButTheProjectHasNone() {
        RecurringTask task = task(NotificationPolicy.ALWAYS);
        task.setProjectName("maintenance");
        when(projectThreadService.forumChatId()).thenReturn(-1004352885219L);
        when(projectService.findProjectByName("maintenance")).thenReturn(java.util.Optional.empty());

        notifier.notify(task, new RecurringTaskRunOutcome(RunStatus.OK, 0, "tout va bien", null));

        verify(telegramSender).sendMessage(eq(1595302518L), anyString());
    }

    private static RecurringTask task(NotificationPolicy policy) {
        RecurringTask task = new RecurringTask();
        task.setName("healthcheck");
        task.setNotificationPolicy(policy);
        return task;
    }
}
