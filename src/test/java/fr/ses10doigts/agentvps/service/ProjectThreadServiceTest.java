package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.config.AgentVpsTelegramProperties;
import fr.ses10doigts.agentvps.model.Project;
import fr.ses10doigts.telegrambots.exception.TelegramForumErrorReason;
import fr.ses10doigts.telegrambots.exception.TelegramForumException;
import fr.ses10doigts.telegrambots.model.TelegramForumTopic;
import fr.ses10doigts.telegrambots.model.TelegramTopicIconColor;
import fr.ses10doigts.telegrambots.service.sender.TelegramSender;
import fr.ses10doigts.telegrambots.service.sender.TelegramSenderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectThreadServiceTest {

    private static final Long FORUM_CHAT_ID = -1004352885219L;

    @Mock
    private ProjectService projectService;

    @Mock
    private ObjectProvider<TelegramSenderRegistry> telegramSenderRegistryProvider;

    @Mock
    private TelegramSenderRegistry telegramSenderRegistry;

    @Mock
    private TelegramSender telegramSender;

    private AgentVpsTelegramProperties properties;
    private ProjectThreadService service;

    @BeforeEach
    void setUp() {
        properties = new AgentVpsTelegramProperties();
        service = new ProjectThreadService(properties, projectService, telegramSenderRegistryProvider);
        lenient().when(telegramSenderRegistryProvider.getIfAvailable()).thenReturn(telegramSenderRegistry);
        lenient().when(telegramSenderRegistry.getDefaultBotSender()).thenReturn(telegramSender);
    }

    @Test
    void isEnabledIsFalseWhenForumChatIdIsBlank() {
        assertThat(service.isEnabled()).isFalse();
        assertThat(service.forumChatId()).isNull();
    }

    @Test
    void isEnabledIsTrueWhenForumChatIdIsConfigured() {
        properties.setForumChatId(FORUM_CHAT_ID.toString());

        assertThat(service.isEnabled()).isTrue();
        assertThat(service.forumChatId()).isEqualTo(FORUM_CHAT_ID);
    }

    @Test
    void forumChatIdIsNullWhenNotAnInteger() {
        properties.setForumChatId("pas-un-nombre");

        assertThat(service.forumChatId()).isNull();
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void isForumChatMatchesOnlyTheConfiguredGroup() {
        properties.setForumChatId(FORUM_CHAT_ID.toString());

        assertThat(service.isForumChat(FORUM_CHAT_ID)).isTrue();
        assertThat(service.isForumChat(10L)).isFalse();
        assertThat(service.isForumChat(null)).isFalse();
    }

    @Test
    void createTopicForProjectDoesNothingWhenThreadsAreNotConfigured() {
        Project project = project("mon-projet");

        assertThat(service.createTopicForProject(project)).isEmpty();
        verify(telegramSenderRegistryProvider, never()).getIfAvailable();
    }

    @Test
    void createTopicForProjectCreatesTheTopicAndRegistersItsThreadId() {
        properties.setForumChatId(FORUM_CHAT_ID.toString());
        Project project = project("mon-projet");
        TelegramForumTopic topic = TelegramForumTopic.builder()
                .messageThreadId(55)
                .name("mon-projet")
                .iconColor(TelegramTopicIconColor.BLUE)
                .build();
        when(telegramSender.createForumTopic(eq(FORUM_CHAT_ID), any(), any(), isNull())).thenReturn(topic);

        assertThat(service.createTopicForProject(project)).contains(55);

        verify(projectService).setThreadId("mon-projet", 55);
    }

    @Test
    void createTopicForProjectSwallowsTelegramForumExceptions() {
        properties.setForumChatId(FORUM_CHAT_ID.toString());
        Project project = project("mon-projet");
        when(telegramSender.createForumTopic(eq(FORUM_CHAT_ID), any(), any(), isNull()))
                .thenThrow(new TelegramForumException(TelegramForumErrorReason.MISSING_RIGHTS, "pas les droits"));

        assertThat(service.createTopicForProject(project)).isEmpty();
        verify(projectService, never()).setThreadId(any(), any());
    }

    @Test
    void deleteTopicDoesNothingWhenMessageThreadIdIsNull() {
        properties.setForumChatId(FORUM_CHAT_ID.toString());

        service.deleteTopic(null);

        verify(telegramSenderRegistryProvider, never()).getIfAvailable();
    }

    @Test
    void deleteTopicCallsTelegramWhenConfiguredAndThreadIdPresent() {
        properties.setForumChatId(FORUM_CHAT_ID.toString());

        service.deleteTopic(55);

        verify(telegramSender).deleteForumTopic(FORUM_CHAT_ID, 55);
    }

    @Test
    void deleteTopicSwallowsTelegramForumExceptions() {
        properties.setForumChatId(FORUM_CHAT_ID.toString());
        when(telegramSender.deleteForumTopic(FORUM_CHAT_ID, 55))
                .thenThrow(new TelegramForumException(TelegramForumErrorReason.TOPIC_NOT_FOUND, "introuvable"));

        service.deleteTopic(55);
    }

    @Test
    void topicStillExistsIsTrueWhenReopenSucceeds() {
        properties.setForumChatId(FORUM_CHAT_ID.toString());
        when(telegramSender.reopenForumTopic(FORUM_CHAT_ID, 55)).thenReturn(true);

        assertThat(service.topicStillExists(55)).isTrue();
    }

    @Test
    void topicStillExistsIsFalseWhenReopenThrowsTopicNotFound() {
        properties.setForumChatId(FORUM_CHAT_ID.toString());
        when(telegramSender.reopenForumTopic(FORUM_CHAT_ID, 55))
                .thenThrow(new TelegramForumException(TelegramForumErrorReason.TOPIC_NOT_FOUND, "introuvable"));

        assertThat(service.topicStillExists(55)).isFalse();
    }

    @Test
    void topicStillExistsIsFalseWhenThreadsAreNotConfigured() {
        assertThat(service.topicStillExists(55)).isFalse();
    }

    private static Project project(String name) {
        Project project = new Project();
        project.setName(name);
        return project;
    }
}
