package fr.ses10doigts.agentvps.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration du moteur de taches recurrentes (roadmap Phase 7). Voir
 * RecurringTaskScheduler (execution) et RecurringTaskNotifier (notifications).
 */
@Data
@ConfigurationProperties(prefix = "agentvps.recurring-tasks")
public class RecurringTaskProperties {

    /** Delai maximum (secondes) avant destruction forcee du process d'un script en cours. */
    private int timeoutSeconds = 120;

    /** Taille du pool de threads dedie a la planification/execution des taches recurrentes. */
    private int schedulerPoolSize = 4;

    /**
     * Identifiant de chat Telegram (numerique) vers lequel envoyer les notifications
     * automatiques (voir RecurringTaskNotifier). En String (pas Long), meme choix que
     * les autres champs optionnels de ce projet (ex. ClaudeCliProperties.settingsPath) :
     * un Long lie directement via @ConfigurationProperties echoue au demarrage si la
     * valeur resolue est une chaine vide (placeholder ${VAR:} sans env var definie),
     * alors qu'une String vide se teste simplement avec isBlank(). Mono-utilisateur pour
     * l'instant (meme decision que ProjectStore, voir memoire projet
     * "roadmap_phase_status") : un seul destinataire, typiquement le meme identifiant que
     * telegram.bots[0].security.allowed-user-ids[0]. Vide/blank = notifications
     * desactivees (un warning est logue au premier envoi tente).
     */
    private String notificationChatId;
}
