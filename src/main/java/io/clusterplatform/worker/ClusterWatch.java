package io.clusterplatform.worker;

import com.google.gson.reflect.TypeToken;
import io.clusterplatform.adapters.KubernetesAdapter;
import io.clusterplatform.persistence.Store;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.util.Watch;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${platform.role}' != 'api'")
public class ClusterWatch {
    private final KubernetesAdapter cluster; private final Store store; private final String namespace;
    private String resourceVersion;
    public ClusterWatch(KubernetesAdapter cluster,Store store,Environment env) { this.cluster=cluster; this.store=store; namespace=env.getRequiredProperty("platform.namespace"); }
    @Scheduled(fixedDelay=1000)
    public void watch() {
        try {
            AppsV1Api api=new AppsV1Api(cluster.client());
            if(resourceVersion==null) resourceVersion=api.listNamespacedDeployment(namespace).labelSelector(KubernetesAdapter.OWNER).execute().getMetadata().getResourceVersion();
            try(Watch<V1Deployment> watch=Watch.createWatch(cluster.client(),api.listNamespacedDeployment(namespace).labelSelector(KubernetesAdapter.OWNER).resourceVersion(resourceVersion).watch(true).timeoutSeconds(5).buildCall(null),new TypeToken<Watch.Response<V1Deployment>>(){}.getType())) {
                for(var event:watch) {
                    if("ERROR".equals(event.type)) { resourceVersion=null; break; }
                    if(event.object==null || event.object.getMetadata()==null) continue;
                    resourceVersion=event.object.getMetadata().getResourceVersion();
                    String id=event.object.getMetadata().getLabels().get(KubernetesAdapter.OWNER);
                    store.jdbc.update("UPDATE operation SET next_run=now() WHERE target_id=? AND status IN ('pending','running','blocked','unknown') AND lease_owner IS NULL",id);
                }
            }
        } catch(Exception x) { resourceVersion=null; }
    }
}
