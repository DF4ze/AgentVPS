package fr.ses10doigts.agentvps;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AgentVpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentVpsApplication.class, args);
    }
}
