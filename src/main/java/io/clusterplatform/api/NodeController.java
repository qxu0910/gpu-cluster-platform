package io.clusterplatform.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.clusterplatform.application.PlatformService;
import io.clusterplatform.domain.ApiException;
import io.clusterplatform.persistence.Store;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/nodes")
@ConditionalOnExpression("'${platform.role}' != 'worker'")
public class NodeController {
    private final Store store;
    private final PlatformService service;
    private final RequestContext context;
    private final String clusterId;
    private final Set<String> maintenanceNodes;
    public NodeController(Store store,PlatformService service,RequestContext context,Environment env) {
        this.store=store; this.service=service; this.context=context; clusterId=env.getRequiredProperty("platform.cluster-id");
        maintenanceNodes=Arrays.stream(env.getProperty("platform.maintenance-nodes","").split(","))
            .map(String::trim).filter(value->!value.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
    private ObjectNode snapshot(Authentication auth) {
        String project=context.project(auth,false);
        var rows=store.jdbc.query("SELECT nodes,observed_at,error_code FROM node_snapshot WHERE project=? AND cluster_id=?",(rs,n)->{
            ObjectNode result=store.object(Map.of("cluster_id",clusterId));
            try { result.set("items",store.json.readTree(rs.getString("nodes"))); } catch(Exception e) { throw new IllegalStateException("invalid_node_snapshot"); }
            var observed=rs.getTimestamp("observed_at");
            boolean stale=observed==null || observed.toInstant().isBefore(Instant.now().minusSeconds(120)) || rs.getString("error_code")!=null;
            result.put("stale",stale);
            if(observed!=null) result.put("observed_at",observed.toInstant().toString());
            if(rs.getString("error_code")!=null) result.put("error_code",rs.getString("error_code"));
            result.path("items").forEach(node->((ObjectNode)node).put("maintenance_allowed",maintenanceNodes.contains(node.path("name").asText())));
            if(stale) result.path("items").forEach(node->((ObjectNode)node).put("ready","Unknown"));
            return result;
        },project,clusterId);
        if(rows.isEmpty()) {
            var result=store.object(Map.of("cluster_id",clusterId,"stale",true,"error_code","node_observation_pending"));
            result.putArray("items"); return result;
        }
        return rows.getFirst();
    }
    @GetMapping public ObjectNode list(Authentication auth,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="20") int limit) {
        if(offset<0 || limit<1 || limit>100) throw new ApiException(400,"invalid_pagination");
        ObjectNode result=snapshot(auth); var all=result.withArray("items"); var page=store.json.createArrayNode();
        for(int i=offset;i<all.size() && (long)i<(long)offset+limit;i++) page.add(all.get(i));
        result.put("total",all.size()).put("offset",offset).put("limit",limit); result.set("items",page); return result;
    }
    @GetMapping("/{name}") public ObjectNode detail(Authentication auth,@PathVariable String name) {
        ObjectNode result=snapshot(auth);
        for(var node:result.withArray("items")) if(name.equals(node.path("name").asText())) { result.remove("items"); result.set("node",node); return result; }
        if(result.path("stale").asBoolean()) throw new ApiException(503,"node_observation_unavailable");
        throw new ApiException(404,"node_not_found");
    }
    public record NodeChange(@NotBlank @Size(max=128) String expectedUid,
                             @NotBlank @Size(max=128) String expectedResourceVersion) { }
    @PostMapping("/{name}/{action:cordon|uncordon}") public ResponseEntity<ObjectNode> change(
            Authentication auth,HttpServletRequest request,@PathVariable String name,@PathVariable String action,
            @Valid @RequestBody NodeChange body) {
        String project=context.project(auth,true);
        ObjectNode result=service.mutate(auth.getName(),project,request.getMethod(),request.getRequestURI(),
            request.getHeader("Idempotency-Key"),request.getAttribute("requestId").toString(),store.object(body),
            ()->validateAndChange(auth,project,name,action,body));
        return ResponseEntity.accepted().location(URI.create("/v1/operations/"+result.path("operation_id").asText())).body(result);
    }
    private ObjectNode validateAndChange(Authentication auth,String project,String name,String action,NodeChange body) {
        if(!maintenanceNodes.contains(name)) throw new ApiException(403,"node_maintenance_not_allowed");
        ObjectNode current=snapshot(auth);
        if(current.path("stale").asBoolean()) throw new ApiException(503,"node_observation_unavailable");
        ObjectNode observed=null;
        for(var node:current.withArray("items")) if(name.equals(node.path("name").asText())) { observed=(ObjectNode)node; break; }
        if(observed==null) throw new ApiException(404,"node_not_found");
        if(!body.expectedUid().equals(observed.path("uid").asText()) ||
            !body.expectedResourceVersion().equals(observed.path("resource_version").asText())) throw ApiException.conflict("node_version_conflict");
        return service.changeNode(project,name,body.expectedUid(),body.expectedResourceVersion(),action.equals("cordon"));
    }
}
