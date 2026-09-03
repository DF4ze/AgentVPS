# AgentVPS

Service Spring Boot qui pilote Claude Code (CLI, mode `-p`) a distance via
Telegram : gestion de projets, continuite de conversation par session, et
taches recurrentes (scripts et missions agent).

## Documentation

- [`doc/installation.md`](doc/installation.md) — installer AgentVPS sur un
  serveur neuf, de zero jusqu'au premier message Telegram.
- [`doc/guide-utilisation.md`](doc/guide-utilisation.md) — utilisation au
  quotidien (commandes Telegram).
- [`doc/architecture.md`](doc/architecture.md) — architecture logicielle
  (composants, flux, modele de donnees).
- [`doc/choix-implementation.md`](doc/choix-implementation.md) — pourquoi
  ces choix techniques.
- [`doc/quick-access.md`](doc/quick-access.md) — reperes rapides (chemins,
  commandes de deploiement) pour qui travaille deja sur ce depot.

Ces documents sont tenus a jour a chaque evolution notable du projet.
Notes de conception et roadmap detaillee (hors depot) : voir
`agent-vps-notes.md` et `roadmap-implementation.md` dans le dossier de
documentation du projet (`D:\Documents\Claude\Projects\AgentVPS`).

## Prerequis

- Java 21
- Le module local `fr.ses10doigts:telegram-bots-mvc` installe dans le
  depot Maven local (`mvn install` depuis le projet `TelegramBots`) avant
  de pouvoir compiler celui-ci.
- Copier `src/main/resources/application.template.yaml` en
  `application.yml` (non versionne) et renseigner les variables
  d'environnement necessaires (token Telegram, etc.) avant de lancer le
  service — detail complet dans `doc/installation.md`.

## Build

    ./mvnw clean package

Produit `target/agentvps.jar`.

## Lancer en local

    ./mvnw spring-boot:run

## Logs

Ecrits dans `logs/agentvps.log` (relatif au repertoire de travail du
process), avec rotation par defaut de Spring Boot.
