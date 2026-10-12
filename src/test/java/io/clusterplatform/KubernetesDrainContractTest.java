package io.clusterplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.clusterplatform.adapters.KubernetesAdapter;
import io.clusterplatform.persistence.Secrets;
import io.clusterplatform.persistence.Store;
import io.kubernetes.client.openapi.ApiClient;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class KubernetesDrainContractTest {
    private static final String ID="workload-123";
    private final ObjectMapper json=new ObjectMapper();
    private final AtomicBoolean cordoned=new AtomicBoolean();
    private final AtomicBoolean podPresent=new AtomicBoolean(true);
    private final AtomicBoolean pdbAllows=new AtomicBoolean();
    private final AtomicInteger mutations=new AtomicInteger();
    private final AtomicReference<String> evictionBody=new AtomicReference<>();
    private final JdbcTemplate jdbc=new JdbcTemplate() {
        @Override public <T> T queryForObject(String sql,Class<T> type,Object... args) {
            return type.cast(1);
        }
    };
    private boolean alternative=true,managed=true,localStorage=false,daemon=false;

    private KubernetesAdapter adapter() {
        var env=new MockEnvironment().withProperty("platform.drain-enabled","true")
            .withProperty("platform.maintenance-nodes","worker-1")
            .withProperty("platform.namespace","platform-workloads")
            .withProperty("platform.project","local")
            .withProperty("platform.encryption-key",Base64.getEncoder().encodeToString(new byte[32]));
        var adapter=new KubernetesAdapter(new Store(jdbc,json),new Secrets(env),env);
        var http=new OkHttpClient.Builder().addInterceptor(chain->{
            String path=chain.request().url().encodedPath();
            String method=chain.request().method();
            String body;
            int code=200;
            if(path.equals("/api/v1/nodes/worker-1") && method.equals("GET")) body=node("worker-1",cordoned.get());
            else if(path.equals("/api/v1/nodes")) body="{\"apiVersion\":\"v1\",\"kind\":\"NodeList\",\"metadata\":{},\"items\":["+
                node("worker-1",cordoned.get())+(alternative?","+node("worker-2",false):"")+"]}";
            else if(path.equals("/api/v1/pods")) body="{\"apiVersion\":\"v1\",\"kind\":\"PodList\",\"metadata\":{},\"items\":["+
                (podPresent.get()?pod():"")+"]}";
            else if(path.equals("/apis/apps/v1/namespaces/platform-workloads/replicasets/rs-1")) body="""
                {"apiVersion":"apps/v1","kind":"ReplicaSet","metadata":{"name":"rs-1","uid":"rs-uid","ownerReferences":[{"apiVersion":"apps/v1","kind":"Deployment","name":"workload-123","uid":"deployment-uid","controller":true}]}}
                """;
            else if(path.equals("/apis/apps/v1/namespaces/platform-workloads/deployments/"+ID)) body="""
                {"apiVersion":"apps/v1","kind":"Deployment","metadata":{"name":"workload-123","uid":"deployment-uid","labels":{"platform.cluster.io/workload":"workload-123"}}}
                """;
            else if(path.equals("/api/v1/nodes/worker-1") && method.equals("PUT")) {
                mutations.incrementAndGet(); cordoned.set(true); body=node("worker-1",true);
            } else if(path.equals("/api/v1/namespaces/platform-workloads/pods/pod-1/eviction") && method.equals("POST")) {
                mutations.incrementAndGet();
                var submitted=new Buffer(); chain.request().body().writeTo(submitted); evictionBody.set(submitted.readUtf8());
                if(pdbAllows.get()) { podPresent.set(false); body="{\"apiVersion\":\"policy/v1\",\"kind\":\"Eviction\",\"metadata\":{\"name\":\"pod-1\",\"namespace\":\"platform-workloads\"}}"; }
                else { code=429; body="{\"apiVersion\":\"v1\",\"kind\":\"Status\",\"status\":\"Failure\",\"reason\":\"TooManyRequests\",\"code\":429}"; }
            } else { code=404; body="{\"apiVersion\":\"v1\",\"kind\":\"Status\",\"status\":\"Failure\",\"reason\":\"NotFound\",\"code\":404}"; }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message(code==200?"OK":"rejected").body(ResponseBody.create(body,MediaType.get("application/json"))).build();
        }).build();
        ReflectionTestUtils.setField(adapter,"client",new ApiClient(http).setBasePath("http://cluster.test"));
        return adapter;
    }
    private String node(String name,boolean unschedulable) {
        return "{\"apiVersion\":\"v1\",\"kind\":\"Node\",\"metadata\":{\"name\":\""+name+"\",\"uid\":\""+(name.equals("worker-1")?"node-uid":"other-uid")+"\",\"resourceVersion\":\"7\"},\"spec\":{\"unschedulable\":"+unschedulable+"},\"status\":{\"conditions\":[{\"type\":\"Ready\",\"status\":\"True\"}]}}";
    }
    private String pod() {
        if(daemon) return """
            {"apiVersion":"v1","kind":"Pod","metadata":{"name":"daemon-pod","namespace":"kube-system","ownerReferences":[{"apiVersion":"apps/v1","kind":"DaemonSet","name":"agent","uid":"daemon-uid","controller":true}]},"spec":{"nodeName":"worker-1","containers":[{"name":"agent","image":"busybox"}]}}
            """;
        if(!managed) return "{\"apiVersion\":\"v1\",\"kind\":\"Pod\",\"metadata\":{\"name\":\"foreign\",\"namespace\":\"other\"},\"spec\":{\"containers\":[{\"name\":\"test\",\"image\":\"busybox\"}]}}";
        String base="""
            {"apiVersion":"v1","kind":"Pod","metadata":{"name":"pod-1","namespace":"platform-workloads","uid":"pod-uid","labels":{"platform.cluster.io/workload":"workload-123"},"ownerReferences":[{"apiVersion":"apps/v1","kind":"ReplicaSet","name":"rs-1","uid":"rs-uid","controller":true}]},"spec":{"nodeName":"worker-1","containers":[{"name":"service","image":"busybox"}]}}
            """;
        return localStorage?base.replace("\"image\":\"busybox\"}]", "\"image\":\"busybox\"}],\"volumes\":[{\"name\":\"scratch\",\"emptyDir\":{}}]"):base;
    }
    private ObjectNode spec() { return (ObjectNode)json.valueToTree(java.util.Map.of("name","worker-1","uid","node-uid","desired_unschedulable",true)); }

    @Test void missingAlternativeBlocksBeforeCordon() {
        alternative=false;
        var result=adapter().reconcileDrain(spec());
        assertThat(result.path("error_code").asText()).isEqualTo("no_alternative_ready_node");
        assertThat(mutations).hasValue(0);
    }
    @Test void foreignPodBlocksWithoutEvictionOrCordon() {
        managed=false;
        var result=adapter().reconcileDrain(spec());
        assertThat(result.path("error_code").asText()).isEqualTo("node_contains_unmanaged_pods");
        assertThat(mutations).hasValue(0);
    }
    @Test void localStorageBlocksBeforeCordon() {
        localStorage=true;
        var result=adapter().reconcileDrain(spec());
        assertThat(result.path("error_code").asText()).isEqualTo("node_contains_local_storage");
        assertThat(mutations).hasValue(0);
    }
    @Test void daemonPodRemainsAndDoesNotPreventDrain() {
        daemon=true;
        var cluster=adapter();
        assertThat(cluster.reconcileDrain(spec()).path("complete").asBoolean()).isFalse();
        assertThat(cluster.reconcileDrain(spec()).path("complete").asBoolean()).isTrue();
        assertThat(podPresent).isTrue();
        assertThat(mutations).hasValue(1);
    }
    @Test void pdbRejectionRetriesAndSuccessRequiresPodToLeaveNode() throws Exception {
        var cluster=adapter();
        var blocked=cluster.reconcileDrain(spec());
        assertThat(blocked.path("phase").asText()).isEqualTo("blocked");
        assertThat(blocked.path("error_code").asText()).isEqualTo("eviction_pdb_or_rate_limited");
        assertThat(json.readTree(evictionBody.get()).path("deleteOptions").path("preconditions").path("uid").asText()).isEqualTo("pod-uid");
        assertThat(cordoned).isTrue();
        assertThat(podPresent).isTrue();
        pdbAllows.set(true);
        var evicting=cluster.reconcileDrain(spec());
        assertThat(evicting.path("complete").asBoolean()).isFalse();
        var drained=cluster.reconcileDrain(spec());
        assertThat(drained.path("complete").asBoolean()).isTrue();
        assertThat(drained.path("phase").asText()).isEqualTo("drained");
    }
}
