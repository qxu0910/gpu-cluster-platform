package io.clusterplatform.worker;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.clusterplatform.adapters.*;
import io.clusterplatform.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnExpression("'${platform.role}' != 'api'")
public class OperationWorker {
    private final Store store; private final RegistryAdapter registry; private final ClusterAdapter cluster; private final TransactionTemplate tx;
    private final String owner=UUID.randomUUID().toString();
    public OperationWorker(Store store,RegistryAdapter registry,ClusterAdapter cluster,TransactionTemplate tx) { this.store=store; this.registry=registry; this.cluster=cluster; this.tx=tx; }
    @Scheduled(fixedDelayString="${platform.poll-ms:2000}")
    public void tick() {
        for(int i=0;i<8;i++) {
            ObjectNode operation=claim(); if(operation==null) return;
            try(ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor()) {
                heartbeat.scheduleAtFixedRate(()->store.jdbc.update("UPDATE operation SET lease_until=now()+interval '45 seconds' WHERE id=? AND lease_owner=?",operation.path("id").asText(),owner),10,10,TimeUnit.SECONDS);
                try { execute(operation); }
                catch(DependencyException x) { finish(operation,x.retryable?"unknown":"failed",x.retryable?"dependency_unavailable":"failed",x.code,null); }
                catch(Exception x) { finish(operation,"unknown","reconciling","execution_uncertain",null); }
                finally { heartbeat.shutdownNow(); }
            }
        }
    }
    public ObjectNode claim() {
        return tx.execute(s->{
            var ids=store.jdbc.query("SELECT id FROM operation WHERE status IN ('pending','running','blocked','unknown') AND next_run<=now() AND (lease_until IS NULL OR lease_until<now()) ORDER BY next_run,created_at FOR UPDATE SKIP LOCKED LIMIT 1",(rs,n)->rs.getString(1));
            if(ids.isEmpty()) return null; String id=ids.getFirst();
            store.jdbc.update("UPDATE operation SET lease_owner=?,lease_until=now()+interval '45 seconds',status='running',attempts=attempts+1 WHERE id=?",owner,id);
            return store.jdbc.queryForObject("SELECT * FROM operation WHERE id=?",(rs,n)->store.object(Map.of("id",id,"project",rs.getString("project"),"target_id",rs.getString("target_id"),"action",rs.getString("action"),"generation",rs.getLong("generation"))),id);
        });
    }
    private void execute(ObjectNode op) {
        String project=op.path("project").asText(),id=op.path("target_id").asText(),action=op.path("action").asText();
        if(action.equals("authorize_upload")) {
            ObjectNode upload=store.resource(project,"upload",id,false);
            ObjectNode authorized=upload.has("credentials")?upload:registry.authorize(id,upload);
            tx.executeWithoutResult(s->{ if(!owns(op)) return; store.save(id,1,authorized); finish(op,"succeeded","upload_authorized",null,null); });
        } else if(action.equals("verify_upload")) {
            ObjectNode upload=store.resource(project,"upload",id,false); ObjectNode image=registry.verify(upload);
            registry.revoke(upload);
            tx.executeWithoutResult(s->{ if(!owns(op)) return;
                ObjectNode fresh=store.resource(project,"upload",id,true);
                if(!fresh.has("image_id")) fresh.put("image_id",store.insert("image",project,image));
                fresh.remove("credentials"); store.save(id,1,fresh); finish(op,"succeeded","available",null,null);
            });
        } else {
            ObjectNode spec=store.resource(project,"workload",id,false);
            ObjectNode observation=cluster.reconcile(id,op.path("generation").asLong(),spec,action.equals("delete"));
            boolean complete=observation.path("complete").asBoolean(); String phase=observation.path("phase").asText();
            tx.executeWithoutResult(s->{ if(!owns(op)) return;
                finish(op,complete?"succeeded":phase.equals("blocked")?"blocked":"running",phase,observation.has("error_code")?observation.path("error_code").asText():null,observation);
                if(complete && action.equals("delete")) store.jdbc.update("UPDATE resource SET deleted=true WHERE id=?",id);
            });
        }
    }
    private boolean owns(ObjectNode op) {
        store.jdbc.queryForObject("SELECT id FROM resource WHERE id=? FOR UPDATE",String.class,op.path("target_id").asText());
        return Boolean.TRUE.equals(store.jdbc.queryForObject("SELECT lease_owner=? AND lease_until>now() FROM operation WHERE id=? FOR UPDATE",Boolean.class,owner,op.path("id").asText()));
    }
    private void finish(ObjectNode op,String status,String stage,String error,ObjectNode observation) {
        tx.executeWithoutResult(s->{
            if(!owns(op)) return;
            store.jdbc.update("UPDATE operation SET status=?,stage=?,error_code=?,lease_owner=NULL,lease_until=NULL,next_run=now()+interval '3 seconds',updated_at=now() WHERE id=?",status,stage,error,op.path("id").asText());
            if(observation!=null) store.jdbc.update("UPDATE resource SET observed=?::jsonb,observed_at=now() WHERE id=?",observation.toString(),op.path("target_id").asText());
            else if(status.equals("unknown")) store.jdbc.update("UPDATE resource SET observed=observed || '{\"phase\":\"unknown\",\"stale\":true}'::jsonb WHERE id=?",op.path("target_id").asText());
            if(status.equals("succeeded") || status.equals("failed")) store.jdbc.update("INSERT INTO audit_event(caller,project,request_id,action,target_id,result) VALUES ('worker',?,?,?,?,?)",op.path("project").asText(),op.path("id").asText(),op.path("action").asText(),op.path("target_id").asText(),status);
        });
    }
    @Scheduled(fixedDelay=30000)
    public void observe() {
        // Periodic reconciliation keeps completed resources fresh and repairs missed watch events.
        var resources=store.jdbc.query("SELECT id,project FROM resource r WHERE kind='workload' AND NOT deleted AND NOT EXISTS (SELECT 1 FROM operation o WHERE o.target_id=r.id AND o.status IN ('pending','running','blocked','unknown'))",(rs,n)->Map.entry(rs.getString(1),rs.getString(2)));
        for(var item:resources) {
            tx.executeWithoutResult(s->{
                ObjectNode spec;
                try { spec=store.resource(item.getValue(),"workload",item.getKey(),true); } catch(io.clusterplatform.domain.ApiException missing) { return; }
                if(store.jdbc.queryForObject("SELECT count(*) FROM operation WHERE target_id=? AND status IN ('pending','running','blocked','unknown')",Integer.class,item.getKey())>0) return;
                try {
                    ObjectNode observed=cluster.reconcile(item.getKey(),spec.path("version").asLong(),spec,false);
                    store.jdbc.update("UPDATE resource SET observed=?::jsonb,observed_at=now() WHERE id=? AND version=? AND NOT deleted",observed.toString(),item.getKey(),spec.path("version").asLong());
                } catch(Exception x) { store.jdbc.update("UPDATE resource SET observed=observed || '{\"phase\":\"unknown\",\"stale\":true}'::jsonb WHERE id=?",item.getKey()); }
            });
        }
        var uploads=store.jdbc.query("SELECT id,project FROM resource WHERE kind='upload' AND jsonb_exists(body,'credentials') AND (body->>'expires_at')::timestamptz<now()",(rs,n)->Map.entry(rs.getString(1),rs.getString(2)));
        for(var item:uploads) try {
            ObjectNode upload=store.resource(item.getValue(),"upload",item.getKey(),false); registry.revoke(upload);
            store.jdbc.update("UPDATE resource SET body=body-'credentials' WHERE id=?",item.getKey());
        } catch(Exception ignored) { /* Retry expired credential cleanup on the next pass. */ }
    }
}
