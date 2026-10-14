package io.clusterplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterplatform.persistence.Store;
import io.clusterplatform.worker.NodeObserver;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NodeObserverTest {
    @Test void projectsOnlyAllowedFieldsAndPreservesMissingGpu() {
        var node=new V1Node().metadata(new V1ObjectMeta().name("worker-1").annotations(Map.of("private","sensitive-value"))
                .labels(Map.of("nvidia.com/gpu.product","GB10","nvidia.com/cuda.driver-version.full","580.173.02","private","sensitive-value")))
            .spec(new V1NodeSpec().unschedulable(true))
            .status(new V1NodeStatus().capacity(Map.of("cpu",Quantity.fromString("8")))
                .allocatable(Map.of("cpu",Quantity.fromString("7500m")))
                .conditions(List.of(new V1NodeCondition().type("Ready").status("True").message("sensitive-value"),new V1NodeCondition().type("DiskPressure").status("False")))
                .nodeInfo(new V1NodeSystemInfo().architecture("arm64").operatingSystem("linux").kernelVersion("6.17")));
        var result=NodeObserver.project(node,new Store(null,new ObjectMapper()));
        assertThat(result.path("ready").asText()).isEqualTo("True");
        assertThat(result.path("unschedulable").asBoolean()).isTrue();
        assertThat(result.path("capacity").path("cpu").asText()).isEqualTo("8");
        assertThat(result.path("capacity").has("nvidia.com/gpu")).isFalse();
        assertThat(result.path("architecture").asText()).isEqualTo("arm64");
        assertThat(result.path("gpu_product").asText()).isEqualTo("GB10");
        assertThat(result.path("driver_version").asText()).isEqualTo("580.173.02");
        assertThat(result.toString()).doesNotContain("sensitive-value");
        assertThat(NodeObserver.project(new V1Node().metadata(new V1ObjectMeta().name("starting")),new Store(null,new ObjectMapper())).path("ready").asText()).isEqualTo("Unknown");
    }
}
