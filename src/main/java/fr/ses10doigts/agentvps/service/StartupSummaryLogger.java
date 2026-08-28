package fr.ses10doigts.agentvps.service;

import fr.ses10doigts.agentvps.config.ClaudeCliProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Logue un recapitulatif de la configuration active juste apres le demarrage
 * (une seule ligne INFO) : quel provider claude est actif, quel permission-mode,
 * si Telegram est active, combien de projets sont charges et lequel est actif.
 *
 * Ajoute suite a une discussion avec Clem le 29/08/2026 sur l'enrichissement des
 * logs (voir aussi le log de succes ajoute dans ClaudeCliService et le contexte
 * MDC chatId/project ajoute dans AgentVpsTelegramController) : sans ca, verifier
 * "dans quel mode le service a demarre" apres un deploiement demandait de relire
 * application.yml plutot que le log lui-meme.
 *
 * Volontairement minimal : pas de token, pas de chemin de settings complet, rien
 * qui n'est pas deja par ailleurs visible dans la config versionnee.
 */
@Component
@Order(Integer.MAX_VALUE)
@RequiredArgsConstructor
@Slf4j
public class StartupSummaryLogger implements ApplicationRunner {

    private final ClaudeCliProperties claudeCliProperties;
    private final ProjectService projectService;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        boolean telegramEnabled = environment.getProperty("telegram.enabled", Boolean.class, false);
        int projectCount = projectService.listProjects().size();
        String activeProject = projectService.getActiveProject().map(p -> p.getName()).orElse("(aucun)");

        log.info("AgentVPS demarre - provider={}, permission-mode={}, telegram.enabled={}, "
                        + "projets charges={}, projet actif={}",
                claudeCliProperties.getProvider(),
                claudeCliProperties.getPermissionMode(),
                telegramEnabled,
                projectCount,
                activeProject);
    }
}
