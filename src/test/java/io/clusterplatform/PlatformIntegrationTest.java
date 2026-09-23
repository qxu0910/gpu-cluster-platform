package io.clusterplatform;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.clusterplatform.application.PlatformService;
import io.clusterplatform.domain.*;
import io.clusterplatform.persistence.Store;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"platform.role=api","platform.auth-mode=local","platform.local-token=integration-test-token-minimum-32-characters","platform.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","platform.cpu-test=true"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="RUN_DATABASE_TESTS",matches="true")
class PlatformIntegrationTest {
    @Autowired Store store;
    @Autowired PlatformService service;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    private static final String TOKEN="Bearer integration-test-token-minimum-32-characters";
    @BeforeEach void clean() { store.jdbc.execute("TRUNCATE audit_event,idempotency,operation,revision,resource CASCADE"); }
    @Test void unauthorizedAndUnknownFieldsAreRejectedWithoutSideEffects() throws Exception {
        mvc.perform(get("/v1/images")).andExpect(status().isUnauthorized()).andExpect(header().exists("X-Request-ID")).andExpect(jsonPath("$.error.message").value("unauthorized"));
        mvc.perform(post("/v1/image-uploads").header("Authorization",TOKEN).header("Idempotency-Key","bad").contentType("application/json").content("{\"repository\":\"test\",\"tag\":\"v1\",\"project\":\"other\"}")).andExpect(status().isBadRequest());
        assertThat(store.jdbc.queryForObject("SELECT count(*) FROM resource",Integer.class)).isZero();
    }
    @Test void consoleAssetsArePublicWhileManagementDataStaysProtected() throws Exception {
        var page=mvc.perform(get("/index.html")).andExpect(status().isOk()).andReturn();
        assertThat(page.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("集群管理控制台");
        mvc.perform(get("/app.js")).andExpect(status().isOk());
        mvc.perform(get("/style.css")).andExpect(status().isOk());
        mvc.perform(get("/v1/workloads")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/image-uploads/private/credentials")).andExpect(status().isUnauthorized());
    }
    @Test void idempotencyIsAtomicAcrossConcurrentRequestsAndConflicts() throws Exception {
        var body=store.object(Map.of("repository","demo","tag","v1"));
        try(ExecutorService pool=Executors.newFixedThreadPool(6)) {
            List<Future<ObjectNode>> results=new ArrayList<>();
            for(int i=0;i<6;i++) results.add(pool.submit(()->service.mutate("caller","local","POST","/v1/image-uploads","same","r",body,()->service.upload("local",new Contracts.Upload("demo","v1")))));
            Set<String> ids=new HashSet<>(); for(var result:results) ids.add(result.get().path("operation_id").asText()); assertThat(ids).hasSize(1);
        }
        assertThat(store.jdbc.queryForObject("SELECT count(*) FROM resource",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->service.mutate("caller","local","POST","/v1/image-uploads","same","r",store.object(Map.of("tag","v2")),()->null)).isInstanceOf(ApiException.class).hasMessage("idempotency_conflict");
    }
    @Test void crossProjectReadsHideResourcesAndGetNeverReturnsCredentials() throws Exception {
        String hidden=store.insert("image","other",store.object(Map.of("reference","example")));
        mvc.perform(get("/v1/images/"+hidden).header("Authorization",TOKEN)).andExpect(status().isNotFound());
        String own=store.insert("image","local",store.object(Map.of("reference","example","pull_credentials","TOP-SECRET")));
        mvc.perform(get("/v1/images/"+own).header("Authorization",TOKEN)).andExpect(status().isOk()).andExpect(jsonPath("$.pull_credentials").doesNotExist());
    }
    @Test void referenceProtectionVersionConflictAndEncryptedEnvironment() throws Exception {
        String image=store.insert("image","local",store.object(Map.of("reference","harbor/app@sha256:"+"a".repeat(64),"pull_credentials","encrypted")));
        Contracts.Workload request=new Contracts.Workload("demo",image,"local-kind","service",1,new Contracts.Resources(0,1,1),null,null,null,Map.of("PASSWORD","do-not-log"),List.of(new Contracts.Port("http",8080,"TCP")),new Contracts.Probe("http","/ready",8080),null,30);
        ObjectNode accepted=service.mutate("caller","local","POST","/v1/workloads","create","r",store.object(request),()->service.create("local",request));
        String id=accepted.path("target_id").asText();
        assertThat(store.resource("local","workload",id,false).toString()).doesNotContain("do-not-log");
        assertThatThrownBy(()->service.mutate("caller","local","DELETE","/v1/images/"+image,"delete","r",store.object(Map.of()),()->service.deleteImage("local",image))).hasMessage("image_in_use");
        assertThatThrownBy(()->service.mutate("caller","local","POST","/v1/workloads/"+id+"/scale","scale","r",store.object(Map.of()),()->service.change("local",id,"scale",1,2,null))).hasMessage("operation_in_progress");
        store.jdbc.update("UPDATE operation SET status='succeeded'");
        assertThatThrownBy(()->service.mutate("caller","local","POST","/v1/workloads/"+id+"/stop","stop2","r",store.object(Map.of()),()->service.change("local",id,"stop",7,null,null))).hasMessage("version_conflict");
    }
    @Test void stopCannotRaceLiveLeaseButSupersedesExpiredWork() {
        String image=store.insert("image","local",store.object(Map.of("reference","registry/app@sha256:"+"c".repeat(64))));
        var request=new Contracts.Workload("cancel",image,"local-kind","service",1,new Contracts.Resources(0,1,1),null,null,null,null,List.of(new Contracts.Port("http",8080,"TCP")),new Contracts.Probe("http","/ready",8080),null,30);
        var created=service.mutate("caller","local","POST","/v1/workloads","create","r",store.object(request),()->service.create("local",request));
        String id=created.path("target_id").asText();
        store.jdbc.update("UPDATE operation SET lease_owner='other-worker',lease_until=now()+interval '45 seconds'");
        assertThatThrownBy(()->service.mutate("caller","local","POST","/v1/workloads/"+id+"/stop","stop","r",store.object(Map.of()),()->service.change("local",id,"stop",1,null,null))).hasMessage("operation_in_progress");
        store.jdbc.update("UPDATE operation SET lease_until=now()-interval '1 second'");
        var stopped=service.mutate("caller","local","POST","/v1/workloads/"+id+"/stop","stop","r",store.object(Map.of()),()->service.change("local",id,"stop",1,null,null));
        assertThat(store.operation("local",created.path("operation_id").asText()).path("stage").asText()).isEqualTo("superseded");
        assertThat(store.operation("local",stopped.path("operation_id").asText()).path("status").asText()).isEqualTo("pending");
        assertThat(store.resource("local","workload",id,false).path("replicas").asInt()).isZero();
    }
    @Test void oldObservationIsUnknownAndSecretsRemainRedacted() {
        var resource=store.object(Map.of("observed_at",java.time.Instant.now().minusSeconds(180).toString(),"observed",Map.of("phase","ready"),"encrypted_env","ciphertext","pull_secret_seed","seed"));
        var response=service.safe(resource);
        assertThat(response.path("observed").path("phase").asText()).isEqualTo("unknown");
        assertThat(response.path("observed").path("stale").asBoolean()).isTrue();
        assertThat(response.toString()).doesNotContain("ciphertext","seed");
        assertThat(resource.path("observed").path("phase").asText()).isEqualTo("ready");
    }
    @Test void deletionRequiresQuotedStrongVersionPrecondition() throws Exception {
        String image=store.insert("image","local",store.object(Map.of("reference","registry/app@sha256:"+"d".repeat(64))));
        var request=new Contracts.Workload("delete",image,"local-kind","service",1,new Contracts.Resources(0,1,1),null,null,null,null,List.of(new Contracts.Port("http",8080,"TCP")),new Contracts.Probe("http","/ready",8080),null,30);
        var created=service.mutate("caller","local","POST","/v1/workloads","create","r",store.object(request),()->service.create("local",request));
        String path="/v1/workloads/"+created.path("target_id").asText();
        mvc.perform(delete(path).header("Authorization",TOKEN).header("Idempotency-Key","delete").header("If-Match","1")).andExpect(status().isBadRequest());
        mvc.perform(delete(path).header("Authorization",TOKEN).header("Idempotency-Key","delete").header("If-Match","\"1\"")).andExpect(status().isAccepted());
    }
    @Test void openApiContainsAllLifecyclePaths() throws Exception {
        mvc.perform(get("/v3/api-docs").header("Authorization",TOKEN)).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/v1/workloads/{id}/revisions']").exists());
    }
    @Test void leaseExcludesOtherWorkersAndExpiredLeaseCanBeRecovered() {
        ObjectNode body=store.object(Map.of("repository","demo","tag","v1"));
        service.mutate("caller","local","POST","/v1/image-uploads","lease","r",body,()->service.upload("local",new Contracts.Upload("demo","v1")));
        var registry=org.mockito.Mockito.mock(io.clusterplatform.adapters.RegistryAdapter.class);
        var cluster=org.mockito.Mockito.mock(io.clusterplatform.adapters.ClusterAdapter.class);
        var first=new io.clusterplatform.worker.OperationWorker(store,registry,cluster,tx);
        var second=new io.clusterplatform.worker.OperationWorker(store,registry,cluster,tx);
        assertThat(first.claim()).isNotNull(); assertThat(second.claim()).isNull();
        store.jdbc.update("UPDATE operation SET lease_until=now()-interval '1 second'");
        assertThat(second.claim()).isNotNull();
    }
    @Test void workerSurvivesDependencyLossAndNewInstanceConvergesWithoutNewOperation() {
        String image=store.insert("image","local",store.object(Map.of("reference","registry/app@sha256:"+"b".repeat(64),"pull_credentials","encrypted")));
        var request=new Contracts.Workload("recover",image,"local-kind","service",1,new Contracts.Resources(0,1,1),null,null,null,null,List.of(new Contracts.Port("http",8080,"TCP")),new Contracts.Probe("http","/ready",8080),null,30);
        var accepted=service.mutate("caller","local","POST","/v1/workloads","recover","r",store.object(request),()->service.create("local",request));
        var registry=org.mockito.Mockito.mock(io.clusterplatform.adapters.RegistryAdapter.class);
        var cluster=org.mockito.Mockito.mock(io.clusterplatform.adapters.ClusterAdapter.class);
        org.mockito.Mockito.when(cluster.reconcile(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyBoolean()))
            .thenThrow(new io.clusterplatform.adapters.DependencyException("cluster_unavailable",true))
            .thenReturn(store.object(Map.of("phase","ready","complete",true)));
        new io.clusterplatform.worker.OperationWorker(store,registry,cluster,tx).tick();
        String operation=accepted.path("operation_id").asText();
        assertThat(store.operation("local",operation).path("status").asText()).isEqualTo("unknown");
        assertThat(store.resource("local","workload",accepted.path("target_id").asText(),false).path("observed").path("stale").asBoolean()).isTrue();
        store.jdbc.update("UPDATE operation SET next_run=now()");
        new io.clusterplatform.worker.OperationWorker(store,registry,cluster,tx).tick();
        assertThat(store.operation("local",operation).path("status").asText()).isEqualTo("succeeded");
        assertThat(store.jdbc.queryForObject("SELECT count(*) FROM operation",Integer.class)).isEqualTo(1);
    }
    @Test void registryDigestFailureDoesNotPublishImageAndOperationGetStays200() throws Exception {
        var registry=org.mockito.Mockito.mock(io.clusterplatform.adapters.RegistryAdapter.class);
        var cluster=org.mockito.Mockito.mock(io.clusterplatform.adapters.ClusterAdapter.class);
        org.mockito.Mockito.when(registry.authorize(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{ ObjectNode upload=invocation.getArgument(1); upload.put("credentials","encrypted"); return upload; });
        var body=store.object(Map.of("repository","demo","tag","v1"));
        var accepted=service.mutate("caller","local","POST","/v1/image-uploads","digest","r",body,()->service.upload("local",new Contracts.Upload("demo","v1")));
        var worker=new io.clusterplatform.worker.OperationWorker(store,registry,cluster,tx); worker.tick();
        String upload=accepted.path("target_id").asText();
        var complete=service.mutate("caller","local","POST","/v1/image-uploads/"+upload+"/complete","complete","r",store.object(Map.of()),()->service.complete("local",upload,new Contracts.Complete(null)));
        org.mockito.Mockito.when(registry.verify(org.mockito.ArgumentMatchers.any())).thenThrow(new io.clusterplatform.adapters.DependencyException("digest_mismatch",false));
        worker.tick();
        mvc.perform(get("/v1/operations/"+complete.path("operation_id").asText()).header("Authorization",TOKEN)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("failed")).andExpect(jsonPath("$.error.code").value("digest_mismatch"));
        assertThat(store.jdbc.queryForObject("SELECT count(*) FROM resource WHERE kind='image'",Integer.class)).isZero();
    }
}
