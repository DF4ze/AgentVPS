# Architecture logicielle — AgentVPS

*Dernière mise à jour : 02/09/2026*

## 1. Principe

AgentVPS n'est **pas** un agent IA custom : c'est une couche de pilotage à distance
(Telegram aujourd'hui, web envisagé plus tard) par-dessus le CLI **Claude Code**
déjà existant, invoqué en mode non-interactif (`claude -p`). Le raisonnement
agentique est entièrement délégué au CLI ; le service Java n'apporte que le
transport, la persistance (projets/conversations/tâches) et l'ordonnancement.

Service Spring Boot (Java 21), mono-utilisateur, déployé sur un VPS sous
l'utilisateur système dédié `agentvps`.

## 2. Flux principal

```
Telegram (long-polling)
      │  @Command (/projet, /conv, /tache) ou @Chat (message libre)
      ▼
AgentVpsTelegramController
      │
      ├─▶ ProjectService        (projet actif, conversations, session_id)
      ├─▶ ChatService           ──▶ ClaudeCliService ──▶ `claude -p ...` (ProcessBuilder)
      ├─▶ ProjectOnboardingService (interview de création de projet)
      └─▶ RecurringTaskService / RecurringTaskManager (CRUD tâches)
                                        │
                                        ▼
                              RecurringTaskScheduler (Spring TaskScheduler + CronTrigger dynamique)
                                        │
                              ┌─────────┴─────────┐
                              ▼                   ▼
                    ScriptExecutionService   AgentMissionExecutionService
                    (ProcessBuilder direct)  (délègue à ClaudeCliService)
```

Chaque appel `claude -p --output-format json [--resume <id>] [--append-system-prompt ...]
--permission-mode dontAsk --settings <fichier>` tourne dans le répertoire de
travail (`cwd`) du projet actif, ce qui fait charger automatiquement le
`CLAUDE.md` de ce projet par Claude Code.

## 3. Composants principaux

- **`AgentVpsTelegramController`** — point d'entrée unique (module maison
  `telegram-bots-mvc`). Commandes `/projet`, `/conv`, `/tache` + handler
  `@Chat` pour le message libre. Envoie un placeholder immédiat puis édite le
  message avec la réponse finale (le CLI peut prendre plusieurs dizaines de
  secondes).
- **`ProjectService`** — CRUD projets + historique de conversations, table
  `projet → session_id courant`. Mono-utilisateur : un seul "projet actif"
  global. Gère aussi le compteur de renforcement périodique et le slug réservé
  `system` (projet à droits élargis, voir §6).
- **`ChatService`** — orchestration d'un message `@Chat` : décide s'il faut
  reprendre la conversation (`--resume`) ou en démarrer une nouvelle, injecte
  le renforcement périodique si dû, choisit le fichier `--settings` (standard
  ou élargi selon `Project.elevated`).
- **`ClaudeCliService`** — seul point d'invocation du binaire `claude` (ou
  `ori claude` si `provider=OPENROUTER`). `ProcessBuilder` + timeout +
  drainage parallèle stdout/stderr (StreamGobbler) + parsing du JSON de
  sortie (`ClaudeCliResult`, contient notamment `session_id`).
- **`ProjectOnboardingService`** — interview de création de projet (premier
  appel claude d'un nouveau projet, avec un system prompt dédié).
- **`RecurringTaskService` / `RecurringTaskManager` / `RecurringTaskScheduler`**
  — couche "tâches récurrentes" (roadmap Phase 7) : CRUD + validation pure
  (`RecurringTaskService`), orchestration création+planification
  (`RecurringTaskManager`), planification live et exécution effective
  (`RecurringTaskScheduler`, un `CronTrigger` ou un `Instant` unique par
  tâche sur le `TaskScheduler` Spring). Garde-fou anti-chevauchement : un
  `Set<String>` de tâches en cours d'exécution.
- **`ScriptExecutionService`** — exécute `RecurringTask.command` en
  `ProcessBuilder` direct (sans passer par Claude Code), sous les privilèges
  du process `agentvps`. Un code de sortie non nul est un résultat métier
  normal (WARNING/CRITICAL), pas une erreur.
- **`AgentMissionExecutionService`** — exécute une tâche en mode
  `AGENT_MISSION` : délègue à `ClaudeCliService` avec un prompt libre, un
  system prompt additionnel dédié, et un timeout séparé (une mission peut
  enchaîner plusieurs appels réseau/MCP).
- **`RecurringTaskNotifier`** — notification Telegram post-exécution, isolée
  pour qu'un échec d'envoi n'écrase jamais le résultat réel du run.

## 4. Modèle de données

- `Project` (name/slug, status ACTIVE/ARCHIVED, workingDirectory, elevated,
  conversations, currentSessionId)
- `Conversation` (sessionId, createdAt, lastUsedAt, label,
  messagesSinceReinforcement)
- `RecurringTask` (name, projectName, executionMode SCRIPT/AGENT_MISSION,
  command ou missionPrompt, triggerType CRON/ONE_TIME, cronExpression ou
  scheduledAt, status, notificationPolicy, dernier résultat)

## 5. Persistance

Pas de base de données : deux fichiers JSON sous le workspace
(`WorkspaceProperties.rootDir`, `/home/agentvps/AgentVPS` en prod) :

- `projects-store.json` — table projets/conversations (`ProjectStore`, via
  `JsonProjectStoreRepository`)
- `recurring-tasks-store.json` — tâches récurrentes (`RecurringTaskStore`,
  via `JsonRecurringTaskStoreRepository`)

Chargement unique en mémoire au premier accès, puis chaque mutation est
persistée immédiatement sous un verrou `synchronized` (le long-polling
Telegram peut faire arriver plusieurs threads concurrents).

Chaque projet a en plus son propre dossier de travail
(`projects/<slug>/`, contenant son `CLAUDE.md`) sous
`WorkspaceProperties.projectsDir()`.

## 6. Sécurité et isolation

Le VPS tourne en conteneur LXC avec les user namespaces non privilégiés
désactivés : le sandbox OS natif de Claude Code (`bubblewrap`) **ne
fonctionne pas** ici. Deux couches de défense compensent, l'une système,
l'autre applicative :

1. **Isolation OS** — utilisateur Linux dédié `agentvps`, home séparé de
   `oklm` (qui héberge les autres bots du VPS). `/home/oklm` est en `700` :
   `agentvps` ne peut structurellement rien y lire, quelle que soit la
   configuration de Claude Code. Vraie barrière noyau, contrairement au
   sandbox indisponible.
2. **Permissions Claude Code** — `--permission-mode dontAsk` (headless,
   personne pour répondre à une confirmation) + `--settings <fichier
   allow/ask/deny>` (`claude-settings.json`) : `deny` protège en dur
   `~/.ssh`, `~/.claude`, `~/.ori`, `/etc`, `/root`, `sudo`, `rm -rf`, quel
   que soit le projet.

Le projet réservé **`system`** (`Project.elevated=true`, créé via `/projet
new system`) élargit la portée en changeant son `cwd` vers la racine du
workspace au lieu d'un sous-dossier isolé (les règles `Read/Write/Edit(**)`
sont déjà relatives au cwd) — le `deny` reste strictement identique. Il
utilise un fichier `--settings` dédié (`claude-settings-system.json`,
quelques commandes de diagnostic en plus) et ne voit **pas** les autres
applications du VPS (CristalBot, InstaBot... tournent sous `oklm`, home
`700`, mur noyau identique).

Un script curé (`scripts/change-project-permissions.sh`) permet au projet
`system` de modifier le `settings.local.json` d'un projet enfant en
contournant le refus natif de l'outil `Edit` de Claude Code sur les fichiers
de permissions (protection anti-auto-escalade intégrée à Claude Code) — le
script passe par un process Bash normal, avec ses propres garde-fous
(nom de projet validé, `system` explicitement exclu, écriture atomique,
journalisation).

## 7. Déploiement

Build Maven local → upload du jar → `systemctl restart agentvps`, automatisé
via une opération curée de la gateway SSH (`build-deploy:agentvps`, voir
`quick-access.md`). Service systemd `agentvps.service`, `User=agentvps`,
`Restart=on-failure`. Détail complet dans `quick-access.md`.
