package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.model.NotificationPolicy;
import fr.ses10doigts.agentvps.model.Project;
import fr.ses10doigts.agentvps.model.RecurringTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Assistant conversationnel pour /tache new (demande de Clem le 29/08/2026 : le
 * one-liner "/tache new &lt;nom&gt; &lt;projet&gt; &lt;script&gt; &lt;notif&gt; &lt;cron...&gt;"
 * etait trop rebutant, en particulier taper une expression cron a la main). Remplace
 * entierement l'ancienne syntaxe positionnelle : /tache new pose desormais une question
 * a la fois (nom, projet, commande, notification, frequence), avec des menus numerotes
 * pour la frequence (quotidien/horaire/toutes les N minutes/cron avance) plutot que
 * d'exiger un format Spring a 6 champs des le depart.
 *
 * Etat de conversation garde EN MEMOIRE (pas persiste) par chatId : un redemarrage du
 * service en plein assistant force l'utilisateur a refaire /tache new - compromis
 * assume, l'interaction est courte (quelques messages) et ne justifie pas une
 * persistance dediee, contrairement au ProjectStore/RecurringTaskStore.
 *
 * Interception faite par AgentVpsTelegramController.chat() (voir isActive/handleReply) :
 * tant qu'une session est active pour un chatId, tout message libre de ce chat est
 * consomme par l'assistant plutot que transmis a claude -p.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecurringTaskCreationWizard {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT);

    private final RecurringTaskManager recurringTaskManager;
    private final RecurringTaskService recurringTaskService;
    private final ProjectService projectService;

    private final Map<Long, WizardState> sessions = new ConcurrentHashMap<>();

    public boolean isActive(Long chatId) {
        return sessions.containsKey(chatId);
    }

    public void cancel(Long chatId) {
        sessions.remove(chatId);
    }

    /**
     * Demarre l'assistant. Ne cree PAS de session si aucun projet n'existe encore (une
     * tache recurrente n'a pas de sens sans repertoire de travail) - renvoie directement
     * un message d'orientation vers /projet new a la place.
     */
    public String start(Long chatId) {
        List<String> projectNames = projectNames();
        if (projectNames.isEmpty()) {
            return "Aucun projet n'existe encore : cree-en un d'abord avec /projet new <nom>, puis relance /tache new.";
        }
        sessions.put(chatId, new WizardState());
        return "Creons une nouvelle tache recurrente ! (tape \"annuler\" a tout moment pour arreter)\n\n"
                + "Quel nom veux-tu lui donner ?";
    }

    /** A appeler uniquement quand isActive(chatId) est vrai. Fait avancer la session d'une etape et renvoie le prochain message a envoyer. */
    public String handleReply(Long chatId, String text) {
        WizardState state = sessions.get(chatId);
        if (state == null) {
            return null;
        }
        String trimmed = text == null ? "" : text.trim();
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (normalized.equals("annuler") || normalized.equals("stop") || normalized.equals("cancel")) {
            sessions.remove(chatId);
            return "Creation annulee.";
        }

        return switch (state.step) {
            case NAME -> handleName(state, trimmed);
            case PROJECT -> handleProject(state, trimmed);
            case SCRIPT -> handleScript(state, trimmed);
            case NOTIFICATION -> handleNotification(state, trimmed);
            case FREQUENCY_MODE -> handleFrequencyMode(state, trimmed);
            case FREQUENCY_DAILY_TIME -> handleDailyTime(state, trimmed);
            case FREQUENCY_MINUTES -> handleMinutes(state, trimmed);
            case FREQUENCY_CUSTOM_CRON -> handleCustomCron(state, trimmed);
            case CONFIRM -> handleConfirm(chatId, state, normalized);
        };
    }

    private String handleName(WizardState state, String name) {
        if (name.isBlank()) {
            return "Le nom ne peut pas etre vide. Comment veux-tu l'appeler ?";
        }
        if (recurringTaskService.findTask(name).isPresent()) {
            return "Une tache s'appelle deja '" + name + "'. Choisis un autre nom :";
        }
        state.name = name;

        // Par defaut, la tache est rattachee au projet actif (demande de Clem le 29/08/2026) :
        // pas besoin de redemander un projet que l'utilisateur vient de choisir avec /projet.
        // On ne demande explicitement le projet que s'il n'y en a pas d'actif.
        Optional<Project> active = projectService.getActiveProject();
        if (active.isPresent()) {
            state.projectName = active.get().getName();
            state.step = Step.SCRIPT;
            return "Elle sera rattachee au projet actif '" + state.projectName + "'.\n\n"
                    + "Quelle commande faut-il executer, relative au dossier du projet ? Exemple : ./health_check.sh";
        }
        state.step = Step.PROJECT;
        return "Aucun projet actif pour le moment. Sur quel projet doit-elle tourner ? Projets disponibles : "
                + String.join(", ", projectNames());
    }

    private String handleProject(WizardState state, String projectName) {
        if (!projectNames().contains(projectName)) {
            return "Projet inconnu. Choisis parmi : " + String.join(", ", projectNames());
        }
        state.projectName = projectName;
        state.step = Step.SCRIPT;
        return "Quelle commande faut-il executer, relative au dossier du projet ? Exemple : ./health_check.sh";
    }

    private String handleScript(WizardState state, String command) {
        if (command.isBlank()) {
            return "La commande ne peut pas etre vide. Quelle commande faut-il executer ?";
        }
        state.command = command;
        state.step = Step.NOTIFICATION;
        return "Quand veux-tu etre notifie sur Telegram ?\n"
                + "1. A chaque execution\n"
                + "2. Seulement en cas de souci (recommande)\n"
                + "3. Jamais\n"
                + "Reponds avec 1, 2 ou 3.";
    }

    private String handleNotification(WizardState state, String choice) {
        NotificationPolicy policy = switch (choice) {
            case "1" -> NotificationPolicy.ALWAYS;
            case "2" -> NotificationPolicy.ON_ISSUE;
            case "3" -> NotificationPolicy.NEVER;
            default -> null;
        };
        if (policy == null) {
            return "Reponds avec 1, 2 ou 3 : a quelle frequence veux-tu etre notifie ?";
        }
        state.notificationPolicy = policy;
        state.step = Step.FREQUENCY_MODE;
        return "A quelle frequence doit-elle tourner ?\n"
                + "1. Tous les jours a une heure precise\n"
                + "2. Toutes les heures\n"
                + "3. Toutes les N minutes\n"
                + "4. Expression cron personnalisee (avance)\n"
                + "Reponds avec 1, 2, 3 ou 4.";
    }

    private String handleFrequencyMode(WizardState state, String choice) {
        return switch (choice) {
            case "1" -> {
                state.step = Step.FREQUENCY_DAILY_TIME;
                yield "A quelle heure ? (format HH:mm, ex. 06:00)";
            }
            case "2" -> {
                state.cronExpression = "0 0 * * * *";
                state.frequencyDescription = "toutes les heures";
                state.step = Step.CONFIRM;
                yield recap(state);
            }
            case "3" -> {
                state.step = Step.FREQUENCY_MINUTES;
                yield "Toutes les combien de minutes ? (entre 1 et 59)";
            }
            case "4" -> {
                state.step = Step.FREQUENCY_CUSTOM_CRON;
                yield "Entre ton expression cron (format Spring, 6 champs : secondes minutes heures "
                        + "jour-du-mois mois jour-de-semaine, ex. 0 0 6 * * * pour tous les jours a 6h) :";
            }
            default -> "Reponds avec 1, 2, 3 ou 4 : a quelle frequence doit-elle tourner ?";
        };
    }

    private String handleDailyTime(WizardState state, String rawTime) {
        LocalTime time;
        try {
            time = LocalTime.parse(rawTime, TIME_FORMAT);
        } catch (DateTimeParseException e) {
            return "Format invalide, attendu HH:mm (ex. 06:00). A quelle heure ?";
        }
        state.cronExpression = "0 " + time.getMinute() + " " + time.getHour() + " * * *";
        state.frequencyDescription = "tous les jours a " + time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT));
        state.step = Step.CONFIRM;
        return recap(state);
    }

    private String handleMinutes(WizardState state, String rawMinutes) {
        int minutes;
        try {
            minutes = Integer.parseInt(rawMinutes);
        } catch (NumberFormatException e) {
            return "Reponds avec un nombre entre 1 et 59. Toutes les combien de minutes ?";
        }
        if (minutes < 1 || minutes > 59) {
            return "Le nombre doit etre entre 1 et 59. Toutes les combien de minutes ?";
        }
        state.cronExpression = "0 */" + minutes + " * * * *";
        state.frequencyDescription = "toutes les " + minutes + " minutes";
        state.step = Step.CONFIRM;
        return recap(state);
    }

    private String handleCustomCron(WizardState state, String rawCron) {
        String validated;
        try {
            validated = RecurringTaskService.validateCron(rawCron);
        } catch (RecurringTaskException e) {
            return e.getMessage() + "\nReessaie :";
        }
        state.cronExpression = validated;
        state.frequencyDescription = "cron personnalise : " + validated;
        state.step = Step.CONFIRM;
        return recap(state);
    }

    private String handleConfirm(Long chatId, WizardState state, String normalized) {
        if (normalized.equals("oui") || normalized.equals("o") || normalized.equals("yes") || normalized.equals("y")) {
            sessions.remove(chatId);
            try {
                RecurringTask task = recurringTaskManager.createTask(
                        state.name, state.projectName, state.command, state.cronExpression, state.notificationPolicy, null);
                return "Tache '" + task.getName() + "' creee et active ! (" + state.frequencyDescription + ")";
            } catch (RecurringTaskException e) {
                log.error("Echec de la creation de la tache '{}' via l'assistant", state.name, e);
                return "Erreur lors de la creation : " + e.getMessage();
            }
        }
        if (normalized.equals("non") || normalized.equals("n") || normalized.equals("no")) {
            sessions.remove(chatId);
            return "Creation annulee.";
        }
        return "Reponds par \"oui\" ou \"non\" : on cree la tache ?";
    }

    private String recap(WizardState state) {
        return "Recapitulatif :\n"
                + "Nom : " + state.name + "\n"
                + "Projet : " + state.projectName + "\n"
                + "Commande : " + state.command + "\n"
                + "Notification : " + describePolicy(state.notificationPolicy) + "\n"
                + "Frequence : " + state.frequencyDescription + "\n\n"
                + "On cree la tache ? (oui/non)";
    }

    private List<String> projectNames() {
        return projectService.listProjects().stream().map(Project::getName).collect(Collectors.toList());
    }

    private static String describePolicy(NotificationPolicy policy) {
        return switch (policy) {
            case ALWAYS -> "a chaque execution";
            case ON_ISSUE -> "en cas de souci";
            case NEVER -> "jamais";
        };
    }

    private enum Step {
        NAME, PROJECT, SCRIPT, NOTIFICATION, FREQUENCY_MODE, FREQUENCY_DAILY_TIME, FREQUENCY_MINUTES, FREQUENCY_CUSTOM_CRON, CONFIRM
    }

    private static final class WizardState {
        private Step step = Step.NAME;
        private String name;
        private String projectName;
        private String command;
        private NotificationPolicy notificationPolicy;
        private String cronExpression;
        private String frequencyDescription;
    }
}
