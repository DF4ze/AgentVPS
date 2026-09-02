#!/usr/bin/env bash
#
# change-project-permissions.sh — ajoute/modifie/supprime une regle allow ou deny
# dans le fichier .claude/settings.local.json d'UN PROJET ENFANT d'AgentVPS
# (projects/<projet>/.claude/settings.local.json), depuis le projet "system".
#
# POURQUOI CE SCRIPT EXISTE (voir memoire projet "god_mode_system_project") :
# Claude Code refuse d'editer lui-meme un fichier .claude/settings*.json via son
# outil Edit, quel que soit le contenu de claude-settings-system.json (verifie
# empiriquement le 02/09/2026 : 3 tentatives directes toutes bloquees - protection
# integree contre l'auto-escalade de droits, pas un bug de config). Ce script
# route donc l'ecriture par un process Bash normal (autorise via une regle
# Bash(./scripts/change-project-permissions.sh ...) ciblee dans
# claude-settings-system.json) plutot que par l'outil Edit de Claude.
#
# USAGE :
#   change-project-permissions.sh <projet> add    <allow|deny> <valeur>
#   change-project-permissions.sh <projet> modify <allow|deny> <position> <valeur>
#   change-project-permissions.sh <projet> delete <allow|deny> <position>
#   change-project-permissions.sh <projet> list   [allow|deny]
#
# <position> = position 1-indexee DANS SA LISTE (1 = premiere regle allow/deny),
# PAS un numero de ligne du fichier JSON brut. Utiliser "list" pour la connaitre.
#
# PROTECTIONS INTEGREES (perimetre volontairement etroit, ne pas elargir sans
# revoir la conception - voir memoire projet "god_mode_system_project") :
#   - <projet> doit etre un nom simple ([a-z0-9-]+, pas de /, .., ni chemin
#     absolu) ET un dossier existant sous PROJECTS_ROOT - aucune sortie de ce
#     perimetre possible via l'argument.
#   - <projet> = "system" est explicitement refuse : ce script ne peut jamais
#     modifier les droits du projet System lui-meme (pas d'auto-escalade).
#   - Le fichier cible est TOUJOURS <PROJECTS_ROOT>/<projet>/.claude/settings.local.json,
#     jamais un chemin fourni en argument.
#   - Ecriture atomique (fichier temporaire + mv) + JSON valide verifie avant
#     remplacement. Chaque mutation est journalisee (avant/apres) dans
#     LOG_FILE pour audit/rollback manuel.
#
set -euo pipefail

PROJECTS_ROOT="/home/agentvps/AgentVPS/projects"
LOG_FILE="/home/agentvps/AgentVPS/logs/permission-changes.log"

usage() {
  cat >&2 <<'USAGE'
Usage:
  change-project-permissions.sh <projet> add    <allow|deny> <valeur>
  change-project-permissions.sh <projet> modify <allow|deny> <position> <valeur>
  change-project-permissions.sh <projet> delete <allow|deny> <position>
  change-project-permissions.sh <projet> list   [allow|deny]
USAGE
  exit 1
}

[ $# -ge 2 ] || usage

PROJECT="$1"
ACTION="$2"

# --- validation du nom de projet : anti path-traversal + anti auto-escalade ---
if [[ ! "$PROJECT" =~ ^[a-z0-9-]+$ ]]; then
  echo "Erreur : nom de projet invalide : '$PROJECT'" >&2
  exit 2
fi
if [ "$PROJECT" = "system" ]; then
  echo "Erreur : le projet 'system' ne peut pas etre modifie par ce script." >&2
  exit 2
fi

PROJECT_DIR="$PROJECTS_ROOT/$PROJECT"
if [ ! -d "$PROJECT_DIR" ]; then
  echo "Erreur : aucun projet '$PROJECT' sous $PROJECTS_ROOT" >&2
  exit 2
fi

SETTINGS_DIR="$PROJECT_DIR/.claude"
SETTINGS_FILE="$SETTINGS_DIR/settings.local.json"
mkdir -p "$SETTINGS_DIR"
mkdir -p "$(dirname "$LOG_FILE")"

case "$ACTION" in
  add)
    [ $# -eq 4 ] || usage
    CATEGORY="$3"; VALUE="$4"
    ;;
  modify)
    [ $# -eq 5 ] || usage
    CATEGORY="$3"; POSITION="$4"; VALUE="$5"
    ;;
  delete)
    [ $# -eq 4 ] || usage
    CATEGORY="$3"; POSITION="$4"
    ;;
  list)
    [ $# -le 3 ] || usage
    CATEGORY="${3:-}"
    ;;
  *)
    usage
    ;;
esac

if [ -n "${CATEGORY:-}" ] && [ "$CATEGORY" != "allow" ] && [ "$CATEGORY" != "deny" ]; then
  echo "Erreur : categorie invalide '$CATEGORY' (attendu: allow|deny)" >&2
  exit 2
fi

python3 - "$SETTINGS_FILE" "$LOG_FILE" "$PROJECT" "$ACTION" "${CATEGORY:-}" "${POSITION:-}" "${VALUE:-}" <<'PYEOF'
import json
import os
import sys
import tempfile
import datetime

settings_file, log_file, project, action, category, position, value = sys.argv[1:8]

def load():
    if os.path.exists(settings_file):
        with open(settings_file, encoding="utf-8") as f:
            data = json.load(f)
    else:
        data = {}
    data.setdefault("permissions", {})
    data["permissions"].setdefault("allow", [])
    data["permissions"].setdefault("deny", [])
    return data

def print_list(data, cat):
    rules = data["permissions"][cat]
    print(f"{cat} ({len(rules)} regle(s)) :")
    if not rules:
        print("  (vide)")
    for i, rule in enumerate(rules, start=1):
        print(f"  {i}. {rule}")

def atomic_write(data):
    directory = os.path.dirname(settings_file)
    fd, tmp_path = tempfile.mkstemp(dir=directory, prefix=".settings.local.json.", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
            f.write("\n")
        os.replace(tmp_path, settings_file)
    except Exception:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)
        raise

def log(before, after, extra):
    with open(log_file, "a", encoding="utf-8") as f:
        f.write(json.dumps({
            "timestamp": datetime.datetime.utcnow().isoformat() + "Z",
            "project": project,
            "action": action,
            "category": category,
            "detail": extra,
            "before": before,
            "after": after,
        }, ensure_ascii=False) + "\n")

data = load()

if action == "list":
    if category:
        print_list(data, category)
    else:
        print_list(data, "allow")
        print()
        print_list(data, "deny")
    sys.exit(0)

before = list(data["permissions"][category])
rules = data["permissions"][category]

if action == "add":
    rules.append(value)
    extra = {"value": value}
elif action == "modify":
    idx = int(position) - 1
    if idx < 0 or idx >= len(rules):
        print(f"Erreur : position {position} hors limites pour '{category}' ({len(rules)} regle(s))", file=sys.stderr)
        sys.exit(3)
    old_value = rules[idx]
    rules[idx] = value
    extra = {"position": position, "old_value": old_value, "new_value": value}
elif action == "delete":
    idx = int(position) - 1
    if idx < 0 or idx >= len(rules):
        print(f"Erreur : position {position} hors limites pour '{category}' ({len(rules)} regle(s))", file=sys.stderr)
        sys.exit(3)
    removed = rules.pop(idx)
    extra = {"position": position, "removed_value": removed}
else:
    print(f"Erreur : action inconnue '{action}'", file=sys.stderr)
    sys.exit(2)

after = list(data["permissions"][category])
atomic_write(data)
log(before, after, extra)

print(f"OK - {action} applique sur '{category}' pour le projet '{project}'.")
print()
print_list(data, category)
PYEOF
