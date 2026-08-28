package fr.ses10doigts.agentvps.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Une conversation Claude au sein d'un projet, identifiee par le session_id renvoye
 * par "claude -p ... --output-format json". Un projet peut contenir plusieurs
 * conversations dans le temps : l'utilisateur peut en demarrer une nouvelle ou
 * revenir a une ancienne (voir ProjectService, roadmap Phase 3 - reponse de Clem
 * du 28/08/2026 : "le projet contient tous les session_id qui ont eu lieu").
 *
 * L'ordre de la liste Project.conversations fait foi pour la numerotation affichee
 * a l'utilisateur cote Telegram (1 = la plus ancienne) ; aucun numero n'est stocke
 * ici pour eviter toute desynchronisation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Conversation {

    /** session_id claude, utilise tel quel avec --resume pour reprendre cette conversation. */
    private String sessionId;

    private Instant startedAt;

    private Instant lastUsedAt;

    /** Court libelle optionnel (ex. "Mise en place initiale") pour l'affichage dans /projet ... list. */
    private String label;
}
