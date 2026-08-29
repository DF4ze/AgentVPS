package fr.ses10doigts.agentvps.model;

/**
 * Mecanisme d'execution d'une tache recurrente (roadmap Phase 7, extension du 29/08/2026 -
 * demande Clem : au-dela d'un simple script, pouvoir confier a l'agent une "mission" en
 * texte libre - recherches reseau, appels MCP, lecture de fichiers locaux, etc.).
 *
 * SCRIPT : comportement d'origine, voir ScriptExecutionService - RecurringTask.command
 * est executee directement via ProcessBuilder, interpretee selon la convention Nagios/Icinga
 * (code de sortie -> RunStatus).
 *
 * AGENT_MISSION : RecurringTask.missionPrompt est envoye tel quel a "claude -p" (voir
 * AgentMissionExecutionService), dans le repertoire de travail du projet associe - l'agent
 * est autonome jusqu'au bout (pas d'utilisateur pour repondre a une question). Pas de code
 * de sortie : le resultat est RunStatus.OK si l'appel reussit, RunStatus.ERROR sinon (voir
 * RecurringTaskScheduler).
 */
public enum RecurringTaskExecutionMode {
    SCRIPT,
    AGENT_MISSION
}
