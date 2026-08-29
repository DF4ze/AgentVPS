package fr.ses10doigts.agentvps.model;

/**
 * Interpretation du resultat d'une execution de tache recurrente.
 *
 * OK/WARNING/CRITICAL/UNKNOWN suivent la convention de code de sortie des plugins de
 * supervision de type Nagios/Icinga (0/1/2/3) - deja adoptee par le premier script de
 * la Phase 7 (health_check.sh, voir memoire projet "phase7_maintenance_project") : en
 * retenant cette convention comme contrat generique pour toute tache recurrente future,
 * un script suit ou non ce contrat sans que le scheduler ait besoin de connaitre sa
 * logique metier.
 *
 * ERROR est distinct : il ne vient pas du code de sortie du script mais d'un echec
 * d'execution cote AgentVPS (process introuvable, timeout, exception) - voir
 * RecurringTaskScheduler.
 */
public enum RunStatus {
    OK,
    WARNING,
    CRITICAL,
    UNKNOWN,
    ERROR;

    /** Convention Nagios/Icinga : 0=OK, 1=WARNING, 2=CRITICAL, tout le reste=UNKNOWN. */
    public static RunStatus fromExitCode(int exitCode) {
        return switch (exitCode) {
            case 0 -> OK;
            case 1 -> WARNING;
            case 2 -> CRITICAL;
            default -> UNKNOWN;
        };
    }

    /** Vrai si ce statut merite une notification meme en politique ON_ISSUE (tout sauf OK). */
    public boolean isIssue() {
        return this != OK;
    }
}
