package io.clusterplatform.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.clusterplatform.persistence.*;
import io.kubernetes.client.custom.*;
import io.kubernetes.client.openapi.*;
import io.kubernetes.client.openapi.apis.*;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.*;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class KubernetesAdapter implements ClusterAdapter {
    public static final String OWNER="platform.cluster.io/workload";
    public static final String REVISION="platform.cluster.io/revision";
    private final Store store; private final Secrets secrets; private final Environment env;
    private ApiClient client;
    public KubernetesAdapter(Store store,Secrets secrets,Environment env) { this.store=store; this.secrets=secrets; this.env=env; }
    public synchronized ApiClient client() {
        if(client==null) try {
            String path=env.getProperty("platform.kubeconfig","");
            if(path.isBlank()) client=Config.fromCluster();
            else try(FileReader reader=new FileReader(path)) { client=ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(reader)).build(); }
            client.setConnectTimeout(5000); client.setReadTimeout(10000);
        } catch(Exception x) { throw new DependencyException("cluster_unavailable",true); }
        return client;
    }
    public ObjectNode reconcile(String id,long version,ObjectNode spec,boolean delete) {
        try {
            AppsV1Api apps=new AppsV1Api(client()); CoreV1Api core=new CoreV1Api(client()); String ns=spec.path("namespace").asText();
            V1Deployment existing=null; try { existing=apps.readNamespacedDeployment(id,ns).execute(); } catch(ApiException x) { if(x.getCode()!=404) throw x; }
            if(existing!=null) owned(existing.getMetadata(),id,spec.path("observed").path("uid").asText());
            if(existing!=null && existing.getMetadata().getAnnotations()!=null) {
                String prior=existing.getMetadata().getAnnotations().get(REVISION);
                if(prior!=null && Long.parseLong(prior)>version) throw new DependencyException("stale_generation",false);
            }
            if(delete) return delete(apps,core,id,ns,existing);
            ensurePullSecret(core,id,ns,spec);
            V1Deployment desired=deployment(id,version,spec);
            if(existing==null) existing=apps.createNamespacedDeployment(ns,desired).execute();
            else if(existing.getMetadata().getAnnotations()==null || !Long.toString(version).equals(existing.getMetadata().getAnnotations().get(REVISION))) {
                desired.getMetadata().setResourceVersion(existing.getMetadata().getResourceVersion());
                existing=apps.replaceNamespacedDeployment(id,ns,desired).execute();
            }
            ensureService(core,id,ns,spec);
            var pods=core.listNamespacedPod(ns).labelSelector(OWNER+"="+id).execute().getItems();
            return observation(id,version,spec,existing,pods);
        } catch(ApiException x) {
            if(x.getCode()==401 || x.getCode()==403) throw new DependencyException("cluster_permission_denied",false);
            if(x.getCode()==409) throw new DependencyException("cluster_resource_conflict",true);
            if(x.getCode()==422) throw new DependencyException("cluster_validation_failed",false);
            throw new DependencyException("cluster_unavailable",true);
        }
    }
    public V1Deployment deployment(String id,long version,ObjectNode spec) {
        var labels=Map.of(OWNER,id); String ns=spec.path("namespace").asText();
        V1Container c=new V1Container().name("service").image(spec.path("image").asText()).imagePullPolicy("IfNotPresent");
        if(spec.hasNonNull("command")) c.setCommand(strings(spec.get("command")));
        if(spec.hasNonNull("args")) c.setArgs(strings(spec.get("args")));
        if(spec.has("encrypted_env")) {
            ObjectNode values=store.parse(secrets.decrypt(spec.path("encrypted_env").asText()));
            values.fields().forEachRemaining(e->c.addEnvItem(new V1EnvVar().name(e.getKey()).value(e.getValue().asText())));
        }
        Map<String,Quantity> resources=new HashMap<>();
        resources.put("cpu",Quantity.fromString(spec.path("resources").path("cpu_cores").asText()));
        resources.put("memory",Quantity.fromString(spec.path("resources").path("memory_gib").asText()+"Gi"));
        int gpu=spec.path("resources").path("gpu_per_replica").asInt(); if(gpu>0) resources.put("nvidia.com/gpu",Quantity.fromString(Integer.toString(gpu)));
        c.setResources(new V1ResourceRequirements().requests(resources).limits(resources));
        for(JsonNode port:spec.path("ports")) c.addPortsItem(new V1ContainerPort().name(port.path("name").asText()).containerPort(port.path("port").asInt()).protocol(port.path("protocol").asText()));
        c.setReadinessProbe(probe(spec.path("readiness"),false)); if(spec.hasNonNull("startup")) c.setStartupProbe(probe(spec.path("startup"),true));
        c.setSecurityContext(new V1SecurityContext().allowPrivilegeEscalation(false).capabilities(new V1Capabilities().drop(List.of("ALL"))));
        V1PodSpec pod=new V1PodSpec().containers(List.of(c)).automountServiceAccountToken(false)
            .terminationGracePeriodSeconds(spec.path("termination_grace_seconds").asLong()).imagePullSecrets(List.of(new V1LocalObjectReference().name(id+"-pull")));
        JsonNode placement=spec.path("placement"); if(placement.hasNonNull("node_pool")) pod.setNodeSelector(Map.of("platform.cluster.io/pool",placement.path("node_pool").asText()));
        if(placement.path("allowed_nodes").isArray() && !placement.path("allowed_nodes").isEmpty()) {
            pod.setAffinity(new V1Affinity().nodeAffinity(new V1NodeAffinity().requiredDuringSchedulingIgnoredDuringExecution(new V1NodeSelector().nodeSelectorTerms(List.of(
                new V1NodeSelectorTerm().matchExpressions(List.of(new V1NodeSelectorRequirement().key("kubernetes.io/hostname").operator("In").values(strings(placement.path("allowed_nodes"))))))))));
        }
        return new V1Deployment().apiVersion("apps/v1").kind("Deployment")
            .metadata(new V1ObjectMeta().name(id).namespace(ns).labels(labels).annotations(Map.of(REVISION,Long.toString(version))))
            .spec(new V1DeploymentSpec().replicas(spec.path("replicas").asInt()).progressDeadlineSeconds(600)
                .selector(new V1LabelSelector().matchLabels(labels)).template(new V1PodTemplateSpec().metadata(new V1ObjectMeta().labels(labels).annotations(Map.of(REVISION,Long.toString(version)))).spec(pod)));
    }
    private V1Probe probe(JsonNode config,boolean startup) {
        if(config.path("type").asText().equals("process")) return null;
        V1Probe p=new V1Probe().periodSeconds(5).timeoutSeconds(2).failureThreshold(startup?120:3);
        if(config.path("type").asText().equals("http")) p.setHttpGet(new V1HTTPGetAction().path(config.path("path").asText()).port(new IntOrString(config.path("port").asInt())));
        else p.setTcpSocket(new V1TCPSocketAction().port(new IntOrString(config.path("port").asInt()))); return p;
    }
    private void ensurePullSecret(CoreV1Api core,String id,String ns,ObjectNode spec) throws ApiException {
        ObjectNode creds=store.parse(secrets.decrypt(spec.path("pull_credentials").asText()));
        String auth=Base64.getEncoder().encodeToString((creds.path("username").asText()+":"+creds.path("password").asText()).getBytes(StandardCharsets.UTF_8));
        String config=store.object(Map.of("auths",Map.of(creds.path("registry").asText(),Map.of("auth",auth)))).toString();
        V1Secret secret=new V1Secret().metadata(new V1ObjectMeta().name(id+"-pull").namespace(ns).labels(Map.of(OWNER,id))).type("kubernetes.io/dockerconfigjson").data(Map.of(".dockerconfigjson",config.getBytes(StandardCharsets.UTF_8)));
        try { V1Secret previous=core.readNamespacedSecret(id+"-pull",ns).execute(); owned(previous.getMetadata(),id,"");
            if(!Arrays.equals(previous.getData().get(".dockerconfigjson"),config.getBytes(StandardCharsets.UTF_8))) { secret.getMetadata().setResourceVersion(previous.getMetadata().getResourceVersion()); core.replaceNamespacedSecret(id+"-pull",ns,secret).execute(); }
        } catch(ApiException x) { if(x.getCode()!=404) throw x; core.createNamespacedSecret(ns,secret).execute(); }
    }
    private void ensureService(CoreV1Api core,String id,String ns,ObjectNode spec) throws ApiException {
        List<V1ServicePort> ports=new ArrayList<>(); for(JsonNode p:spec.path("ports")) ports.add(new V1ServicePort().name(p.path("name").asText()).port(p.path("port").asInt()).targetPort(new IntOrString(p.path("port").asInt())).protocol(p.path("protocol").asText()));
        V1Service desired=new V1Service().metadata(new V1ObjectMeta().name(id).namespace(ns).labels(Map.of(OWNER,id))).spec(new V1ServiceSpec().type("ClusterIP").selector(Map.of(OWNER,id)).ports(ports));
        try { V1Service old=core.readNamespacedService(id,ns).execute(); owned(old.getMetadata(),id,"");
            if(!Objects.equals(old.getSpec().getPorts(),ports)) { desired.getMetadata().setResourceVersion(old.getMetadata().getResourceVersion()); desired.getSpec().setClusterIP(old.getSpec().getClusterIP()); core.replaceNamespacedService(id,ns,desired).execute(); }
        } catch(ApiException x) { if(x.getCode()!=404) throw x; core.createNamespacedService(ns,desired).execute(); }
    }
    private ObjectNode delete(AppsV1Api apps,CoreV1Api core,String id,String ns,V1Deployment existing) throws ApiException {
        if(existing!=null) apps.deleteNamespacedDeployment(id,ns).body(new V1DeleteOptions().propagationPolicy("Foreground").preconditions(new V1Preconditions().uid(existing.getMetadata().getUid()))).execute();
        boolean remains=false;
        try { V1Service svc=core.readNamespacedService(id,ns).execute(); owned(svc.getMetadata(),id,""); core.deleteNamespacedService(id,ns).body(new V1DeleteOptions().preconditions(new V1Preconditions().uid(svc.getMetadata().getUid()))).execute(); remains=true; } catch(ApiException x) { if(x.getCode()!=404) throw x; }
        try { V1Secret secret=core.readNamespacedSecret(id+"-pull",ns).execute(); owned(secret.getMetadata(),id,""); core.deleteNamespacedSecret(id+"-pull",ns).body(new V1DeleteOptions().preconditions(new V1Preconditions().uid(secret.getMetadata().getUid()))).execute(); remains=true; } catch(ApiException x) { if(x.getCode()!=404) throw x; }
        var pods=core.listNamespacedPod(ns).labelSelector(OWNER+"="+id).execute().getItems();
        return store.object(Map.of("phase",existing==null && !remains && pods.isEmpty()?"deleted":"deleting","complete",existing==null && !remains && pods.isEmpty()));
    }
    public ObjectNode observation(String id,long version,ObjectNode spec,V1Deployment deployment,List<V1Pod> pods) {
        V1DeploymentStatus s=deployment.getStatus(); int expected=spec.path("replicas").asInt();
        boolean generation=s!=null && s.getObservedGeneration()!=null && s.getObservedGeneration()>=deployment.getMetadata().getGeneration();
        boolean ready=expected==0 ? generation && pods.isEmpty() : generation && number(s.getUpdatedReplicas())==expected && number(s.getAvailableReplicas())==expected && number(s.getReplicas())==expected;
        String phase=ready?(expected==0?"stopped":"ready"):"scheduling",error="";
        var instances=store.json.createArrayNode();
        for(V1Pod pod:pods) {
            var item=instances.addObject().put("name",pod.getMetadata().getName()).put("node",pod.getSpec().getNodeName());
            if(pod.getStatus()==null) continue;
            item.put("phase",pod.getStatus().getPhase());
            if(pod.getStatus().getConditions()!=null) for(V1PodCondition condition:pod.getStatus().getConditions()) {
                if("Unschedulable".equals(condition.getReason())) { phase="blocked"; error="unschedulable"; }
            }
            if(pod.getStatus().getContainerStatuses()!=null) for(V1ContainerStatus container:pod.getStatus().getContainerStatuses()) {
                if(container.getState()!=null && container.getState().getWaiting()!=null) {
                    String reason=container.getState().getWaiting().getReason();
                    if("ErrImagePull".equals(reason)||"ImagePullBackOff".equals(reason)) { phase="blocked"; error=pullError(container.getState().getWaiting().getMessage()); }
                    else if("CrashLoopBackOff".equals(reason)) { phase="blocked"; error="container_crash"; }
                    else if(!phase.equals("blocked")) phase="pulling";
                } else if(!ready && !phase.equals("blocked")) phase="starting";
            }
        }
        if(!ready && s!=null && s.getConditions()!=null && s.getConditions().stream().anyMatch(c->"ProgressDeadlineExceeded".equals(c.getReason()))) { phase="blocked"; error="rollout_deadline_exceeded"; }
        if(error.equals("image_pull_failed") && spec.path("observed").path("error_code").asText().startsWith("image_")) error=spec.path("observed").path("error_code").asText();
        ObjectNode result=store.object(Map.of("phase",phase,"complete",ready,"desired_generation",version,"uid",deployment.getMetadata().getUid(),"readiness_mode",spec.path("readiness").path("type").asText()));
        if(!error.isEmpty()) result.put("error_code",error); result.set("instances",instances);
        result.putObject("access").put("scope","cluster").put("host",id+"."+spec.path("namespace").asText()+".svc.cluster.local"); return result;
    }
    private int number(Integer n) { return n==null?0:n; }
    private String pullError(String message) {
        String text=message==null?"":message.toLowerCase(Locale.ROOT);
        if(text.contains("unauthorized") || text.contains("denied") || text.contains("403") || text.contains("401")) return "image_pull_unauthorized";
        if(text.contains("not found") || text.contains("manifest unknown")) return "image_not_found";
        if(text.contains("no space left")) return "image_disk_full";
        if(text.contains("certificate") || text.contains("x509")) return "image_registry_tls_failed";
        if(text.contains("timeout") || text.contains("deadline exceeded")) return "image_pull_timeout";
        return "image_pull_failed";
    }
    private void owned(V1ObjectMeta meta,String id,String uid) {
        if(meta.getLabels()==null || !id.equals(meta.getLabels().get(OWNER)) || (!uid.isBlank() && !uid.equals(meta.getUid()))) throw new DependencyException("ownership_conflict",false);
    }
    private List<String> strings(JsonNode array) { List<String> values=new ArrayList<>(); array.forEach(v->values.add(v.asText())); return values; }
}
