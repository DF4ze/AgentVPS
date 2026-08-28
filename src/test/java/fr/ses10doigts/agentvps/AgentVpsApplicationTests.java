package fr.ses10doigts.agentvps;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AgentVpsApplicationTests {

    @Test
    void contextLoads() {
        // Verifie que le contexte Spring demarre (telegram.enabled=false en test,
        // voir src/test/resources/application.yml).
    }
}
