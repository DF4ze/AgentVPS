package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.model.Project;
import fr.ses10doigts.agentvps.config.AgentVpsTelegramProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemProjectBootstrapTest {

    @Test
    void createsSystemProjectAtApplicationReady() {
        ProjectService projectService = mock(ProjectService.class);
        Project system = new Project();
        when(projectService.ensureSystemProject()).thenReturn(system);
        SystemProjectBootstrap bootstrap = new SystemProjectBootstrap(projectService);

        bootstrap.run(null);

        verify(projectService).ensureSystemProject();
        assertThat(system).isNotNull();
    }

    @Test
    void initializationFailureDoesNotEscapeBootstrap() {
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.ensureSystemProject()).thenThrow(new ProjectException("disque indisponible"));
        SystemProjectBootstrap bootstrap = new SystemProjectBootstrap(projectService);

        bootstrap.run(null);
    }

    @Test
    void createsSystemTelegramTopicOnlyWhenVisibilityIsEnabled() {
        AgentVpsTelegramProperties properties = new AgentVpsTelegramProperties();
        properties.setSystemProjectVisible(true);
        ProjectService projectService = mock(ProjectService.class);
        ProjectThreadService threadService = mock(ProjectThreadService.class);
        Project system = new Project();
        system.setName(ProjectService.ELEVATED_PROJECT_SLUG);
        when(projectService.findProjectByName(ProjectService.ELEVATED_PROJECT_SLUG)).thenReturn(java.util.Optional.of(system));
        when(threadService.createTopicForProject(system)).thenReturn(java.util.Optional.of(42));
        SystemProjectTelegramTopicBootstrap bootstrap =
                new SystemProjectTelegramTopicBootstrap(properties, projectService, threadService);

        bootstrap.onApplicationEvent(null);

        verify(threadService).createTopicForProject(system);
    }

    @Test
    void doesNotCreateSystemTelegramTopicWhenVisibilityIsDisabled() {
        AgentVpsTelegramProperties properties = new AgentVpsTelegramProperties();
        ProjectService projectService = mock(ProjectService.class);
        ProjectThreadService threadService = mock(ProjectThreadService.class);
        SystemProjectTelegramTopicBootstrap bootstrap =
                new SystemProjectTelegramTopicBootstrap(properties, projectService, threadService);

        bootstrap.onApplicationEvent(null);

        org.mockito.Mockito.verifyNoInteractions(projectService, threadService);
    }
}
