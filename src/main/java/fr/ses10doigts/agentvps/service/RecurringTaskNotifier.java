package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.config.RecurringTaskProperties;
import fr.ses10doigts.agentvps.model.NotificationPolicy;
import fr.ses10doigts.agentvps.model.RecurringTask;
import fr.ses10doigts.agentvps.model.RecurringTaskRunOutcome;
import fr.ses10doigts.telegrambots.service.sender.TelegramSender;
import fr.ses10doigts.telegrambots.service.sender.TelegramSenderRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Decide si un message Telegram automatique doit etre envoye apres l'execution d'une
 * tache recurrente (RecurringTask.notificationPolicy, decision Clem du 29/08/2026 :
 * configurable par tache) et l'envoie a agentvps.recurring-tasks.notification-chat-id.
 *
 * Depend de TelegramSenderRegistry (pas du bean TelegramSender injecte partout ailleurs
 * dans le code - voir AgentVpsTelegramController) et appelle explicitement
 * getDefaultBotSender() - bug trouve en prod le 29/08/2026 : le bean TelegramSender par
 * defaut est un ContextAwareTelegramSender, qui a besoin qu'un "bot courant" soit lie au
 * thread en cours via CurrentTelegramBotContext (ThreadLocal, normalement bind/clear par
 * TelegramPollingBotAdapter autour du traitement d'un update entrant). Ce composant est
 * invoque depuis le thread pool du scheduler (RecurringTaskScheduler, TaskScheduler
 * dedie dans SchedulingConfig), qui ne traite jamais d'update Telegram entrant : rien n'y
 * lie jamais de bot courant, d'ou un IllegalStateException("No current Telegram bot is
 * bound to the current thread") au premier declenchement cron reel. TelegramSenderRegistry
 * .getDefaultBotSender() renvoie directement un DefaultTelegramSender (client HTTP lie a
 * un token de bot, sans aucune dependance a un ThreadLocal) - la bonne brique pour un
 * envoi depuis un contexte hors requete Telegram.
 *
 * TelegramSenderRegistry resolu via ObjectProvider.getIfAvailable() (pas getObject()) :
 * a la difference d'AgentVpsTelegramController (dont les handlers ne s'executent que si
 * Telegram est actif, puisque c'est lui qui les declenche), ce composant est appele
 * depuis un declenchement cron - telegram.enabled peut tres bien etre false (ex. tests,
 * environnement de dev) sans que ca doive faire echouer l'execution de la tache
 * elle-meme ; on se contente alors de logger et de ne rien envoyer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecurringTaskNotifier {

    private final ObjectProvider<TelegramSenderRegistry> telegramSenderRegistryProvider;
    private final RecurringTaskProperties properties;

    public void notify(RecurringTask task, RecurringTaskRunOutcome outcome) {
        if (!shouldSend(task.getNotificationPolicy(), outcome)) {
            return;
        }

        String rawChatId = properties.getNotificationChatId();
        if (rawChatId == null || rawChatId.isBlank()) {
            log.warn("Notification due pour la tache '{}' (statut={}) mais aucun "
                    + "agentvps.recurring-tasks.notification-chat-id configure : message non envoye",
                    task.getName(), outcome.status());
            return;
        }

        long chatId;
        try {
            chatId = Long.parseLong(rawChatId.trim());
        } catch (NumberFormatException e) {
            log.error("agentvps.recurring-tasks.notification-chat-id invalide (pas un entier) : '{}'", rawChatId);
            return;
        }

        TelegramSenderRegistry senderRegistry = telegramSenderRegistryProvider.getIfAvailable();
        if (senderRegistry == null) {
            log.warn("TelegramSenderRegistry indisponible (telegram.enabled=false ?) : notification de la "
                    + "tache '{}' non envoyee", task.getName());
            return;
        }

        TelegramSender sender = senderRegistry.getDefaultBotSender();
        sender.sendMessage(chatId, formatMessage(task, outcome));
    }

    private static boolean shouldSend(NotificationPolicy policy, RecurringTaskRunOutcome outcome) {
        return switch (policy) {
            case ALWAYS -> true;
            case ON_ISSUE -> outcome.status().isIssue();
            case NEVER -> false;
        };
    }

    private static String formatMessage(RecurringTask task, RecurringTaskRunOutcome outcome) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tache recurrente '").append(task.getName()).append("' : ").append(outcome.status());
        if (outcome.exitCode() != null) {
            sb.append(" (code ").append(outcome.exitCode()).append(')');
        }
        String detail = (outcome.errorMessage() != null && !outcome.errorMessage().isBlank())
                ? outcome.errorMessage()
                : outcome.outputSummary();
        if (detail != null && !detail.isBlank()) {
            sb.append('\n').append(detail);
        }
        return sb.toString().trim();
    }
}
