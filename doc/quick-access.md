# Quick access — AgentVPS

*Repères rapides pour Claude (commandes, chemins, accès). Dernière mise à
jour : 02/09/2026.*

## Dossiers locaux (poste de dev, Windows)

- Code (ce dépôt) : `D:\Documents\Spring\AgentVPS`
- Notes/roadmap (hors dépôt) : `D:\Documents\Claude\Projects\AgentVPS`
  - `agent-vps-notes.md` — notes de conception initiales
  - `roadmap-implementation.md` — roadmap détaillée, phase par phase
- Playbook de la gateway SSH (déploiement) : `D:\Installs\Ssh-tunnel\playbook.json`
  (dossier non connecté par défaut — demander l'accès si besoin d'y toucher)


## Déploiement (VPS, via la gateway SSH)

Opérations du playbook (`executeOperation` / `checkOperation` du plugin
ssh-gateway) :

- **`build-deploy:agentvps`** — build Maven (tests inclus) → upload du jar
  vers `/home/agentvps/AgentVPS/agentvps.jar` → `sudo systemctl restart
  agentvps` → vérification (`systemctl is-active` + logs journalctl). ~90s.
- **`test:agentvps`** — `mvn test` seul, sans déploiement (itération rapide).

Après modification du `playbook.json` : appeler `restartGateway()` pour que
la gateway recharge le fichier, puis `listOperations()` pour vérifier.

Ne sont **pas** couverts par le pipeline automatisé (à faire à la main via
`uploadFile` + `execCommand`) : `claude-settings.json` et
`claude-settings-system.json` (lus à chaque invocation `claude -p`, pas de
rebuild nécessaire pour un simple changement de règle).

## VPS — chemins clés

- Utilisateur système du service : `agentvps` (home `/home/agentvps`,
  isolé de `oklm` qui héberge les autres bots — home `700`, mur noyau)
- Binaire claude : `/home/agentvps/.local/bin/claude`
- Binaire ori (si `provider=OPENROUTER`) : `/home/agentvps/.local/bin/ori`
- Jar déployé : `/home/agentvps/AgentVPS/agentvps.jar`
- Workspace (projets + stores JSON) : `/home/agentvps/AgentVPS/`
  - `projects/<slug>/` — dossier de travail + `CLAUDE.md` de chaque projet
  - `projects-store.json` — table projets/conversations
  - `recurring-tasks-store.json` — tâches récurrentes
  - `conversation-logs/<projet>/chat.jsonl` — captures optionnelles du flux
    Claude Code (`AGENTVPS_CLAUDE_CAPTURE_CONVERSATION_LOGS=true`)
  - `conversation-logs/analysis/script-candidates.md` — synthèse produite par
    la tâche désactivée `amelioration-continue`
  - `logs/permission-changes.log` — audit du script
    `change-project-permissions.sh`
- Settings Claude Code : `/home/agentvps/.config/agentvps/claude-settings.json`
  (standard) et `claude-settings-system.json` (projet `system`)
- Service systemd : `/etc/systemd/system/agentvps.service`
  (`User=agentvps`, `Restart=on-failure`)

## Commandes systemd (via la gateway SSH, `runAsUser=root` pour restart/status root)

```bash
sudo systemctl status agentvps
sudo systemctl restart agentvps
journalctl -u agentvps -n 100 --no-pager
```

`agentvps` n'est pas dans la whitelist "services gérables" native de la
gateway (`getAllServicesStatus`/`getServiceLogs` ne connaissent que `tor,
mariadb, instabot, cristalbot`) — passer par `execCommand` brut ou par
l'opération `build-deploy:agentvps` qui fait déjà la vérification.

## Commandes Telegram (bot `agentvps-bot`)

- `/projet list | new <nom> | delete | <nom>` — gérer les projets (switch
  actif en tapant son nom). Le projet technique `system` est réservé et masqué
  par défaut (voir `AGENTVPS_TELEGRAM_SYSTEM_PROJECT_VISIBLE`).
- `/conv list | new | <numéro>` — conversations du projet actif
- `/tache list | show <nom> | new ... | enable|disable|delete|run <nom>` —
  tâches récurrentes
- message libre → handler `@Chat` (transmis à `claude -p`, avec `--resume`
  si une conversation est en cours)

Whitelist : un seul ID Telegram autorisé, configuré dans `application.yml`
(`telegram.bots[0].security.allowed-user-ids`).

## Mémoires projet pertinentes (via `project_memory_read`)

- `roadmap_phase_status.md` — état des phases en un coup d'œil
- `deploy_automation.md` — détail de `build-deploy:agentvps`, pièges JDK/permissions
- `phase7_scheduler_implementation.md` / `phase7_maintenance_project.md` — tâches récurrentes
- `god_mode_system_project.md` — projet `system`, décisions ouvertes
- `ssh_gateway_security.md` — philosophie "commandes curées" de la gateway
- `claude_fs_permissions.md` — détail des règles allow/ask/deny
- `ori_openrouter_integration.md` — bascule de fournisseur de modèle
- `prompting_architecture.md` — system prompt + renforcement périodique
- `telegram_controller.md` — comportement du controller (ACK, heartbeat...)
- `build_warnings_fix.md` — pièges pom.xml/.mvn/jvm.config

## Fichiers de config à connaître

- `src/main/resources/application.template.yaml` — squelette (à copier en
  `application.yml`, non versionné)
- `src/main/resources/claude-settings.json` / `claude-settings-system.json`
  — règles de permissions Claude Code (versionnées, source de vérité — le
  fichier déployé sur le VPS doit rester synchronisé manuellement)
- `scripts/change-project-permissions.sh` — édite `settings.local.json` d'un
  projet enfant depuis le projet `system` (usage : voir l'en-tête du script)
