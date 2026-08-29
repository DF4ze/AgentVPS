package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.model.NotificationPolicy;
import fr.ses10doigts.agentvps.model.Project;
import fr.ses10doigts.agentvps.model.ProjectStatus;
import fr.ses10doigts.agentvps.model.RecurringTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Couvre l'assistant conversationnel /tache new (voir RecurringTaskCreationWizard) :
 * chaque etape, ses validations, l'annulation a tout moment, et le rattachement par
 * defaut au projet actif (demande de Clem le 29/08/2026).
 */
@ExtendWith(MockitoExtension.class)
class RecurringTaskCreationWizardTest {

    private static final Long CHAT_ID = 10L;

    @Mock
    private RecurringTaskManager recurringTaskManager;

    @Mock
    private RecurringTaskService recurringTaskService;

    @Mock
    private ProjectService projectService;

    private RecurringTaskCreationWizard wizard;

    @BeforeEach
    void setUp() {
        wizard = new RecurringTaskCreationWizard(recurringTaskManager, recurringTaskService, projectService);
        lenient().when(projectService.listProjects()).thenReturn(List.of(project("maintenance"), project("web")));
        lenient().when(recurringTaskService.findTask(any())).thenReturn(Optional.empty());
    }

    // -------------------------------------------------------------- start()

    @Test
    void startRefusesToBeginWhenNoProjectExistsYet() {
        when(projectService.listProjects()).thenReturn(List.of());

        String reply = wizard.start(CHAT_ID);

        assertThat(reply).contains("/projet new");
        assertThat(wizard.isActive(CHAT_ID)).isFalse();
    }

    @Test
    void startOpensASessionAndAsksForAName() {
        String reply = wizard.start(CHAT_ID);

        assertThat(reply).contains("Quel nom veux-tu lui donner");
        assertThat(wizard.isActive(CHAT_ID)).isTrue();
    }

    // ----------------------------------------------------------- annulation

    @Test
    void cancelWordEndsTheSessionAtAnyStep() {
        wizard.start(CHAT_ID);
        wizard.handleReply(CHAT_ID, "healthcheck");

        String reply = wizard.handleReply(CHAT_ID, "annuler");

        assertThat(reply).isEqualTo("Creation annulee.");
        assertThat(wizard.isActive(CHAT_ID)).isFalse();
    }

    @Test
    void handleReplyWithoutAnActiveSessionReturnsNull() {
        assertThat(wizard.handleReply(CHAT_ID, "healthcheck")).isNull();
    }

    // ------------------------------------------------------------------ nom

    @Test
    void blankNameIsRejectedAndSessionStaysOnTheSameStep() {
        wizard.start(CHAT_ID);

        String reply = wizard.handleReply(CHAT_ID, "   ");

        assertThat(reply).contains("ne peut pas etre vide");
        assertThat(wizard.isActive(CHAT_ID)).isTrue();
    }

    @Test
    void duplicateNameIsRejected() {
        when(recurringTaskService.findTask("healthcheck")).thenReturn(Optional.of(new RecurringTask()));
        wizard.start(CHAT_ID);

        String reply = wizard.handleReply(CHAT_ID, "healthcheck");

        assertThat(reply).contains("s'appelle deja");
    }

    // --------------------------------------------------- projet par defaut

    @Test
    void nameStepSkipsToScriptAndUsesTheActiveProjectWhenOneExists() {
        when(projectService.getActiveProject()).thenReturn(Optional.of(project("maintenance")));
        wizard.start(CHAT_ID);

        String reply = wizard.handleReply(CHAT_ID, "healthcheck");

        assertThat(reply).contains("projet actif 'maintenance'").contains("Quelle commande");
    }

    @Test
    void nameStepAsksForAProjectWhenNoneIsActive() {
        when(projectService.getActiveProject()).thenReturn(Optional.empty());
        wizard.start(CHAT_ID);

        String reply = wizard.handleReply(CHAT_ID, "healthcheck");

        assertThat(reply).contains("Aucun projet actif").contains("maintenance").contains("web");
    }

    @Test
    void unknownProjectIsRejectedWhenAskedExplicitly() {
        when(projectService.getActiveProject()).thenReturn(Optional.empty());
        wizard.start(CHAT_ID);
        wizard.handleReply(CHAT_ID, "healthcheck");

        String reply = wizard.handleReply(CHAT_ID, "inconnu");

        assertThat(reply).contains("Projet inconnu");
    }

    // -------------------------------------------------------------- script

    @Test
    void blankScriptIsRejected() {
        when(projectService.getActiveProject()).thenReturn(Optional.of(project("maintenance")));
        wizard.start(CHAT_ID);
        wizard.handleReply(CHAT_ID, "healthcheck");

        String reply = wizard.handleReply(CHAT_ID, "  ");

        assertThat(reply).contains("ne peut pas etre vide");
    }

    // --------------------------------------------------------- notification

    @Test
    void invalidNotificationChoiceIsRejected() {
        goToNotificationStep();

        String reply = wizard.handleReply(CHAT_ID, "4");

        assertThat(reply).contains("1, 2 ou 3");
    }

    // ----------------------------------------------------------- frequence

    @Test
    void hourlyFrequencyGoesStraightToRecap() {
        goToFrequencyStep();

        String reply = wizard.handleReply(CHAT_ID, "2");

        assertThat(reply).contains("Recapitulatif").contains("toutes les heures").contains("On cree la tache");
    }

    @Test
    void invalidFrequencyModeIsRejected() {
        goToFrequencyStep();

        String reply = wizard.handleReply(CHAT_ID, "9");

        assertThat(reply).contains("1, 2, 3 ou 4");
    }

    @Test
    void dailyFrequencyAsksForATimeThenRecaps() {
        goToFrequencyStep();
        wizard.handleReply(CHAT_ID, "1");

        String invalid = wizard.handleReply(CHAT_ID, "pas une heure");
        assertThat(invalid).contains("Format invalide");

        String reply = wizard.handleReply(CHAT_ID, "06:00");

        assertThat(reply).contains("Recapitulatif").contains("tous les jours a 06:00");
    }

    @Test
    void minutesFrequencyValidatesRangeThenRecaps() {
        goToFrequencyStep();
        wizard.handleReply(CHAT_ID, "3");

        assertThat(wizard.handleReply(CHAT_ID, "abc")).contains("nombre entre 1 et 59");
        assertThat(wizard.handleReply(CHAT_ID, "0")).contains("entre 1 et 59");
        assertThat(wizard.handleReply(CHAT_ID, "60")).contains("entre 1 et 59");

        String reply = wizard.handleReply(CHAT_ID, "15");

        assertThat(reply).contains("Recapitulatif").contains("toutes les 15 minutes");
    }

    @Test
    void customCronIsValidatedAgainstSpringCronExpressionThenRecaps() {
        goToFrequencyStep();
        wizard.handleReply(CHAT_ID, "4");

        String invalid = wizard.handleReply(CHAT_ID, "pas du tout un cron");
        assertThat(invalid).contains("Expression cron invalide");

        String reply = wizard.handleReply(CHAT_ID, "0 0 6 * * *");

        assertThat(reply).contains("Recapitulatif").contains("cron personnalise : 0 0 6 * * *");
    }

    // ------------------------------------------------------------- confirm

    @Test
    void confirmingCreatesTheTaskAndEndsTheSession() {
        goToConfirmStep();
        RecurringTask created = new RecurringTask();
        created.setName("healthcheck");
        when(recurringTaskManager.createTask(
                eq("healthcheck"), eq("maintenance"), eq("./health_check.sh"), eq("0 0 * * * *"), eq(NotificationPolicy.ON_ISSUE), any()))
                .thenReturn(created);

        String reply = wizard.handleReply(CHAT_ID, "oui");

        assertThat(reply).contains("healthcheck' creee et active");
        assertThat(wizard.isActive(CHAT_ID)).isFalse();
    }

    @Test
    void decliningAtConfirmCancelsWithoutCreatingAnything() {
        goToConfirmStep();

        String reply = wizard.handleReply(CHAT_ID, "non");

        assertThat(reply).isEqualTo("Creation annulee.");
        assertThat(wizard.isActive(CHAT_ID)).isFalse();
        verify(recurringTaskManager, never()).createTask(any(), any(), any(), any(), any(), any());
    }

    @Test
    void invalidConfirmAnswerReasksWithoutEndingTheSession() {
        goToConfirmStep();

        String reply = wizard.handleReply(CHAT_ID, "peut-etre");

        assertThat(reply).contains("\"oui\" ou \"non\"");
        assertThat(wizard.isActive(CHAT_ID)).isTrue();
    }

    @Test
    void creationFailureIsReportedAndSessionEndsAnyway() {
        goToConfirmStep();
        when(recurringTaskManager.createTask(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RecurringTaskException("Projet 'maintenance' introuvable"));

        String reply = wizard.handleReply(CHAT_ID, "oui");

        assertThat(reply).contains("Erreur lors de la creation").contains("introuvable");
        assertThat(wizard.isActive(CHAT_ID)).isFalse();
    }

    // ------------------------------------------------------------------ cancel()

    @Test
    void cancelRemovesTheSessionExplicitly() {
        wizard.start(CHAT_ID);

        wizard.cancel(CHAT_ID);

        assertThat(wizard.isActive(CHAT_ID)).isFalse();
    }

    // ----------------------------------------------------------------- helpers

    private void goToNotificationStep() {
        when(projectService.getActiveProject()).thenReturn(Optional.of(project("maintenance")));
        wizard.start(CHAT_ID);
        wizard.handleReply(CHAT_ID, "healthcheck");
        wizard.handleReply(CHAT_ID, "./health_check.sh");
    }

    private void goToFrequencyStep() {
        goToNotificationStep();
        wizard.handleReply(CHAT_ID, "2");
    }

    private void goToConfirmStep() {
        goToFrequencyStep();
        wizard.handleReply(CHAT_ID, "2");
    }

    private static Project project(String name) {
        Project project = new Project();
        project.setName(name);
        project.setStatus(ProjectStatus.ACTIVE);
        project.setCreatedAt(Instant.now());
        project.setWorkingDirectory("/home/agentvps/AgentVPS/projects/" + name);
        return project;
    }
}
