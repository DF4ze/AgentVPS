# Connaissance du projet — AgentVPS

*Dernière mise à jour : 21/09/2026*

Ce document rassemble les faits utiles pour reprendre le développement ou le
diagnostic d'AgentVPS dans une nouvelle discussion. Il complète
`architecture.md` (structure), `choix-implementation.md` (décisions) et
`installation.md` (procédure).

## État général

- Service Spring Boot Java 21, mono-utilisateur, exécuté par systemd sous
  l'utilisateur Linux `agentvps`.
- AgentVPS ne raisonne pas lui-même : il orchestre le CLI Claude Code via
  `claude -p` (ou `ori claude` quand le provider est `OPENROUTER`).
- Telegram est le canal actuel, avec commandes `/projet`, `/conv`, `/tache` et
  les messages libres traités par `@Chat`.
- La persistance est constituée de fichiers JSON dans le workspace, pas d'une
  base de données.
- Le dépôt compile avec Maven Wrapper, mais dépend du module local
  `fr.ses10doigts:telegram-bots-mvc`, qui doit être installé dans le dépôt
  Maven local avant le build.

## Build et vérification

Depuis la racine du dépôt :

```bash
./mvnw test
./mvnw clean package
```

Le JAR produit est `target/agentvps.jar`. Lors de la dernière vérification,
les 257 tests automatisés passaient. Cela ne remplace pas une validation
réelle avec un bot Telegram, le CLI Claude et un service systemd.

## Configuration : points importants

Le fichier `src/main/resources/application.template.yaml` est un modèle. Il
n'est pas chargé automatiquement sous ce nom. En production, utiliser un
`application.yml` ou `application.yaml` externe dans le répertoire de travail
du service, ou définir les variables d'environnement dans systemd.

Les propriétés AgentVPS sont sous `agentvps`. Le bloc Telegram est au même
niveau que `claude`, jamais à l'intérieur de `agentvps.claude` :

```yaml
agentvps:
  claude:
    provider: OPENROUTER
    open-router-model: "openai/gpt-5.6-luna"
    timeout-seconds: 600
    capture-conversation-logs: true

  telegram:
    forum-chat-id: "-1001234567890"
    system-project-visible: true
```

Pièges constatés :

- `timeout-seconds` est au pluriel ; `timeout-second` ne renseigne pas la
  propriété Java `timeoutSeconds`.
- `agentvps.telegram.system-project-visible` est différent d'une propriété
  `agentvps.claude.telegram...` mal indentée.
- `system-project-visible` vaut `false` par défaut.
- La création des Threads nécessite aussi `forum-chat-id`, un supergroupe
  Telegram avec les sujets activés et les droits administrateur du bot pour
  gérer les sujets.
- Une variable définie dans un shell interactif n'est pas forcément visible
  par systemd. Vérifier l'unité ou son `EnvironmentFile`, puis faire
  `systemctl daemon-reload` si l'unité a changé et redémarrer le service.

## Initialisation au démarrage

L'ordre fonctionnel est le suivant :

1. `SystemProjectBootstrap` garantit que le projet réservé `system` existe
   dans `projects-store.json`.
2. `ContinuousImprovementMissionBootstrap` crée la tâche
   `amelioration-continue` si elle n'existe pas, mais la laisse désactivée.
3. `SystemProjectTelegramTopicBootstrap` crée le Thread Telegram de `system`
   si la visibilité est activée et qu'un forum est configuré.

Les deux premières initialisations sont idempotentes. Le projet system est
créé avec le workspace comme dossier de travail, sans onboarding et sans
devenir le projet actif.

## Projet réservé `system`

`system` est un projet technique, identifié par le slug réservé
`ProjectService.ELEVATED_PROJECT_SLUG`. Il ne doit jamais être créé, sélectionné,
archivé, modifié ou supprimé par les commandes utilisateur. Il est utilisé pour
les missions transversales qui doivent lire plusieurs projets.

Ses droits supplémentaires viennent principalement de son `cwd` placé à la
racine du workspace et du fichier `claude-settings-system.json`. Les règles
`deny` restent communes : pas de `sudo`, `rm -rf`, accès aux secrets
`~/.ssh`, `~/.claude`, `~/.ori`, `/etc` ou `/root`. L'isolation Linux entre
`agentvps` et les autres utilisateurs reste la vraie barrière système.

La visibilité Telegram est indépendante de son existence et de ses droits :

```text
AGENTVPS_TELEGRAM_SYSTEM_PROJECT_VISIBLE=true
```

Quand elle est active, le projet peut apparaître dans les vues Telegram et son
Thread peut être créé. Il reste néanmoins non sélectionnable et non gérable
comme un projet utilisateur.

## Threads Telegram

- `/projets init` parcourt les projets existants et crée les Threads manquants.
- Le projet `system` n'est parcouru que si sa visibilité Telegram est activée.
- L'association est persistée dans `Project.telegramThreadId`.
- Si l'identifiant est connu, AgentVPS vérifie le topic avec
  `reopenForumTopic` avant de le conserver.
- L'API Telegram utilisée ne permet pas de rechercher tous les topics par leur
  titre. Un topic créé manuellement mais dont l'identifiant n'est pas enregistré
  ne peut donc pas être automatiquement rattaché ; AgentVPS en créera un autre.
- La désactivation de la visibilité n'efface pas le projet ni son topic déjà
  existant ; elle masque le projet et empêche les nouvelles créations.

## Amélioration continue

La feature est implémentée, mais **elle n'a pas encore été testée en
conditions réelles**. Les tests automatisés couvrent le seeding, la capture et
les chemins principaux, mais il reste à valider sur une instance complète :

1. activer `capture-conversation-logs` ;
2. envoyer plusieurs messages Telegram sur plusieurs projets ;
3. vérifier `conversation-logs/<projet>/chat.jsonl` ;
4. activer `/tache enable amelioration-continue` ;
5. lancer `/tache run amelioration-continue` ;
6. vérifier `conversation-logs/analysis/script-candidates.md`.

Le seeding crée une mission `AGENT_MISSION` hebdomadaire désactivée. Elle
exécute Claude dans le projet `system`, lit les captures de tous les projets et
ne doit écrire que dans `conversation-logs/analysis/script-candidates.md`.
Elle ne crée pas de tâche, ne modifie pas le code et ne modifie pas la
configuration.

Limites connues à conserver :

- Les sorties partielles des appels Claude en erreur ou en timeout ne sont pas
  capturées pour l'instant.
- Le contenu `thinking` dépend du provider ; il est exploitable avec
  `OPENROUTER`/Ori mais peut être vide avec le canal Anthropic natif.
- La capture est désactivée par défaut et doit être activée explicitement.
- La validation des candidats et la création d'éventuels scripts restent
  manuelles.

## Permissions Claude Code

`--permission-mode dontAsk` est indispensable en headless : sans confirmation
interactive possible, une permission non autorisée bloque l'action. Les deux
fichiers de settings sont déployés séparément du JAR et relus à chaque appel :

- `claude-settings.json` pour les projets normaux ;
- `claude-settings-system.json` pour `system`.

Le fichier system autorise en plus quelques commandes de lecture et
d'agrégation (`wc`, `head`, `tail`, `sort`, `uniq`, `find`, `grep`, `cat`), mais
les règles de refus restent actives.

## Diagnostic rapide

Pour un problème de démarrage ou de Thread Telegram :

```bash
sudo systemctl status agentvps
journalctl -u agentvps -n 100 --no-pager
systemctl show agentvps --property=Environment
```

À rechercher dans les logs :

- `Projet system cree automatiquement` ou `Projet system existant normalise` ;
- `Thread Telegram cree pour le projet 'system'` ;
- `Projet system masque de Telegram` ;
- `Threads non configures` ;
- les erreurs de droits Telegram ou de groupe qui n'est pas configuré en forum.

