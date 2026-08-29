package fr.ses10doigts.agentvps.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Une tache recurrente (roadmap Phase 7, "couche projet / tache recurrente") : execution
 * periodique d'un script/commande dans le repertoire de travail d'un projet AgentVPS
 * existant (voir Project.workingDirectory), sans passer par le CLI claude - contrairement
 * a ChatService/ClaudeCliService, RecurringTaskScheduler lance directement le script via
 * ProcessBuilder (voir ScriptExecutionService), avec les privilegies du process AgentVPS
 * (utilisateur systeme agentvps) : aucun rapport avec le systeme de permissions
 * allow/deny/ask de Claude Code (--settings), qui ne s'applique qu'aux actions decidees
 * par le modele lui-meme (voir memoire projet "claude_fs_permissions").
 *
 * La "brique generique" retenue avec Clem le 29/08/2026 (plutot qu'un job cable en dur
 * pour le premier cas d'usage, health_check.sh) : nom, cron, projet associe et commande
 * sont des champs de donnees, pas du code - un nouveau script de la Phase 7 se declare
 * via /tache new, sans modification du code Java.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecurringTask {

    /** Cle stable (slug, meme convention que Project.name - voir RecurringTaskService.slugify). */
    private String name;

    /** Libelle libre optionnel pour l'affichage (ex. "Health check quotidien du VPS"). */
    private String description;

    /** Project.name du projet associe : fournit le repertoire de travail (cwd) du script. */
    private String projectName;

    /**
     * Commande a executer dans le repertoire de travail du projet, ex. "./health_check.sh"
     * ou "bash health_check.sh --disk-warn 80". Decoupee sur les espaces par
     * ScriptExecutionService (pas d'interpretation shell - voir ce service pour le detail
     * et ses limites, ex. pas de pipes/redirections).
     */
    private String command;

    /**
     * Mecanisme de declenchement (ajoute le 29/08/2026 - voir RecurringTaskTriggerType).
     * Determine lequel de cronExpression / scheduledAt est effectivement utilise.
     */
    private RecurringTaskTriggerType triggerType = RecurringTaskTriggerType.CRON;

    /**
     * Expression cron Spring (6 champs : secondes minutes heures jour-du-mois mois
     * jour-de-semaine, ou macro type "@daily"/"@hourly" - voir
     * org.springframework.scheduling.support.CronExpression), validee a la creation
     * (RecurringTaskService.createTask) avant d'etre acceptee. Null si triggerType == ONE_TIME.
     */
    private String cronExpression;

    /**
     * Instant d'execution unique pour une tache ponctuelle (triggerType == ONE_TIME
     * uniquement, voir RecurringTaskService.createOneTimeTask) - null pour une tache CRON.
     */
    private Instant scheduledAt;

    private RecurringTaskStatus status = RecurringTaskStatus.ACTIVE;

    private NotificationPolicy notificationPolicy = NotificationPolicy.ON_ISSUE;

    private Instant createdAt;

    // --- Dernier resultat d'execution (voir RecurringTaskService.recordRunResult) ---

    private Instant lastRunAt;

    /** Code de sortie du script, ou null si aucun run n'a encore eu lieu ou si le dernier run est en ERROR. */
    private Integer lastExitCode;

    private RunStatus lastRunStatus;

    /** Extrait (tronque) de la sortie standard du dernier run, pour /tache show. */
    private String lastOutputSummary;

    /** Message d'erreur du dernier run si RunStatus.ERROR (echec d'execution, pas un code de sortie du script). */
    private String lastErrorMessage;
}
