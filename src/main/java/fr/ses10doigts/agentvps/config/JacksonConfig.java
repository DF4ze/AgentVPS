package fr.ses10doigts.agentvps.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fournit le bean ObjectMapper utilise par ClaudeCliService et par la persistance
 * JSON du ProjectStore (JsonProjectStoreRepository).
 *
 * Le starter minimal du projet (spring-boot-starter, sans spring-web /
 * spring-boot-starter-json) ne declenche pas l'auto-configuration Jackson
 * de Spring Boot : elle depend de Jackson2ObjectMapperBuilder, fourni par
 * spring-web, absent ici. D'ou l'erreur au demarrage "required a bean of
 * type ObjectMapper that could not be found" sans ce bean explicite.
 * Pas de raison d'ajouter spring-web pour ca a ce stade du projet.
 *
 * JavaTimeModule enregistre explicitement pour la meme raison (pas d'auto-config) :
 * necessaire pour (de)serialiser les Instant de Project/Conversation/ProjectStore.
 * Ecriture en ISO-8601 (et non en timestamp epoch) pour un fichier JSON lisible/greppable
 * sur le VPS - coherent avec le choix d'un simple fichier plutot qu'une base de donnees
 * pour la persistance des projets (roadmap Phase 3, point 5).
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
