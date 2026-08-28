# AgentVPS

Service Spring Boot qui pilote Claude Code (CLI, mode `-p`) a distance via
Telegram : gestion de projets, continuite de conversation par session, et
(a terme) taches recurrentes.

Documentation, decisions et roadmap detaillee : voir `agent-vps-notes.md` et
`roadmap-implementation.md` dans le dossier de documentation du projet
(`D:\Documents\Claude\Projects\AgentVPS`). Ce depot ne contient que le code.

## Prerequis

- Java 21
- Le module local `fr.ses10doigts:telegram-bots-mvc` installe dans le
  depot Maven local (`mvn install` depuis le projet `TelegramBots`) avant
  de pouvoir compiler celui-ci.
- Copier `src/main/resources/application.template.yaml` en
  `application.yml` (non versionne) et renseigner les variables
  d'environnement necessaires (token Telegram, etc.) avant de lancer le
  service.

## Build

    ./mvnw clean package

Produit `target/agentvps.jar`.

## Lancer en local

    ./mvnw spring-boot:run

## Logs

Ecrits dans `logs/agentvps.log` (relatif au repertoire de travail du
process), avec rotation par defaut de Spring Boot.
