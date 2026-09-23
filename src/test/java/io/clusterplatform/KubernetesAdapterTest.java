package io.clusterplatform;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.clusterplatform.adapters.KubernetesAdapter;
import io.clusterplatform.persistence.*;
import io.kubernetes.client.openapi.models.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class KubernetesAdapterTest {
    private final ObjectMapper json=new ObjectMapper();
    private final MockEnvironment env=new MockEnvironment().withProperty("platform.encryption-key",Base64.getEncoder().encodeToString(new byte[32]));
    private final KubernetesAdapter adapter=new KubernetesAdapter(new Store(null,json),new Secrets(env),env);
    private ObjectNode spec() throws Exception { return (ObjectNode)json.readTree("""
        {"namespace":"test","image":"registry/project/app@sha256:abc","replicas":1,"resources":{"cpu_cores":1,"memory_gib":1,"gpu_per_replica":0},"ports":[{"name":"http","port":8080,"protocol":"TCP"}],"readiness":{"type":"http","port":8080,"path":"/ready"},"termination_grace_seconds":30}
        """); }
    @Test void createsConstrainedDeploymentWithoutBypassingScheduler() throws Exception {
        var d=adapter.deployment("workload-123",2,spec()); var pod=d.getSpec().getTemplate().getSpec();
        assertThat(pod.getNodeName()).isNull(); assertThat(pod.getAutomountServiceAccountToken()).isFalse();
        assertThat(pod.getContainers().getFirst().getSecurityContext().getAllowPrivilegeEscalation()).isFalse();
        assertThat(pod.getContainers().getFirst().getReadinessProbe().getHttpGet().getPath()).isEqualTo("/ready");
        assertThat(pod.getContainers().getFirst().getResources().getLimits()).doesNotContainKey("nvidia.com/gpu");
    }
    @Test void runningAndStaleGenerationAreNotReadyAndStopWaitsForPodDeletion() throws Exception {
        var spec=spec(); var d=adapter.deployment("workload-123",1,spec); d.getMetadata().uid("uid").generation(2L);
        d.status(new V1DeploymentStatus().observedGeneration(1L).availableReplicas(1).updatedReplicas(1).replicas(1));
        assertThat(adapter.observation("workload-123",1,spec,d,List.of()).path("complete").asBoolean()).isFalse();
        d.getStatus().observedGeneration(2L);
        assertThat(adapter.observation("workload-123",1,spec,d,List.of()).path("complete").asBoolean()).isTrue();
        spec.put("replicas",0);
        var pod=new V1Pod().metadata(new V1ObjectMeta().name("terminating")).spec(new V1PodSpec());
        assertThat(adapter.observation("workload-123",2,spec,d,List.of(pod)).path("complete").asBoolean()).isFalse();
        assertThat(adapter.observation("workload-123",2,spec,d,List.of()).path("complete").asBoolean()).isTrue();
    }
    @Test void encryptionIsRandomizedAndTamperDetected() {
        Secrets secrets=new Secrets(env); String a=secrets.encrypt("sensitive"),b=secrets.encrypt("sensitive");
        assertThat(a).isNotEqualTo(b); assertThat(secrets.decrypt(a)).isEqualTo("sensitive");
        assertThatThrownBy(()->secrets.decrypt(a.substring(0,a.length()-5)+"AAAAA")).isInstanceOf(IllegalStateException.class);
    }
}
