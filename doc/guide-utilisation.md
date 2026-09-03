# Guide d'utilisation — AgentVPS

*Dernière mise à jour : 03/09/2026*

Utilisation au quotidien du bot Telegram AgentVPS, une fois installé (voir
`installation.md`). Pour l'architecture interne, voir `architecture.md`.

## Notions de base

- **Projet** — un espace de travail isolé (son propre dossier, sa propre
  mémoire `CLAUDE.md`, ses propres conversations). Un seul projet "actif" à
  la fois en usage classique (DM).
- **Conversation** — un fil de discussion avec Claude à l'intérieur d'un
  projet, identifié par un `session_id` Claude Code. La reprise
  (`--resume`) permet de continuer une conversation existante sans renvoyer
  tout l'historique.
- **Message libre (@Chat)** — tout message qui n'est pas une commande `/...`
  est transmis à `claude -p`, avec reprise de la conversation en cours du
  projet actif (ou nouvelle conversation si aucune n'est en cours).

## Gestion des projets — `/projet`

- `/projet` — affiche le projet actif et la liste de ses conversations.
- `/projet list` — liste tous les projets.
- `/projet new <nom>` — crée un projet et démarre une interview (Claude pose
  quelques questions pour remplir le `CLAUDE.md` initial du projet).
- `/projet <nom>` — bascule le projet actif.
- `/projet delete [nom]` — supprime le projet actif (ou le nom donné). En
  DM : archivage réversible. Dans un groupe "Threads" (voir plus bas) :
  suppression définitive avec confirmation (bouton Oui/Annuler).

Aucun projet n'existe encore ? Le premier message libre crée automatiquement
un projet `default`.

## Conversations — `/conv`

- `/conv` — conversation courante du projet actif.
- `/conv list` — historique des conversations du projet.
- `/conv new` — démarre une nouvelle conversation (les précédentes restent
  accessibles via leur numéro).
- `/conv <numéro>` — reprend une conversation précédente.

## Discuter — message libre

Tapez simplement votre message. Le bot :

1. envoie un accusé de réception immédiat ;
2. affiche "en train d'écrire..." en continu pendant que Claude travaille
   (jusqu'à 120 s par défaut) ;
3. remplace l'accusé de réception par la réponse finale.

Chaque échange fait tourner `claude -p` dans le dossier de travail du
projet actif, qui charge automatiquement son `CLAUDE.md` — c'est la mémoire
durable du projet d'une conversation à l'autre. Ce qui n'est pas écrit dans
`CLAUDE.md` (par Claude lui-même, en cours de discussion) est perdu à la fin
de la conversation.

## Tâches récurrentes — `/tache`

- `/tache` ou `/tache list` — liste des tâches.
- `/tache show <nom>` — détail d'une tâche.
- `/tache new` — assistant conversationnel : nom → (projet, si aucun actif)
  → type (script ou mission agent) → commande/mission → notification →
  fréquence (quotidien / horaire / toutes les N minutes / cron avancé /
  ponctuel) → récapitulatif → confirmation. Tapez `annuler` à tout moment
  pour sortir.
- `/tache enable|disable|delete|run <nom>` — gestion.

Deux types de tâche :

- **Script** — une commande shell fixe, exécutée directement (pas de
  passage par Claude). Le code de sortie (convention Nagios : 0=OK,
  1=WARNING, 2=CRITICAL) pilote la notification.
- **Mission agent** — un objectif en texte libre confié à `claude -p` (ex.
  "analyse crypto quotidienne"), avec les privilèges normaux d'une
  conversation (pas ceux, plus larges, du process AgentVPS). Chaque
  exécution repart d'une conversation neuve ; la continuité passe par le
  `CLAUDE.md` du projet.

Notification par tâche : `ALWAYS` (toujours), `ON_ISSUE` (seulement en cas
de souci — code non-OK ou erreur), `NEVER`.

## Threads = projets (optionnel, si configuré)

Si un groupe Telegram est configuré (§11 de `installation.md`), chaque
projet y a son propre sujet ("Thread") : écrire dans ce sujet route
automatiquement vers le bon projet, sans `/projet <nom>`. Dans ce groupe,
`/projet` se limite à `new` et `delete` (le reste passe par la navigation
entre sujets). `/projets init` crée les sujets manquants pour les projets
déjà existants. Le DM classique continue de fonctionner en parallèle,
indépendamment.

## Projet "system" (droits élargis)

`/projet new system` crée un projet réservé à droits élargis : son dossier
de travail est la racine du workspace entière plutôt qu'un sous-dossier
isolé, ce qui lui donne une vue sur tous les autres projets (leurs
`CLAUDE.md`, l'historique des conversations...). Les règles de sécurité
(pas de `sudo`, pas d'accès à `~/.ssh`/`~/.claude`/`~/.ori`, pas d'accès aux
autres applications du serveur tournant sous un autre utilisateur système)
restent identiques à un projet normal — seul le périmètre de lecture/
écriture s'élargit. À réserver aux tâches transverses (maintenance, audit,
analyse cross-projets), pas comme projet de travail courant.

## Limites connues

- Usage mono-utilisateur : un seul ID Telegram autorisé par défaut, un seul
  "projet actif" global en DM.
- Un projet nommé littéralement `list`, `new` ou `delete` serait masqué par
  les sous-commandes de `/projet` (idem `/conv`).
- Pas de verrou pendant un appel `claude -p` en cours (jusqu'à 120 s) : une
  commande concurrente pendant ce laps de temps pourrait interférer. Sans
  impact en usage personnel séquentiel.

---

*Ce document est mis à jour à chaque évolution notable de l'usage (nouvelle
commande, nouveau comportement visible côté Telegram, etc.).*
