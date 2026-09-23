package io.clusterplatform.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.clusterplatform.domain.*;
import io.clusterplatform.persistence.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PlatformService {
    private final Store store; private final Secrets secrets; private final Environment env; private final TransactionTemplate tx;
    public PlatformService(Store store,Secrets secrets,Environment env,TransactionTemplate tx) { this.store=store; this.secrets=secrets; this.env=env; this.tx=tx; }
    public ObjectNode mutate(String caller,String project,String method,String path,String key,String requestId,JsonNode request,Supplier<ObjectNode> action) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw new ApiException(400,"idempotency_key_required");
        String hash=hash(canonical(request));
        return tx.execute(status->{
            // Transaction advisory locks also cover absent idempotency rows.
            store.jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,caller+"|"+method+"|"+path+"|"+key);
            store.jdbc.update("DELETE FROM idempotency WHERE caller=? AND method=? AND path=? AND key=? AND expires_at<now()",caller,method,path,key);
            var previous=store.jdbc.query("SELECT request_hash,response::text FROM idempotency WHERE caller=? AND method=? AND path=? AND key=?",
                (rs,n)->Map.entry(rs.getString(1),rs.getString(2)),caller,method,path,key);
            if(!previous.isEmpty()) {
                if(!previous.getFirst().getKey().equals(hash)) throw ApiException.conflict("idempotency_conflict");
                return store.parse(previous.getFirst().getValue());
            }
            ObjectNode result=action.get();
            store.jdbc.update("INSERT INTO idempotency(caller,method,path,key,request_hash,response) VALUES (?,?,?,?,?,?::jsonb)",caller,method,path,key,hash,result.toString());
            store.jdbc.update("INSERT INTO audit_event(caller,project,request_id,action,target_id,result) VALUES (?,?,?,?,?,?)",caller,project,requestId,method+" "+path,result.path("target_id").asText(),"accepted");
            return result;
        });
    }
    public ObjectNode upload(String project,Contracts.Upload request) {
        ObjectNode body=store.object(request); body.put("expires_at",Instant.now().plusSeconds(86400).toString());
        body.put("upload_secret_seed",secrets.encrypt(UUID.randomUUID().toString()+UUID.randomUUID()));
        body.put("pull_secret_seed",secrets.encrypt(UUID.randomUUID().toString()+UUID.randomUUID()));
        String id=store.insert("upload",project,body); return operation(project,id,"authorize_upload",1);
    }
    public ObjectNode complete(String project,String id,Contracts.Complete request) {
        ObjectNode body=store.resource(project,"upload",id,true); assertIdle(id);
        if(body.has("image_id")) throw ApiException.conflict("upload_already_completed");
        if(Instant.parse(body.path("expires_at").asText()).isBefore(Instant.now())) throw ApiException.conflict("upload_expired");
        if(!body.has("credentials")) throw ApiException.conflict("upload_not_authorized");
        if(request.digest()!=null) body.put("expected_digest",request.digest()); store.save(id,body.path("version").asLong(),body);
        return operation(project,id,"verify_upload",1);
    }
    public ObjectNode create(String project,Contracts.Workload request) {
        validateWorkload(request); ObjectNode image=store.resource(project,"image",request.imageId(),true);
        ObjectNode body=store.object(request); prepare(body,image);
        String id=store.insert("workload",project,body); revision(id,1,body); return operation(project,id,"create",1);
    }
    public ObjectNode change(String project,String id,String action,long expected,Integer replicas,Contracts.Workload configuration) {
        ObjectNode body=store.resource(project,"workload",id,true);
        long version=body.path("version").asLong(); if(expected!=version) throw ApiException.conflict("version_conflict");
        if(action.equals("stop") || action.equals("delete")) supersedeIdleOperation(id,action); else assertIdle(id);
        if(action.equals("revision")) {
            validateWorkload(configuration); if(!configuration.name().equals(body.path("name").asText())) throw new ApiException(400,"name_immutable");
            ObjectNode image=store.resource(project,"image",configuration.imageId(),true);
            body=store.object(configuration); prepare(body,image);
        } else if(action.equals("stop")) {
            if(body.path("replicas").asInt()>0) body.put("resume_replicas",body.path("replicas").asInt()); body.put("replicas",0);
        } else if(action.equals("start")) {
            if(body.path("replicas").asInt()!=0) throw ApiException.conflict("workload_not_stopped");
            int count=replicas==null?body.path("resume_replicas").asInt(1):replicas;
            if(count<1) throw new ApiException(400,"invalid_replicas"); body.put("replicas",count);
        } else if(action.equals("scale")) {
            if(replicas==null) throw new ApiException(400,"replicas_required");
            if(replicas==0 && body.path("replicas").asInt()>0) body.put("resume_replicas",body.path("replicas").asInt()); body.put("replicas",replicas);
        }
        version++; store.save(id,version,body); revision(id,version,body); return operation(project,id,action,version);
    }
    public ObjectNode deleteImage(String project,String id) {
        store.resource(project,"image",id,true);
        Integer count=store.jdbc.queryForObject("SELECT count(*) FROM resource WHERE kind='workload' AND NOT deleted AND body->>'image_id'=?",Integer.class,id);
        Integer history=store.jdbc.queryForObject("SELECT count(*) FROM revision v JOIN resource r ON r.id=v.workload_id WHERE NOT r.deleted AND v.body->>'image_id'=?",Integer.class,id);
        if(count>0 || history>0) throw ApiException.conflict("image_in_use");
        store.jdbc.update("UPDATE resource SET deleted=true WHERE id=?",id);
        ObjectNode result=operation(project,id,"revoke_image",1); store.jdbc.update("UPDATE operation SET status='succeeded',stage='revoked' WHERE id=?",result.path("operation_id").asText()); return result;
    }
    private void prepare(ObjectNode body,ObjectNode image) {
        body.put("image",image.path("reference").asText()); body.put("namespace",env.getRequiredProperty("platform.namespace"));
        body.put("pull_credentials",image.path("pull_credentials").asText());
        body.put("resume_replicas",body.path("replicas").asInt());
        if(body.hasNonNull("env")) { body.put("encrypted_env",secrets.encrypt(body.get("env").toString())); body.remove("env"); }
    }
    private void validateWorkload(Contracts.Workload w) {
        if(!w.clusterId().equals(env.getRequiredProperty("platform.cluster-id"))) throw new ApiException(403,"cluster_not_allowed");
        if(w.resources().gpuPerReplica()==0 && !env.getProperty("platform.cpu-test",Boolean.class,false)) throw new ApiException(400,"gpu_required");
        Set<String> names=new HashSet<>(); Set<Integer> ports=new HashSet<>();
        for(var p:w.ports()) { if(!names.add(p.name()) || !ports.add(p.port())) throw new ApiException(400,"duplicate_port"); }
        validateProbe(w.readiness(),w.ports()); if(w.startup()!=null) validateProbe(w.startup(),w.ports());
    }
    private void validateProbe(Contracts.Probe p,List<Contracts.Port> ports) {
        if(p.type().equals("process")) return;
        if(ports.stream().noneMatch(port->port.port()==p.port() && port.protocol().equals("TCP"))) throw new ApiException(400,"probe_port_not_exposed");
        if(p.type().equals("http") && (p.path()==null || !p.path().startsWith("/"))) throw new ApiException(400,"invalid_probe_path");
    }
    private void revision(String id,long version,ObjectNode body) { store.jdbc.update("INSERT INTO revision(workload_id,version,body) VALUES (?,?,?::jsonb)",id,version,body.toString()); }
    private void assertIdle(String id) {
        if(store.jdbc.queryForObject("SELECT count(*) FROM operation WHERE target_id=? AND status IN ('pending','running','blocked','unknown')",Integer.class,id)>0) throw ApiException.conflict("operation_in_progress");
    }
    private void supersedeIdleOperation(String id,String action) {
        var active=store.jdbc.queryForList("SELECT id,action,(lease_owner IS NOT NULL AND lease_until>now()) AS leased FROM operation WHERE target_id=? AND status IN ('pending','running','blocked','unknown') FOR UPDATE",id);
        for(var op:active) {
            if(Boolean.TRUE.equals(op.get("leased")) || "delete".equals(op.get("action"))) throw ApiException.conflict("operation_in_progress");
            store.jdbc.update("UPDATE operation SET status='failed',stage='superseded',error_code=?,lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE id=?","superseded_by_"+action,op.get("id"));
            store.jdbc.update("INSERT INTO audit_event(caller,project,request_id,action,target_id,result) SELECT 'worker',project,id,action,target_id,'superseded' FROM operation WHERE id=?",op.get("id"));
        }
    }
    private ObjectNode operation(String project,String target,String action,long generation) {
        String id="op-"+UUID.randomUUID(); store.jdbc.update("INSERT INTO operation(id,project,target_id,action,generation) VALUES (?,?,?,?,?)",id,project,target,action,generation);
        return store.object(Map.of("operation_id",id,"target_id",target,"status","accepted"));
    }
    public ObjectNode safe(ObjectNode resource) {
        ObjectNode result=resource.deepCopy(); result.remove(List.of("encrypted_env","credentials","pull_credentials","expected_digest","upload_secret_seed","pull_secret_seed"));
        if(resource.has("encrypted_env")) result.put("environment_redacted",true);
        if(resource.has("observed_at") && Instant.parse(resource.path("observed_at").asText()).isBefore(Instant.now().minusSeconds(120))) {
            result.withObject("observed").put("stale",true).put("phase","unknown");
        }
        return result;
    }
    public ObjectNode credentials(String project,String id) {
        ObjectNode upload=store.resource(project,"upload",id,false);
        if(Instant.parse(upload.path("expires_at").asText()).isBefore(Instant.now()) || upload.has("image_id")) throw ApiException.conflict("upload_expired_or_completed");
        if(!upload.has("credentials")) throw ApiException.conflict("upload_not_authorized");
        return store.parse(secrets.decrypt(upload.path("credentials").asText()));
    }
    public static String canonical(JsonNode value) {
        if(value.isObject()) {
            TreeMap<String,JsonNode> sorted=new TreeMap<>(); value.fields().forEachRemaining(e->sorted.put(e.getKey(),e.getValue()));
            return "{"+sorted.entrySet().stream().map(e->com.fasterxml.jackson.databind.node.TextNode.valueOf(e.getKey()).toString()+":"+canonical(e.getValue())).collect(java.util.stream.Collectors.joining(","))+"}";
        }
        if(value.isArray()) { List<String> items=new ArrayList<>(); value.forEach(v->items.add(canonical(v))); return "["+String.join(",",items)+"]"; } return value.toString();
    }
    private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception x) { throw new IllegalStateException(x); } }
}
