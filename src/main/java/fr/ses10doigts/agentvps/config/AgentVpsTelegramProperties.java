package fr.ses10doigts.agentvps.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration de la feature "Threads = projets" (02/09/2026, voir memoire projet
 * "telegram_threads_projects" et ProjectThreadService) : chaque projet AgentVPS est
 * miroite par un sujet (topic) de forum dans un supergroupe Telegram dedie, permis
 * uniquement par un supergroupe avec "Sujets" actives - impossible dans une conversation
 * privee (voir TelegramForumTopic/TelegramSender.createForumTopic du module
 * telegram-bots-mvc 1.8.0).
 */
@Data
@ConfigurationProperties(prefix = "agentvps.telegram")
public class AgentVpsTelegramProperties {

    /**
     * Identifiant Telegram (numerique, negatif pour un supergroupe, ex. -1001234567890)
     * du groupe utilise pour les Threads = projets. En String (pas Long), meme choix que
     * RecurringTaskProperties.notificationChatId : un Long lie directement via
     * @ConfigurationProperties echoue au demarrage si la valeur resolue est une chaine
     * vide (placeholder ${VAR:} sans env var definie), alors qu'une String vide se teste
     * simplement avec isBlank(). Vide/blank = Threads desactives : /projet garde son
     * comportement complet (list/new/delete/<nom>) partout, comme avant cette feature.
     */
    private String forumChatId;

    /**
     * Expose ou non le projet reserve "system" dans les vues et Threads Telegram.
     * Desactive par defaut : le projet reste present et utilisable en interne par les
     * missions techniques, mais n'est pas un projet pilotable par l'utilisateur.
     */
    private boolean systemProjectVisible = false;
}
