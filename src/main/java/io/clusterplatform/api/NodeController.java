package io.clusterplatform.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.clusterplatform.domain.ApiException;
import io.clusterplatform.persistence.Store;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/nodes")
@ConditionalOnExpression("'${platform.role}' != 'worker'")
public class NodeController {
    private final Store store;
    private final RequestContext context;
    private final String clusterId;
    public NodeController(Store store,RequestContext context,Environment env) {
        this.store=store; this.context=context; clusterId=env.getRequiredProperty("platform.cluster-id");
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
}
