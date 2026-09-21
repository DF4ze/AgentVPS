package fr.ses10doigts.agentvps.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Cree le projet reserve "system" au premier demarrage. Il s'agit uniquement d'une
 * entree de store et du point de travail racine : aucun appel Claude ni onboarding
 * interactif n'est lance automatiquement.
 */
@Component
@Order(0)
@RequiredArgsConstructor
@Slf4j
public class SystemProjectBootstrap implements ApplicationRunner {

    private final ProjectService projectService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            projectService.ensureSystemProject();
        } catch (ProjectException e) {
            // Ne pas rendre le demarrage bloquant pour une initialisation de confort :
            // l'erreur est visible dans les logs et sera retentee au prochain demarrage.
            log.error("Impossible d'initialiser le projet system : {}", e.getMessage(), e);
        }
    }
}
