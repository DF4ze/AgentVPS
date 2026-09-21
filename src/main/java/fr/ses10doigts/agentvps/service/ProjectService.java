package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.config.WorkspaceProperties;
import fr.ses10doigts.agentvps.model.Conversation;
import fr.ses10doigts.agentvps.model.Project;
import fr.ses10doigts.agentvps.model.ProjectStatus;
import fr.ses10doigts.agentvps.model.ProjectStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Comparator;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Gestion des projets et de leurs conversations Claude (roadmap Phase 3, points 4-7),
 * independamment de tout canal (Telegram, futur web - voir Phase 6). Mono-utilisateur
 * pour l'instant (decision Clem du 28/08/2026) : un seul "projet actif" global.
 *
 * Toute la logique metier (nommage, unicite, archivage/reactivation, historique de
 * conversations) vit ici ; ce service ne connait pas ClaudeCliService (voir
 * ProjectOnboardingService pour l'orchestration de l'appel claude a la creation).
 *
 * Le ProjectStore est charge une fois en memoire puis tenu a jour ; chaque mutation
 * est immediatement persistee (JsonProjectStoreRepository) sous le meme verrou pour
 * eviter toute ecriture concurrente incoherente (plusieurs threads Telegram possibles
 * via le long-polling du module telegram-bots-mvc).
 */
@Service
@Slf4j
public class ProjectService {

    /**
     * Slug reserve qui marque un projet comme "System" (droits elargis, voir Project.elevated
     * et memoire projet "god_mode_system_project"). Cree automatiquement au demarrage : il
     * n'est pas un projet utilisateur et ne peut pas etre cree ou gere via les commandes projet.
     * Contrairement a un projet normal, son working directory est la racine du workspace
     * (WorkspaceProperties.rootDir()) et pas un sous-dossier isole sous projectsDir() : ca
     * elargit la portee des regles Read/Write/Edit(**) du settings.json (deja relatives au cwd,
     * voir claude_fs_permissions.md) a tous les autres projets AgentVPS, sans toucher au deny
     * (secrets ~/.ssh/~/.claude/~/.ori, /etc, /root, sudo, rm -rf restent proteges partout).
     */
    public static final String ELEVATED_PROJECT_SLUG = "system";

    private final ProjectStoreRepository repository;
    private final WorkspaceProperties workspaceProperties;
    private final Object lock = new Object();
    private ProjectStore store;

    public ProjectService(ProjectStoreRepository repository, WorkspaceProperties workspaceProperties) {
        this.repository = repository;
        this.workspaceProperties = workspaceProperties;
    }

    public List<Project> listProjects() {
        synchronized (lock) {
            return List.copyOf(store().getProjects().values());
        }
    }

    public Optional<Project> getActiveProject() {
        synchronized (lock) {
            String name = store().getActiveProjectName();
            return name == null ? Optional.empty() : findProject(name);
        }
    }

    public Project getProject(String name) {
        synchronized (lock) {
            return findProject(name)
                    .orElseThrow(() -> new ProjectException("Aucun projet nomme '" + name + "'"));
        }
    }

    /**
     * Cree un nouveau projet : slug unique (nom libre accepte, ex. accents/espaces),
     * dossier de travail cree sur disque, projet defini comme projet actif. N'ajoute
     * aucune conversation (voir ProjectOnboardingService pour l'interview CLAUDE.md
     * qui demarre la premiere conversation juste apres).
     */
    public Project createProject(String rawName) {
        synchronized (lock) {
            String slug = slugify(rawName);
            if (ELEVATED_PROJECT_SLUG.equals(slug)) {
                throw reservedSystemProjectException();
            }
            if (store().getProjects().containsKey(slug)) {
                throw new ProjectException("Un projet nomme '" + slug + "' existe deja");
            }

            Path dir = workspaceProperties.projectsDir().resolve(slug);
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new ProjectException("Impossible de creer le dossier du projet : " + dir, e);
            }

            Project project = new Project();
            project.setName(slug);
            project.setStatus(ProjectStatus.ACTIVE);
            project.setCreatedAt(Instant.now());
            project.setWorkingDirectory(dir.toString());
            project.setElevated(false);

            store().getProjects().put(slug, project);
            store().setActiveProjectName(slug);
            persist();
            log.info("Projet '{}' cree (dossier {}, elevated=false)", slug, dir);
            return project;
        }
    }

    /**
     * Garantit l'existence du projet reserve "system" sans lancer d'onboarding et sans
     * modifier le projet actif. Cette initialisation est appelee au demarrage : le projet
     * sert de point d'appui aux missions transverses (notamment l'amelioration continue).
     */
    public Project ensureSystemProject() {
        synchronized (lock) {
            Optional<Project> existing = findProject(ELEVATED_PROJECT_SLUG);
            if (existing.isPresent()) {
                Project project = existing.get();
                boolean changed = false;
                if (!project.isElevated()) {
                    project.setElevated(true);
                    changed = true;
                }
                if (!workspaceProperties.rootDirPath().toString().equals(project.getWorkingDirectory())) {
                    project.setWorkingDirectory(workspaceProperties.rootDirPath().toString());
                    changed = true;
                }
                if (project.getStatus() != ProjectStatus.ACTIVE) {
                    project.setStatus(ProjectStatus.ACTIVE);
                    changed = true;
                }
                if (ELEVATED_PROJECT_SLUG.equals(store().getActiveProjectName())) {
                    store().setActiveProjectName(null);
                    changed = true;
                }
                if (changed) {
                    persist();
                    log.info("Projet system existant normalise (elevated=true, actif=false)");
                }
                return project;
            }

            Path dir = workspaceProperties.rootDirPath();
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new ProjectException("Impossible de creer le dossier du projet system : " + dir, e);
            }

            Project project = new Project();
            project.setName(ELEVATED_PROJECT_SLUG);
            project.setStatus(ProjectStatus.ACTIVE);
            project.setCreatedAt(Instant.now());
            project.setWorkingDirectory(dir.toString());
            project.setElevated(true);

            store().getProjects().put(ELEVATED_PROJECT_SLUG, project);
            persist();
            log.info("Projet system cree automatiquement (dossier {}, elevated=true)", dir);
            return project;
        }
    }

    /**
     * Definit le projet actif. Un projet archive est reactive automatiquement au
     * passage (decision Clem du 28/08/2026) : pas d'etape de restauration separee.
     */
    public Project switchProject(String name) {
        synchronized (lock) {
            Project project = getProject(name);
            assertUserManagedProject(project);
            if (project.getStatus() == ProjectStatus.ARCHIVED) {
                project.setStatus(ProjectStatus.ACTIVE);
                log.info("Projet '{}' reactive automatiquement (etait archive)", project.getName());
            }
            store().setActiveProjectName(project.getName());
            persist();
            return project;
        }
    }

    /**
     * Archive un projet (equivalent de /projet delete, reversible - voir switchProject).
     * L'historique de conversations est conserve intact. Si c'etait le projet actif,
     * plus aucun projet actif apres l'appel (l'utilisateur doit en choisir un autre).
     */
    public void archiveProject(String name) {
        synchronized (lock) {
            Project project = getProject(name);
            assertUserManagedProject(project);
            project.setStatus(ProjectStatus.ARCHIVED);
            if (project.getName().equals(store().getActiveProjectName())) {
                store().setActiveProjectName(null);
            }
            persist();
            log.info("Projet '{}' archive", project.getName());
        }
    }

    public List<Conversation> listConversations(String name) {
        synchronized (lock) {
            return List.copyOf(getProject(name).getConversations());
        }
    }

    /** Conversation actuellement selectionnee pour ce projet, vide si aucune (voir startNewConversation). */
    public Optional<Conversation> getCurrentConversation(String name) {
        synchronized (lock) {
            Project project = getProject(name);
            String currentId = project.getCurrentSessionId();
            if (currentId == null) {
                return Optional.empty();
            }
            return project.getConversations().stream()
                    .filter(c -> c.getSessionId().equals(currentId))
                    .findFirst();
        }
    }

    /**
     * Demande une nouvelle conversation pour ce projet : le prochain appel claude ne
     * devra pas passer --resume. La conversation elle-meme (avec son session_id) n'est
     * ajoutee a l'historique qu'apres coup, via recordConversationStart, une fois que
     * claude a effectivement repondu.
     */
    public void startNewConversation(String name) {
        synchronized (lock) {
            Project project = getProject(name);
            assertUserManagedProject(project);
            project.setCurrentSessionId(null);
            persist();
        }
    }

    /** Revient a une conversation existante (numero affiche a l'utilisateur, 1 = la plus ancienne). */
    public Conversation switchConversation(String name, int conversationNumber) {
        synchronized (lock) {
            Project project = getProject(name);
            assertUserManagedProject(project);
            List<Conversation> conversations = project.getConversations();
            if (conversationNumber < 1 || conversationNumber > conversations.size()) {
                throw new ProjectException(
                        "Conversation #" + conversationNumber + " introuvable pour le projet '" + project.getName() + "'");
            }
            Conversation conversation = conversations.get(conversationNumber - 1);
            project.setCurrentSessionId(conversation.getSessionId());
            persist();
            return conversation;
        }
    }

    /** Enregistre le demarrage d'une nouvelle conversation (session_id renvoye par un appel claude sans --resume). */
    public Conversation recordConversationStart(String name, String sessionId, String label) {
        synchronized (lock) {
            Project project = getProject(name);
            assertUserManagedProject(project);
            Instant now = Instant.now();
            Conversation conversation = new Conversation(sessionId, now, now, label, 0);
            project.getConversations().add(conversation);
            project.setCurrentSessionId(sessionId);
            persist();
            return conversation;
        }
    }

    /** Met a jour lastUsedAt apres un appel claude reussi sur une conversation existante. */
    public void touchConversation(String name, String sessionId) {
        synchronized (lock) {
            Project project = getProject(name);
            assertUserManagedProject(project);
            project.getConversations().stream()
                    .filter(c -> c.getSessionId().equals(sessionId))
                    .findFirst()
                    .ifPresent(c -> c.setLastUsedAt(Instant.now()));
            persist();
        }
    }

    /**
     * Indique si le renforcement periodique (rappel CLAUDE.md + permissions, injecte en
     * prefixe du message utilisateur par ChatService) doit etre applique au prochain appel.
     * Toujours vrai pour le premier message d'une conversation (sessionId null, aucun
     * historique a diluer). Pour une conversation existante, vrai des que le nombre de
     * messages ecoules depuis le dernier renforcement atteint le seuil configure
     * (voir ClaudeCliProperties.reinforcementEveryMessages).
     *
     * Lecture seule : ne modifie pas le compteur (voir recordReinforcementOutcome, appele
     * uniquement apres un appel claude reussi).
     */
    public boolean isReinforcementDue(String name, String sessionId, int everyMessages) {
        if (sessionId == null) {
            return true;
        }
        synchronized (lock) {
            Project project = getProject(name);
            return project.getConversations().stream()
                    .filter(c -> c.getSessionId().equals(sessionId))
                    .findFirst()
                    // conversation introuvable (ne devrait pas arriver) : on prefere
                    // renforcer plutot que de risquer un oubli silencieux.
                    .map(c -> c.getMessagesSinceReinforcement() + 1 >= everyMessages)
                    .orElse(true);
        }
    }

    /**
     * A appeler apres un appel claude reussi sur une conversation existante (pas pour le
     * tout premier message, cf recordConversationStart qui initialise deja le compteur a 0)
     * pour mettre a jour le compteur de renforcement : remis a 0 si le renforcement vient
     * d'etre injecte dans ce message, incremente sinon.
     */
    public void recordReinforcementOutcome(String name, String sessionId, boolean reinforcementApplied) {
        synchronized (lock) {
            Project project = getProject(name);
            assertUserManagedProject(project);
            project.getConversations().stream()
                    .filter(c -> c.getSessionId().equals(sessionId))
                    .findFirst()
                    .ifPresent(c -> c.setMessagesSinceReinforcement(
                            reinforcementApplied ? 0 : c.getMessagesSinceReinforcement() + 1));
            persist();
        }
    }

    /**
     * Variante "sans exception" de {@link #getProject(String)} (feature Threads = projets
     * du 02/09/2026, voir ProjectThreadService/RecurringTaskNotifier) : les deux appelants
     * concernes veulent un fallback silencieux (pas de Thread configure / projet supprime
     * entre-temps) plutot qu'une ProjectException a chaque fois.
     */
    public Optional<Project> findProjectByName(String name) {
        synchronized (lock) {
            return findProject(name);
        }
    }

    /**
     * Retrouve le projet associe a un sujet (topic) de forum Telegram (feature Threads =
     * projets du 02/09/2026) : c'est ce qui permet a AgentVpsTelegramController de resoudre
     * le projet concerne par un message a partir de son messageThreadId, sans passer par
     * la notion de "projet actif" global (voir Project.telegramThreadId).
     */
    public Optional<Project> findProjectByThreadId(Integer threadId) {
        if (threadId == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            return store().getProjects().values().stream()
                    .filter(p -> threadId.equals(p.getTelegramThreadId()))
                    .findFirst();
        }
    }

    /**
     * Enregistre le messageThreadId du sujet Telegram cree pour ce projet (voir
     * ProjectThreadService.createTopicForProject, seul appelant a ce jour).
     */
    public void setThreadId(String name, Integer threadId) {
        synchronized (lock) {
            Project project = getProject(name);
            assertUserManagedProject(project);
            setThreadIdInternal(project, threadId);
            persist();
            log.info("Thread Telegram associe au projet '{}' (messageThreadId={})", project.getName(), threadId);
        }
    }

    /** Association technique d'un Thread cree par le service Telegram. */
    void recordTelegramThreadId(String name, Integer threadId) {
        synchronized (lock) {
            Project project = getProject(name);
            setThreadIdInternal(project, threadId);
            persist();
        }
    }

    private static void setThreadIdInternal(Project project, Integer threadId) {
        project.setTelegramThreadId(threadId);
    }

    /**
     * Suppression REELLE et irreversible d'un projet (feature Threads = projets du
     * 02/09/2026, decision Clem : /projet delete ne se contente plus d'archiver, voir
     * archiveProject ci-dessous conserve pour compatibilite mais plus appele depuis le
     * controller) : retire le projet du store (donc tout son historique de conversations),
     * supprime son dossier de travail sur disque, et fait un best-effort pour supprimer
     * aussi les transcripts que Claude Code (le CLI) conserve de son cote sous
     * ~/.claude/projects/&lt;cwd sanitise&gt;, independamment de notre ProjectStore (voir
     * deleteClaudeCodeTranscriptsBestEffort). Le projet "system" (elevated) ne peut pas
     * etre supprime par cette voie : son working directory est la racine du workspace
     * (voir ELEVATED_PROJECT_SLUG), le supprimer supprimerait donc AgentVPS lui-meme.
     *
     * La suppression du Thread Telegram associe (si le projet en a un) n'est PAS geree
     * ici : ce service ne connait pas Telegram, voir ProjectThreadService.deleteTopic,
     * appele par AgentVpsTelegramController juste apres cette methode.
     */
    public Project deleteProjectPermanently(String name) {
        synchronized (lock) {
            Project project = getProject(name);
            assertUserManagedProject(project);

            store().getProjects().remove(project.getName());
            if (project.getName().equals(store().getActiveProjectName())) {
                store().setActiveProjectName(null);
            }
            persist();

            deleteDirectoryBestEffort(pathOrNull(project.getWorkingDirectory()), "dossier de travail", project.getName());
            deleteClaudeCodeTranscriptsBestEffort(project);

            log.info("Projet '{}' supprime definitivement (dossier {})", project.getName(), project.getWorkingDirectory());
            return project;
        }
    }

    /**
     * Best-effort : Claude Code (le CLI) conserve ses propres transcripts de conversation
     * sous ~/.claude/projects/&lt;cwd absolu sanitise&gt; (chaque caractere non
     * alphanumerique du chemin remplace par '-', convention observee du CLI), tout a fait
     * independamment de notre propre ProjectStore/historique de Conversation. Les
     * supprimer aussi rend la suppression du projet reellement irreversible cote "tout ce
     * que Claude sait de ce projet" (demande explicite de Clem), pas seulement cote
     * AgentVPS. Non bloquant si absent ou inaccessible : ~/.claude appartient a
     * l'utilisateur systeme qui lance claude (voir claude_fs_permissions.md), qui peut
     * differer de celui qui execute AgentVPS selon l'environnement (dev vs VPS).
     */
    private void deleteClaudeCodeTranscriptsBestEffort(Project project) {
        Path workingDirectory = pathOrNull(project.getWorkingDirectory());
        if (workingDirectory == null) {
            return;
        }
        String sanitized = workingDirectory.toAbsolutePath().normalize().toString()
                .replaceAll("[^a-zA-Z0-9]", "-");
        Path claudeProjectDir = Path.of(System.getProperty("user.home"), ".claude", "projects", sanitized);
        deleteDirectoryBestEffort(claudeProjectDir, "transcripts Claude Code", project.getName());
    }

    private static Path pathOrNull(String rawPath) {
        return (rawPath == null || rawPath.isBlank()) ? null : Path.of(rawPath);
    }

    private void deleteDirectoryBestEffort(Path dir, String what, String projectName) {
        if (dir == null) {
            return;
        }
        try {
            if (!Files.exists(dir)) {
                return;
            }
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        log.warn("Impossible de supprimer {} ({}) du projet '{}'", p, what, projectName, e);
                    }
                });
            }
        } catch (IOException e) {
            log.warn("Echec de la suppression du {} du projet '{}' ({})", what, projectName, dir, e);
        }
    }

    private Optional<Project> findProject(String name) {
        return Optional.ofNullable(store().getProjects().get(slugify(name)));
    }

    private static void assertUserManagedProject(Project project) {
        if (project.isElevated() || ELEVATED_PROJECT_SLUG.equals(project.getName())) {
            throw reservedSystemProjectException();
        }
    }

    private static ProjectException reservedSystemProjectException() {
        return new ProjectException("Le projet 'system' est reserve au fonctionnement interne d'AgentVPS");
    }

    private ProjectStore store() {
        if (store == null) {
            store = repository.load();
        }
        return store;
    }

    private void persist() {
        repository.save(store);
    }

    /**
     * Normalise un nom de projet libre (accents, espaces, majuscules...) en slug stable
     * utilise comme cle et comme nom de dossier : minuscules, [a-z0-9] separes par des
     * tirets simples, pas de tiret en tete/queue. Rejette un nom vide ou qui ne produirait
     * aucun caractere exploitable (ex. uniquement des symboles).
     */
    static String slugify(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new ProjectException("Le nom du projet ne peut pas etre vide");
        }
        String withoutAccents = Normalizer.normalize(rawName.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String slug = withoutAccents.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            throw new ProjectException("Nom de projet invalide : '" + rawName + "'");
        }
        return slug;
    }
}
