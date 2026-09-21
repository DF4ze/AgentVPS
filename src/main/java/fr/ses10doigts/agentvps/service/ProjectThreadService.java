package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.config.AgentVpsTelegramProperties;
import fr.ses10doigts.agentvps.model.Project;
import fr.ses10doigts.telegrambots.exception.TelegramForumException;
import fr.ses10doigts.telegrambots.model.TelegramForumTopic;
import fr.ses10doigts.telegrambots.model.TelegramTopicIconColor;
import fr.ses10doigts.telegrambots.service.sender.TelegramSender;
import fr.ses10doigts.telegrambots.service.sender.TelegramSenderRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Feature "Threads = projets" (02/09/2026, demande de Clem) : chaque projet AgentVPS a
 * son propre sujet (topic) de forum dans le supergroupe Telegram configure
 * (agentvps.telegram.forum-chat-id, voir AgentVpsTelegramProperties), pour que chaque
 * conversation se maintienne visuellement dans un Thread separe cote Telegram - au lieu
 * d'un unique fil pour tous les projets.
 *
 * Contrairement au reste du controller (qui utilise le bean TelegramSender injecte via
 * ObjectProvider, un ContextAwareTelegramSender resolu par ThreadLocal lie au thread de
 * traitement de l'update Telegram entrant), ce service passe systematiquement par
 * TelegramSenderRegistry.getDefaultBotSender() - meme raison que RecurringTaskNotifier :
 * /projets init et la creation de Thread a la creation d'un projet doivent fonctionner de
 * facon identique quel que soit le contexte d'appel (y compris hors requete Telegram a
 * terme), sans dependre d'un ThreadLocal.
 *
 * Toutes les methodes sont "best effort" vis-a-vis de Telegram : une
 * TelegramForumException (groupe pas configure en forum, bot sans le droit "Gerer les
 * sujets", sujet deja supprime...) est journalisee et absorbee plutot que remontee -
 * l'appelant (AgentVpsTelegramController) reste toujours capable de repondre a
 * l'utilisateur, avec ou sans Thread.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectThreadService {

    /**
     * Couleurs unies disponibles pour l'icone d'un sujet (liste fermee imposee par
     * l'API Telegram, voir TelegramTopicIconColor) : choisie de façon deterministe a
     * partir du nom (slug) du projet, pour que deux appels (creation initiale, /projets
     * init qui recree un Thread supprime) retombent sur la meme couleur.
     */
    private static final TelegramTopicIconColor[] ICON_COLORS = TelegramTopicIconColor.values();

    /**
     * Emojis prefixant le titre du sujet (voir pickEmoji) : Telegram n'expose aucune
     * icone "custom emoji" utilisable sans connaitre a l'avance un identifiant de sticker
     * valide (getForumTopicIconStickers, non expose par TelegramSender) - un emoji simple
     * directement dans le titre du sujet reste lisible et distinctif sans cette
     * dependance supplementaire.
     */
    private static final String[] ICON_EMOJIS = {
            "📁", "🗂️", "🧩", "🚀", "🛠️", "🌱", "📌", "🧭", "💡", "🔭", "🧪", "⚙️"
    };

    private final AgentVpsTelegramProperties properties;
    private final ProjectService projectService;
    private final ObjectProvider<TelegramSenderRegistry> telegramSenderRegistryProvider;

    /** {@code true} si un groupe Telegram est configure pour les Threads = projets. */
    public boolean isEnabled() {
        return forumChatId() != null;
    }

    /** Identifiant du groupe Telegram configure, ou {@code null} si non configure/invalide. */
    public Long forumChatId() {
        String raw = properties.getForumChatId();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.error("agentvps.telegram.forum-chat-id invalide (pas un entier) : '{}'", raw);
            return null;
        }
    }

    /** {@code true} si {@code chatId} est le groupe Telegram configure pour les Threads = projets. */
    public boolean isForumChat(Long chatId) {
        Long forumChatId = forumChatId();
        return forumChatId != null && forumChatId.equals(chatId);
    }

    /** Indique si un projet doit etre expose dans l'interface Telegram. */
    public boolean isVisibleInTelegram(Project project) {
        return project != null
                && (!ProjectService.ELEVATED_PROJECT_SLUG.equals(project.getName())
                || properties.isSystemProjectVisible());
    }

    /**
     * Cree le sujet Telegram d'un projet et enregistre son messageThreadId sur le projet
     * (ProjectService.setThreadId). Ne fait rien (Optional vide) si les Threads ne sont
     * pas configures, si Telegram est desactive, ou en cas d'echec Telegram (log warn,
     * jamais leve vers l'appelant - voir javadoc de la classe).
     */
    public Optional<Integer> createTopicForProject(Project project) {
        if (!isVisibleInTelegram(project)) {
            log.debug("Projet '{}' masque de Telegram : aucun Thread cree", project.getName());
            return Optional.empty();
        }
        Long forumChatId = forumChatId();
        if (forumChatId == null) {
            return Optional.empty();
        }

        TelegramSender sender = defaultSender();
        if (sender == null) {
            return Optional.empty();
        }

        try {
            String title = pickEmoji(project.getName()) + " " + project.getName();
            TelegramTopicIconColor color = pickIconColor(project.getName());
            TelegramForumTopic topic = sender.createForumTopic(forumChatId, title, color, null);
            projectService.recordTelegramThreadId(project.getName(), topic.getMessageThreadId());
            log.info("Thread Telegram cree pour le projet '{}' (messageThreadId={})",
                    project.getName(), topic.getMessageThreadId());
            return Optional.ofNullable(topic.getMessageThreadId());
        } catch (TelegramForumException e) {
            log.warn("Impossible de creer le Thread Telegram pour le projet '{}' : {} (raison={})",
                    project.getName(), e.getMessage(), e.getReason());
            return Optional.empty();
        }
    }

    /**
     * Supprime definitivement le sujet Telegram d'un projet (voir
     * TelegramSender.deleteForumTopic - irreversible cote Telegram aussi). Ne fait rien
     * si {@code messageThreadId} est null ou si les Threads ne sont pas configures.
     * Echec Telegram absorbe (log warn) : ne doit jamais bloquer la suppression du
     * projet cote AgentVPS (voir AgentVpsTelegramController, callback de confirmation).
     */
    public void deleteTopic(Integer messageThreadId) {
        Long forumChatId = forumChatId();
        if (forumChatId == null || messageThreadId == null) {
            return;
        }

        TelegramSender sender = defaultSender();
        if (sender == null) {
            return;
        }

        try {
            sender.deleteForumTopic(forumChatId, messageThreadId);
            log.info("Thread Telegram supprime (messageThreadId={})", messageThreadId);
        } catch (TelegramForumException e) {
            log.warn("Impossible de supprimer le Thread Telegram (messageThreadId={}) : {} (raison={})",
                    messageThreadId, e.getMessage(), e.getReason());
        }
    }

    /**
     * Verifie qu'un sujet existe toujours cote Telegram (utilise par /projets init pour
     * decider s'il faut recreer un Thread dont l'id est connu cote AgentVPS mais qui a pu
     * etre supprime manuellement cote Telegram entre-temps). L'API Bot Telegram n'expose
     * aucun "getForumTopic" : on sonde via reopenForumTopic, idempotent et sans effet de
     * bord visible sur un sujet deja ouvert (pas de message de service Telegram), qui
     * echoue avec TelegramForumException si le sujet n'existe plus.
     */
    public boolean topicStillExists(Integer messageThreadId) {
        Long forumChatId = forumChatId();
        if (forumChatId == null || messageThreadId == null) {
            return false;
        }

        TelegramSender sender = defaultSender();
        if (sender == null) {
            return false;
        }

        try {
            sender.reopenForumTopic(forumChatId, messageThreadId);
            return true;
        } catch (TelegramForumException e) {
            log.info("Thread Telegram (messageThreadId={}) introuvable cote Telegram ({}) : a recreer",
                    messageThreadId, e.getMessage());
            return false;
        }
    }

    private TelegramSender defaultSender() {
        TelegramSenderRegistry registry = telegramSenderRegistryProvider.getIfAvailable();
        if (registry == null) {
            log.warn("TelegramSenderRegistry indisponible (telegram.enabled=false ?) : operation sur les Threads ignoree");
            return null;
        }
        return registry.getDefaultBotSender();
    }

    private static TelegramTopicIconColor pickIconColor(String slug) {
        int index = Math.floorMod(slug.hashCode(), ICON_COLORS.length);
        return ICON_COLORS[index];
    }

    private static String pickEmoji(String slug) {
        int index = Math.floorMod(slug.hashCode() * 31 + 7, ICON_EMOJIS.length);
        return ICON_EMOJIS[index];
    }
}
