package io.clusterplatform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"platform.role=api","platform.auth-mode=demo","platform.cpu-test=true","platform.maintenance-nodes=","platform.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="RUN_DATABASE_TESTS",matches="true")
class DemoModeIntegrationTest {
    @Autowired MockMvc mvc;

    @Test void localDemoUsesMappedUserWithoutBearerAndRejectsCrossOriginWrites() throws Exception {
        mvc.perform(get("/v1/capabilities"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.node_observation").value(true))
            .andExpect(jsonPath("$.node_maintenance").value(false))
            .andExpect(jsonPath("$.subject").value("local-operator"));
        mvc.perform(get("/v1/nodes"))
            .andExpect(status().isOk());
        mvc.perform(post("/v1/image-uploads").header("Host","localhost:18080")
            .header("Origin","https://unrelated.example"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("demo_cross_origin_forbidden"));
        mvc.perform(get("/v1/capabilities").header("Host","remote.example"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("demo_loopback_only"));
    }
}
