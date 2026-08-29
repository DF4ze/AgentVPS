package fr.ses10doigts.agentvps.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Fournit le TaskScheduler utilise par RecurringTaskScheduler (le @Component service,
 * pas ce bean) pour planifier dynamiquement les taches recurrentes (roadmap Phase 7).
 * Bean explicite plutot que @EnableScheduling + @Scheduled : les taches sont
 * creees/modifiees/supprimees a chaud via Telegram (/tache ...), donc le planning ne
 * peut pas etre fixe a la compilation par des annotations - voir
 * RecurringTaskScheduler.schedule/unschedule, qui manipule directement des CronTrigger
 * sur ce bean.
 *
 * Nomme "taskScheduler" (pas "recurringTaskScheduler") : la classe @Component
 * RecurringTaskScheduler prend par defaut ce meme nom de bean (simple name avec initiale
 * en minuscule) - un @Bean nomme pareil provoquait un conflit de nom au demarrage
 * (BeanDefinitionStoreException, decouvert par Clem via `./mvnw clean test` le
 * 29/08/2026 : AgentVpsApplicationTests.contextLoads echouait a charger le contexte).
 * "taskScheduler" est en plus le nom conventionnel reconnu par
 * TaskSchedulingAutoConfiguration de Spring Boot, qui evite alors de creer son propre
 * TaskScheduler par defaut.
 */
@Configuration
@RequiredArgsConstructor
public class SchedulingConfig {

    private final RecurringTaskProperties recurringTaskProperties;

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(recurringTaskProperties.getSchedulerPoolSize());
        scheduler.setThreadNamePrefix("recurring-task-");
        scheduler.initialize();
        return scheduler;
    }
}
