package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.config.AgentVpsTelegramProperties;
import fr.ses10doigts.agentvps.model.Project;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Cree le Thread Telegram du projet system apres l'initialisation complete de
 * l'application, uniquement lorsque le projet est expose par configuration.
 */
@Component
@Order(20)
@RequiredArgsConstructor
@Slf4j
public class SystemProjectTelegramTopicBootstrap implements ApplicationListener<ApplicationReadyEvent> {

    private final AgentVpsTelegramProperties telegramProperties;
    private final ProjectService projectService;
    private final ProjectThreadService projectThreadService;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!telegramProperties.isSystemProjectVisible()) {
            log.debug("Projet system masque de Telegram : aucun Thread system cree");
            return;
        }

        projectService.findProjectByName(ProjectService.ELEVATED_PROJECT_SLUG)
                .ifPresent(this::ensureTopic);
    }

    private void ensureTopic(Project project) {
        Integer existingThreadId = project.getTelegramThreadId();
        if (existingThreadId != null && projectThreadService.topicStillExists(existingThreadId)) {
            log.debug("Thread Telegram du projet system deja present (messageThreadId={})", existingThreadId);
            return;
        }

        projectThreadService.createTopicForProject(project);
    }
}
