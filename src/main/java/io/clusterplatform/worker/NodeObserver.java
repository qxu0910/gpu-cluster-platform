package io.clusterplatform.worker;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.clusterplatform.adapters.KubernetesAdapter;
import io.clusterplatform.persistence.Store;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Node;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${platform.role}' != 'api'")
public class NodeObserver {
    private final KubernetesAdapter cluster;
    private final Store store;
    private final String project, clusterId;
    public NodeObserver(KubernetesAdapter cluster, Store store, Environment env) {
        this.cluster=cluster; this.store=store;
        project=env.getRequiredProperty("platform.project");
        clusterId=env.getRequiredProperty("platform.cluster-id");
    }
    @Scheduled(fixedDelay=15000)
    public void observe() {
        try {
            var nodes=store.json.createArrayNode();
            String cursor=null;
            do {
                var page=new CoreV1Api(cluster.client()).listNode().limit(100)._continue(cursor).execute();
                for(var node:page.getItems()) nodes.add(project(node,store));
                cursor=page.getMetadata()==null?null:page.getMetadata().getContinue();
            } while(cursor!=null && !cursor.isBlank());
            store.jdbc.update("INSERT INTO node_snapshot(project,cluster_id,nodes,observed_at) VALUES (?,?,?::jsonb,now()) ON CONFLICT(project,cluster_id) DO UPDATE SET nodes=excluded.nodes,observed_at=excluded.observed_at,error_code=NULL",project,clusterId,nodes.toString());
        } catch(Exception failure) {
            String error=failure instanceof io.kubernetes.client.openapi.ApiException api && (api.getCode()==401 || api.getCode()==403)?"node_observation_forbidden":"node_observation_unavailable";
            store.jdbc.update("INSERT INTO node_snapshot(project,cluster_id,error_code) VALUES (?,?,?) ON CONFLICT(project,cluster_id) DO UPDATE SET error_code=excluded.error_code",project,clusterId,error);
        }
    }
    public static ObjectNode project(V1Node node, Store store) {
        ObjectNode result=store.object(Map.of("name",node.getMetadata().getName(),"ready","Unknown",
            "unschedulable",node.getSpec()!=null && Boolean.TRUE.equals(node.getSpec().getUnschedulable())));
        var conditions=result.putArray("conditions");
        var status=node.getStatus();
        if(status==null) return result;
        if(status.getConditions()!=null) for(var condition:status.getConditions()) {
            if(!java.util.Set.of("Ready","MemoryPressure","DiskPressure","PIDPressure","NetworkUnavailable").contains(condition.getType())) continue;
            ObjectNode item=conditions.addObject().put("type",condition.getType()).put("status",condition.getStatus());
            if(condition.getLastTransitionTime()!=null) item.put("last_transition_at",condition.getLastTransitionTime().toString());
            if("Ready".equals(condition.getType())) result.put("ready",condition.getStatus());
        }
        var capacity=result.putObject("capacity"); var allocatable=result.putObject("allocatable");
        for(String resource:java.util.List.of("cpu","memory","pods","nvidia.com/gpu")) {
            if(status.getCapacity()!=null && status.getCapacity().containsKey(resource)) capacity.put(resource,status.getCapacity().get(resource).toSuffixedString());
            if(status.getAllocatable()!=null && status.getAllocatable().containsKey(resource)) allocatable.put(resource,status.getAllocatable().get(resource).toSuffixedString());
        }
        if(status.getNodeInfo()!=null) {
            result.put("kubelet_version",status.getNodeInfo().getKubeletVersion());
            result.put("runtime_version",status.getNodeInfo().getContainerRuntimeVersion());
            result.put("architecture",status.getNodeInfo().getArchitecture());
            result.put("operating_system",status.getNodeInfo().getOperatingSystem());
        }
        return result;
    }
}
