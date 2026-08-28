package fr.ses10doigts.agentvps.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fournit le bean ObjectMapper utilise par ClaudeCliService.
 *
 * Le starter minimal du projet (spring-boot-starter, sans spring-web /
 * spring-boot-starter-json) ne declenche pas l'auto-configuration Jackson
 * de Spring Boot : elle depend de Jackson2ObjectMapperBuilder, fourni par
 * spring-web, absent ici. D'ou l'erreur au demarrage "required a bean of
 * type ObjectMapper that could not be found" sans ce bean explicite.
 * Pas de raison d'ajouter spring-web pour ca a ce stade du projet.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
