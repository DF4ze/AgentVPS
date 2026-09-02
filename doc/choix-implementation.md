# Choix d'implémentation — AgentVPS

*Dernière mise à jour : 02/09/2026*

Ce document explique le **pourquoi** derrière les décisions techniques. Pour
le **quoi** (structure du code), voir `architecture.md`.

## 1. Déléguer le raisonnement, pas le reconstruire

Contrainte de départ assumée : ne pas redévelopper un agent complet (tentative
précédente jugée trop coûteuse). Le CLI Claude Code fait déjà le raisonnement
agentique ; AgentVPS n'ajoute que transport + persistance + ordonnancement,
de l'ingénierie logicielle classique avec des solutions matures. Conséquence
directe : `claude -p` (mode script, pas de PTY/tmux) suffit, puisque le
process Java tourne déjà en continu (systemd) et assure la persistance —
inutile de maintenir une session interactive côté serveur.

## 2. Continuité de conversation via `--resume`, pas un historique rejoué

Chaque appel `claude -p --output-format json` renvoie un `session_id`.
Rappeler avec `--resume <id>` reprend la conversation côté CLI directement
(le CLI gère son propre historique) — pas besoin de renvoyer les messages
précédents à chaque appel. AgentVPS ne garde donc qu'une table légère
`projet → session_id courant` (+ historique des `session_id` passés pour
permettre de revenir sur une ancienne conversation via `/conv <numéro>`).

## 3. `CLAUDE.md` comme mémoire de projet plutôt que l'auto-memory de Claude Code

Besoin : qu'une **nouvelle** conversation dans un projet hérite du contexte
des conversations précédentes déjà closes. Deux mécanismes existent côté
Claude Code : l'auto-memory (écriture spontanée dans
`~/.claude/projects/<projet>/memory/`) et `CLAUDE.md` (chargé automatiquement
à chaque session, y compris en `-p`, s'il est dans le `cwd`).

Choix retenu : **`CLAUDE.md` uniquement**, l'auto-memory est désactivée
(`CLAUDE_CODE_DISABLE_AUTO_MEMORY=1`, `ClaudeCliProperties.disableAutoMemory`).
Deux raisons : (1) contrôle explicite — l'édition de `CLAUDE.md` est un choix
assumé (prompt dédié en fin de conversation), pas une heuristique du modèle ;
(2) l'auto-memory était de toute façon bloquée par le `deny` sur
`~/.claude/**` du `settings.json` (Phase 1 sécurité), ce qui faisait perdre
des tours à claude qui tentait d'y écrire puis échouait — autant couper la
fonctionnalité elle-même plutôt que la laisser échouer silencieusement.

## 4. Renforcement périodique en préfixe du prompt, pas dans le system prompt

Un rappel des règles (CLAUDE.md + permissions) est injecté périodiquement
(tous les N messages, `agentvps.claude.reinforcement-every-messages`,
toujours vrai au premier message d'une conversation). Choix : préfixe du
message utilisateur (`ChatService`), **pas** `--append-system-prompt`. Un
system prompt additionnel serait retransmis à *chaque* appel — inutile
d'alourdir chaque requête d'un rappel qui ne sert que ponctuellement. Le
compteur n'est mis à jour qu'après un appel réussi, pour ne jamais faire
perdre un renforcement dû si l'appel échoue.

## 5. Sécurité en deux couches indépendantes, pas une seule

Le sandbox OS natif de Claude Code (`bubblewrap`) ne fonctionne pas sur ce
VPS (conteneur LXC, user namespaces non privilégiés désactivés) — sans lui,
les règles `deny` du `settings.json` restent des filtres textuels
contournables (lire un fichier via `python3` plutôt que `cat`, par exemple),
pas une vraie barrière. D'où la décision de superposer :

- une **vraie barrière noyau** : utilisateur système dédié `agentvps`,
  isolé des autres bots (`oklm`, home `700`) ;
- une **politique applicative** (`settings.json` + `--permission-mode
  dontAsk`) qui reste utile pour limiter ce qu'`agentvps` peut faire dans
  *son propre* périmètre (pas de `sudo`, pas de `rm -rf`, pas d'édition de
  `/etc`), même sans intrus externe.

Sans `--permission-mode` explicite, `claude -p` ne peut faire **aucune**
action sur le filesystem en headless (constat empirique : 10 tours puis
abandon, faute de pouvoir répondre à une demande de confirmation
interactive) — ce n'était donc pas une option facultative.

## 6. Projet "system" : élargir le `cwd`, pas relâcher le `deny`

Pour un mode à droits élargis, deux approches étaient possibles : assouplir
les règles `allow`/`deny`, ou élargir le périmètre auquel elles s'appliquent.
Choix retenu : les règles `Read(**)/Write(**)/Edit(**)` du `settings.json`
sont déjà relatives au `cwd` du process claude — il suffit donc de donner au
projet `system` un `cwd` plus large (racine du workspace au lieu d'un
sous-dossier isolé) pour qu'il voie tous les autres projets, **sans toucher
au `deny`** (secrets, `/etc`, `/root`, `sudo`, `rm -rf` protégés partout de
façon identique). Volontairement, ce mode n'ouvre **pas** l'accès aux autres
applications du VPS (CristalBot, InstaBot...) : elles tournent sous un autre
utilisateur système (`oklm`, home `700`), un mur noyau qu'aucun fichier
`--settings` ne peut lever. Décision encore ouverte : lever ce mur via
ACL Unix ciblée, ou (recommandé) via des opérations curées de la gateway SSH
plutôt que par les outils du projet `system`.

`/god` (raccourci multi-projet vers ce mode, sans changer de projet actif) a
été explicitement reporté : il ferait perdre le contexte de la conversation
active (implique un `--resume` séparé vers la session du projet `system`).

## 7. Deux modes d'exécution pour une tâche récurrente

`RecurringTaskExecutionMode.SCRIPT` (commande shell fixe, `ProcessBuilder`
direct, code retour = résultat métier normal, pas une erreur) et
`AGENT_MISSION` (prompt libre confié à `claude -p`, sans `--resume` — chaque
déclenchement repart d'une conversation neuve, la continuité passe par
`CLAUDE.md` du projet, même principe que le chat). Séparation délibérée :
un script de supervision et une mission agentique n'ont pas la même
sémantique d'échec (code de sortie non nul vs exception), d'où deux services
distincts (`ScriptExecutionService` / `AgentMissionExecutionService`) plutôt
qu'un chemin unique avec des branches conditionnelles.

## 8. Persistance JSON plutôt qu'une base de données

Mono-utilisateur, faible volumétrie (quelques projets, quelques tâches) :
un fichier JSON rechargé une fois puis réécrit en entier à chaque mutation,
sous un verrou en mémoire, est largement suffisant et évite d'introduire une
dépendance base de données pour ce volume. Deux fichiers séparés
(`projects-store.json` / `recurring-tasks-store.json`) plutôt qu'un seul :
cycles de vie et fréquences d'écriture différents (un run planifié écrit
bien plus souvent qu'une mutation de projet).

## 9. Fournisseur de modèle interchangeable (Ori/OpenRouter)

`ClaudeCliProperties.provider` (ANTHROPIC par défaut, ou OPENROUTER via Ori
Harness) : `ori claude --model <id> -p ...` se comporte comme `claude -p
...` (mêmes flags, même schéma JSON de sortie) — seul le modèle qui répond
change. Permet de basculer de fournisseur sans toucher au reste du code
(`buildCommand()` ajoute juste `ori claude` devant la même liste de flags).

## 10. Déploiement via opération gateway curatée

Le déploiement manuel répété (build, upload, permissions, restart, vérif.)
coûtait cher en itérations. Choix : une opération dédiée du playbook de la
gateway SSH (`build-deploy:agentvps`) plutôt que des commandes `execCommand`
ad hoc — cohérent avec la philosophie déjà retenue pour la gateway
("commandes curées" plutôt qu'accès root libre, voir la mémoire projet
`ssh_gateway_security`). Détail des commandes dans `quick-access.md`.
