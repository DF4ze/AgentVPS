package fr.ses10doigts.agentvps.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.agentvps.config.JacksonConfig;
import fr.ses10doigts.agentvps.config.WorkspaceProperties;
import fr.ses10doigts.agentvps.model.Conversation;
import fr.ses10doigts.agentvps.model.Project;
import fr.ses10doigts.agentvps.model.ProjectStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectServiceTest {

    private ProjectService service;
    private WorkspaceProperties workspaceProperties;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        ObjectMapper objectMapper = new JacksonConfig().objectMapper();
        workspaceProperties = new WorkspaceProperties();
        workspaceProperties.setRootDir(tempDir.toString());
        ProjectStoreRepository repository = new JsonProjectStoreRepository(objectMapper, workspaceProperties);
        service = new ProjectService(repository, workspaceProperties);
    }

    @Test
    void createProjectCreatesWorkingDirectoryAndSetsItActive() {
        Project project = service.createProject("Mon Projet");

        assertThat(project.getName()).isEqualTo("mon-projet");
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(Files.isDirectory(Path.of(project.getWorkingDirectory()))).isTrue();
        assertThat(service.getActiveProject()).contains(project);
    }

    @Test
    void createProjectSlugifiesAccentsSpacesAndUppercase() {
        Project project = service.createProject("  Été Côté Serveur !! ");

        assertThat(project.getName()).isEqualTo("ete-cote-serveur");
    }

    @Test
    void createProjectMarksSystemSlugAsElevatedWithRootWorkingDirectory() {
        Project project = service.createProject("System");

        assertThat(project.getName()).isEqualTo("system");
        assertThat(project.isElevated()).isTrue();
        // Contrairement aux autres projets, PAS de sous-dossier projects/<slug> : le cwd
        // est la racine du workspace elle-meme (voir ProjectService.ELEVATED_PROJECT_SLUG).
        assertThat(project.getWorkingDirectory()).isEqualTo(workspaceProperties.rootDirPath().toString());
    }

    @Test
    void createProjectLeavesOrdinaryProjectsNonElevated() {
        Project project = service.createProject("Mon Projet");

        assertThat(project.isElevated()).isFalse();
        assertThat(project.getWorkingDirectory()).endsWith("projects" + java.io.File.separator + "mon-projet");
    }

    @Test
    void createProjectRejectsDuplicateName() {
        service.createProject("agentvps");

        assertThatThrownBy(() -> service.createProject("AgentVPS"))
                .isInstanceOf(ProjectException.class);
    }

    @Test
    void createProjectRejectsBlankName() {
        assertThatThrownBy(() -> service.createProject("   "))
                .isInstanceOf(ProjectException.class);
    }

    @Test
    void switchProjectReactivatesAnArchivedProject() {
        service.createProject("projet-a");
        service.createProject("projet-b");
        service.archiveProject("projet-a");
        assertThat(service.getProject("projet-a").getStatus()).isEqualTo(ProjectStatus.ARCHIVED);

        Project reactivated = service.switchProject("projet-a");

        assertThat(reactivated.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(service.getActiveProject()).contains(reactivated);
    }

    @Test
    void archivingTheActiveProjectClearsTheActiveProject() {
        service.createProject("projet-a");

        service.archiveProject("projet-a");

        assertThat(service.getActiveProject()).isEmpty();
        assertThat(service.getProject("projet-a").getStatus()).isEqualTo(ProjectStatus.ARCHIVED);
    }

    @Test
    void archivingAnotherProjectKeepsTheActiveProjectUnchanged() {
        service.createProject("projet-a");
        service.createProject("projet-b");
        service.switchProject("projet-a");

        service.archiveProject("projet-b");

        assertThat(service.getActiveProject()).map(Project::getName).contains("projet-a");
    }

    @Test
    void newProjectHasNoConversationAndNoCurrentSession() {
        service.createProject("projet-a");

        assertThat(service.listConversations("projet-a")).isEmpty();
        assertThat(service.getCurrentConversation("projet-a")).isEmpty();
    }

    @Test
    void recordConversationStartAddsConversationAndMakesItCurrent() {
        service.createProject("projet-a");

        Conversation conversation = service.recordConversationStart("projet-a", "session-1", "Mise en place initiale");

        assertThat(service.listConversations("projet-a")).containsExactly(conversation);
        assertThat(service.getCurrentConversation("projet-a")).contains(conversation);
        assertThat(service.getProject("projet-a").getCurrentSessionId()).isEqualTo("session-1");
    }

    @Test
    void switchConversationRestoresAnOlderConversationByItsDisplayNumber() {
        service.createProject("projet-a");
        service.recordConversationStart("projet-a", "session-1", null);
        service.recordConversationStart("projet-a", "session-2", null);

        Conversation restored = service.switchConversation("projet-a", 1);

        assertThat(restored.getSessionId()).isEqualTo("session-1");
        assertThat(service.getProject("projet-a").getCurrentSessionId()).isEqualTo("session-1");
    }

    @Test
    void switchConversationRejectsOutOfRangeNumber() {
        service.createProject("projet-a");
        service.recordConversationStart("projet-a", "session-1", null);

        assertThatThrownBy(() -> service.switchConversation("projet-a", 2))
                .isInstanceOf(ProjectException.class);
    }

    @Test
    void startNewConversationClearsTheCurrentSessionUntilOneIsRecorded() {
        service.createProject("projet-a");
        service.recordConversationStart("projet-a", "session-1", null);

        service.startNewConversation("projet-a");

        assertThat(service.getProject("projet-a").getCurrentSessionId()).isNull();
        assertThat(service.listConversations("projet-a")).hasSize(1);
    }

    @Test
    void getProjectThrowsForAnUnknownProject() {
        assertThatThrownBy(() -> service.getProject("inconnu"))
                .isInstanceOf(ProjectException.class);
    }

    // -------------------------------------------------- Threads = projets (02/09/2026)

    @Test
    void findProjectByNameReturnsEmptyForAnUnknownProject() {
        assertThat(service.findProjectByName("inconnu")).isEmpty();
    }

    @Test
    void findProjectByNameReturnsTheProjectWhenItExists() {
        Project created = service.createProject("projet-a");

        assertThat(service.findProjectByName("projet-a")).contains(created);
    }

    @Test
    void findProjectByThreadIdReturnsEmptyWhenNoProjectHasThatThread() {
        service.createProject("projet-a");

        assertThat(service.findProjectByThreadId(42)).isEmpty();
        assertThat(service.findProjectByThreadId(null)).isEmpty();
    }

    @Test
    void setThreadIdMakesTheProjectFindableByItsThreadId() {
        Project project = service.createProject("projet-a");

        service.setThreadId("projet-a", 42);

        assertThat(service.getProject("projet-a").getTelegramThreadId()).isEqualTo(42);
        assertThat(service.findProjectByThreadId(42)).contains(project);
    }

    @Test
    void deleteProjectPermanentlyRemovesTheProjectAndItsWorkingDirectory() {
        Project project = service.createProject("projet-a");
        Path workingDirectory = Path.of(project.getWorkingDirectory());
        assertThat(Files.isDirectory(workingDirectory)).isTrue();

        service.deleteProjectPermanently("projet-a");

        assertThat(service.findProjectByName("projet-a")).isEmpty();
        assertThat(Files.exists(workingDirectory)).isFalse();
        assertThatThrownBy(() -> service.getProject("projet-a")).isInstanceOf(ProjectException.class);
    }

    @Test
    void deleteProjectPermanentlyClearsTheActiveProjectWhenItWasActive() {
        service.createProject("projet-a");

        service.deleteProjectPermanently("projet-a");

        assertThat(service.getActiveProject()).isEmpty();
    }

    @Test
    void deleteProjectPermanentlyKeepsAnotherActiveProjectUnchanged() {
        service.createProject("projet-a");
        service.createProject("projet-b");
        service.switchProject("projet-a");

        service.deleteProjectPermanently("projet-b");

        assertThat(service.getActiveProject()).map(Project::getName).contains("projet-a");
    }

    @Test
    void deleteProjectPermanentlyRejectsTheElevatedSystemProject() {
        service.createProject("system");

        assertThatThrownBy(() -> service.deleteProjectPermanently("system"))
                .isInstanceOf(ProjectException.class);
        assertThat(service.findProjectByName("system")).isPresent();
    }

    @Test
    void deleteProjectPermanentlyRemovesAllConversationHistory() {
        service.createProject("projet-a");
        service.recordConversationStart("projet-a", "session-1", null);

        service.deleteProjectPermanently("projet-a");

        assertThat(service.findProjectByName("projet-a")).isEmpty();
    }

    // ------------------------------------------------------- renforcement periodique

    @Test
    void reinforcementIsAlwaysDueForTheFirstMessageOfAConversation() {
        service.createProject("projet-a");

        assertThat(service.isReinforcementDue("projet-a", null, 10)).isTrue();
    }

    @Test
    void reinforcementIsNotDueRightAfterAConversationStarted() {
        service.createProject("projet-a");
        service.recordConversationStart("projet-a", "session-1", null);

        assertThat(service.isReinforcementDue("projet-a", "session-1", 10)).isFalse();
    }

    @Test
    void reinforcementBecomesDueOnceTheThresholdIsReached() {
        service.createProject("projet-a");
        service.recordConversationStart("projet-a", "session-1", null);
        for (int i = 0; i < 9; i++) {
            service.recordReinforcementOutcome("projet-a", "session-1", false);
        }

        assertThat(service.isReinforcementDue("projet-a", "session-1", 10)).isTrue();
    }

    @Test
    void recordReinforcementOutcomeResetsTheCounterWhenApplied() {
        service.createProject("projet-a");
        service.recordConversationStart("projet-a", "session-1", null);
        for (int i = 0; i < 9; i++) {
            service.recordReinforcementOutcome("projet-a", "session-1", false);
        }

        service.recordReinforcementOutcome("projet-a", "session-1", true);

        assertThat(service.isReinforcementDue("projet-a", "session-1", 10)).isFalse();
    }

    @Test
    void reinforcementDefaultsToDueWhenTheConversationCannotBeFound() {
        service.createProject("projet-a");

        assertThat(service.isReinforcementDue("projet-a", "session-inconnue", 10)).isTrue();
    }

    @Test
    void recordReinforcementOutcomeIsANoOpForAnUnknownSessionId() {
        service.createProject("projet-a");

        // ne doit pas lever d'exception, simplement ne rien trouver a mettre a jour
        service.recordReinforcementOutcome("projet-a", "session-inconnue", true);
    }
}
