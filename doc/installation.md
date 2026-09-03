# Installation — AgentVPS

*Dernière mise à jour : 03/09/2026*

Guide d'installation d'AgentVPS sur un serveur neuf (VPS Linux). Pour le
*pourquoi* des choix techniques, voir `architecture.md` et
`choix-implementation.md`. Pour l'usage au quotidien une fois installé, voir
`guide-utilisation.md`.

## 0. Vue d'ensemble

AgentVPS est un service Spring Boot (Java 21) qui pilote le CLI Claude Code
(`claude -p`) à distance via Telegram. Il tourne en permanence comme service
systemd, sous un utilisateur système dédié.

## 1. Prérequis serveur

- Linux (testé en conteneur LXC) avec accès root pour l'installation
  initiale.
- Java 21 (JDK) pour builder ; seul le jar est nécessaire à l'exécution si
  vous préférez builder sur votre poste de dev.
- Un utilisateur système dédié, isolé des autres services du serveur (§2).
- Le binaire `claude` (Claude Code CLI) installé et authentifié pour cet
  utilisateur.
- Un bot Telegram (token via [@BotFather](https://t.me/BotFather)) et votre
  ID Telegram numérique (ex via [@userinfobot](https://t.me/userinfobot)).
- (Optionnel) `ori` (Ori Harness) si vous voulez router certains appels vers
  OpenRouter plutôt que l'API Anthropic native.

## 2. Isolation utilisateur

Créer un utilisateur système dédié (ex `agentvps`), home séparé de tout
autre service du serveur :

```bash
useradd -m -s /bin/bash agentvps
```

Le sandbox natif de Claude Code (bubblewrap) ne fonctionne pas dans un
conteneur LXC sans user namespaces privilégiés — l'isolation OS (utilisateur
dédié, home des *autres* services en accès restreint, ex `700`) est la vraie
barrière ; les permissions Claude Code (§7) sont une seconde couche
applicative, pas une substitution. Voir `choix-implementation.md` §5.

## 3. Installer et authentifier Claude Code

En tant qu'utilisateur `agentvps` :

```bash
# méthode d'installation en vigueur, voir la doc officielle Claude Code
claude auth login
```

Vérifier que le binaire est bien exécutable (`chmod +x` si besoin — piège
déjà rencontré : perte du bit exécutable après une mise à jour du CLI).

Noter le chemin réel du binaire (généralement `~/.local/bin/claude`,
symlink vers `~/.local/share/claude/versions/<version>`) — à renseigner en
config (§6).

## 4. (Optionnel) Installer Ori pour OpenRouter

Si vous voulez pouvoir router certains appels vers un modèle OpenRouter
plutôt que l'API Anthropic native :

```bash
# installer ori (Ori Harness, outil officiel OpenRouter)
ori login   # OAuth, ou `ori login --with-key` pour une clé API
```

Voir `architecture.md` et la mémoire projet `ori_openrouter_integration`
pour le détail des variables d'environnement posées automatiquement par
`ori claude`.

## 5. Builder le module TelegramBots (dépendance locale)

AgentVPS dépend d'un module maison non publié sur un dépôt public :
`fr.ses10doigts:telegram-bots-mvc`. Depuis son dépôt :

```bash
./mvnw clean install
```

Ceci installe le jar dans le dépôt Maven local (`~/.m2`) — nécessaire avant
de pouvoir builder AgentVPS.

## 6. Configurer AgentVPS

Copier `src/main/resources/application.template.yaml` en `application.yml`
(non versionné, voir `.gitignore`) et renseigner au minimum :

| Clé | Rôle |
|---|---|
| `telegram.bots[0].token` | Token du bot (@BotFather) |
| `telegram.bots[0].security.allowed-user-ids` | Votre ID Telegram numérique (liste vide = accès ouvert à tout le monde, à éviter) |
| `agentvps.claude.binary-path` | Chemin réel du binaire `claude` (§3) |
| `agentvps.workspace.root-dir` | Dossier racine du workspace (projets + stores JSON), ex `/home/agentvps/AgentVPS` |

Variables d'environnement principales (le template liste la totalité, avec
valeurs par défaut) :

| Variable | Défaut | Rôle |
|---|---|---|
| `TELEGRAM_ENABLED` | `false` | Active le bot Telegram (`true` en prod) |
| `AGENTVPS_CLAUDE_BINARY` | `/home/agentvps/.local/bin/claude` | Chemin du binaire claude |
| `AGENTVPS_CLAUDE_TIMEOUT_SECONDS` | `120` | Timeout d'un appel `claude -p` (chat interactif) |
| `AGENTVPS_CLAUDE_PERMISSION_MODE` | `dontAsk` | Mode de permission headless (§7) |
| `AGENTVPS_CLAUDE_SETTINGS_PATH` | `/home/agentvps/.config/agentvps/claude-settings.json` | Fichier de règles allow/ask/deny |
| `AGENTVPS_CLAUDE_PROVIDER` | `ANTHROPIC` | `ANTHROPIC` ou `OPENROUTER` (§4) |
| `AGENTVPS_WORKSPACE_ROOT` | `${user.home}/AgentVPS` | Racine des projets + stores JSON |
| `AGENTVPS_TELEGRAM_FORUM_CHAT_ID` | (vide) | ID du groupe Telegram "Threads = projets" (§11) |
| `AGENTVPS_RECURRING_TASKS_NOTIFICATION_CHAT_ID` | (vide) | Chat recevant les notifications de tâches récurrentes |

## 7. Déployer les fichiers de permissions Claude Code

Copier `src/main/resources/claude-settings.json` et
`claude-settings-system.json` vers le chemin déclaré en
`agentvps.claude.settings-path` (`/home/agentvps/.config/agentvps/` par
défaut).

**Sans `--permission-mode` + `--settings` correctement configurés, `claude
-p` ne peut faire AUCUNE action sur le filesystem en headless** (bloqué en
attente d'une confirmation interactive qui n'arrivera jamais) — ce n'est
pas une étape facultative. Voir `architecture.md` §6 pour le détail des
règles (deny sur `~/.ssh`, `~/.claude`, `~/.ori`, `/etc`, `/root`, `sudo`,
`rm -rf`).

Ces fichiers sont relus à chaque invocation `claude -p` (pas de cache JVM)
— un changement de règle ne nécessite pas de rebuild/redeploy du jar.

## 8. Builder et déployer le jar

Depuis le dépôt AgentVPS :

```bash
./mvnw clean package
```

Produit `target/agentvps.jar`. Copier sur le serveur, par ex
`/home/agentvps/AgentVPS/agentvps.jar` (propriétaire `agentvps:agentvps`).

## 9. Service systemd

Créer `/etc/systemd/system/agentvps.service` :

```ini
[Unit]
Description=AgentVPS
After=network.target

[Service]
Type=simple
User=agentvps
WorkingDirectory=/home/agentvps/AgentVPS
Environment="TELEGRAM_ENABLED=true"
ExecStart=/usr/bin/java -jar /home/agentvps/AgentVPS/agentvps.jar
Restart=on-failure
RestartSec=10
SuccessExitStatus=143
SyslogIdentifier=agentvps

[Install]
WantedBy=multi-user.target
```

Ajouter en `Environment=` toute autre variable du §6 que vous ne voulez pas
mettre en clair dans `application.yml`.

```bash
systemctl daemon-reload
systemctl enable agentvps
systemctl start agentvps
systemctl status agentvps
journalctl -u agentvps -n 100 --no-pager
```

Vérifier dans les logs : démarrage propre, bot Telegram enregistré
(long-polling), commandes du menu enregistrées, aucune erreur.

## 10. Premier contact

Envoyer `/projet` au bot sur Telegram : aucun projet n'existant encore, un
projet `default` est créé automatiquement avec une interview (premier appel
`claude -p` réel — valide en même temps que le binaire, l'authentification
et les permissions sont correctement en place). Voir `guide-utilisation.md`
pour la suite.

## 11. (Optionnel) Threads = projets

Pour que chaque projet AgentVPS corresponde à un sujet ("Thread") dans un
groupe Telegram dédié plutôt que de gérer les projets via `/projet` en DM :

1. Créer un supergroupe Telegram, activer les "Sujets" (topics), ajouter le
   bot en admin avec les droits "Gérer les sujets" + "Supprimer les
   messages".
2. Renseigner `AGENTVPS_TELEGRAM_FORUM_CHAT_ID` (ID numérique du groupe,
   négatif pour un supergroupe) dans la config.
3. Dans ce groupe : `/projets init` crée les sujets manquants pour les
   projets existants.

Le chat privé (DM) continue de fonctionner en parallèle avec le mécanisme
`/projet` classique — les deux coexistent.

## 12. Redéploiements ultérieurs

Rebuild (`./mvnw clean package`) → upload du nouveau jar →
`systemctl restart agentvps`. Les fichiers `claude-settings*.json` (§7) ne
nécessitent pas de redéploiement du jar (relus à chaque appel). Voir
`quick-access.md` pour automatiser ce cycle via une gateway de déploiement.

---

*Ce document est mis à jour à chaque évolution notable de l'installation ou
de la configuration (nouvelle variable d'environnement, nouvelle étape
requise, etc.).*
