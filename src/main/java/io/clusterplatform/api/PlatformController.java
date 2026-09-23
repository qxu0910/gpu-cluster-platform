package io.clusterplatform.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.clusterplatform.application.PlatformService;
import io.clusterplatform.domain.*;
import io.clusterplatform.persistence.Store;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
@ConditionalOnExpression("'${platform.role}' != 'worker'")
public class PlatformController {
    private final PlatformService service; private final Store store; private final RequestContext context;
    public PlatformController(PlatformService service,Store store,RequestContext context) { this.service=service; this.store=store; this.context=context; }
    private ResponseEntity<?> mutation(Authentication a,HttpServletRequest q,Object body,Supplier<ObjectNode> action) {
        String project=context.project(a,true);
        ObjectNode result=service.mutate(a.getName(),project,q.getMethod(),q.getRequestURI(),q.getHeader("Idempotency-Key"),q.getAttribute("requestId").toString(),store.json.valueToTree(body),action);
        return ResponseEntity.accepted().location(URI.create("/v1/operations/"+result.path("operation_id").asText())).body(result);
    }
    @PostMapping("/image-uploads") ResponseEntity<?> upload(Authentication a,HttpServletRequest q,@Valid @RequestBody Contracts.Upload b) {
        return mutation(a,q,b,()->service.upload(context.project(a,true),b));
    }
    @GetMapping("/image-uploads/{id}") ObjectNode upload(Authentication a,@PathVariable String id) { return service.safe(store.resource(context.project(a,false),"upload",id,false)); }
    @PostMapping("/image-uploads/{id}/credentials") ResponseEntity<?> credentials(Authentication a,@PathVariable String id) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.credentials(context.project(a,true),id));
    }
    @PostMapping("/image-uploads/{id}/complete") ResponseEntity<?> complete(Authentication a,HttpServletRequest q,@PathVariable String id,@Valid @RequestBody Contracts.Complete b) {
        return mutation(a,q,b,()->service.complete(context.project(a,true),id,b));
    }
    @GetMapping("/images") Object images(Authentication a,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="20") int limit) { return list(a,"image",offset,limit); }
    @GetMapping("/images/{id}") ObjectNode image(Authentication a,@PathVariable String id) { return service.safe(store.resource(context.project(a,false),"image",id,false)); }
    @DeleteMapping("/images/{id}") ResponseEntity<?> deleteImage(Authentication a,HttpServletRequest q,@PathVariable String id) {
        return mutation(a,q,Map.of(),()->service.deleteImage(context.project(a,true),id));
    }
    @PostMapping("/workloads") ResponseEntity<?> create(Authentication a,HttpServletRequest q,@Valid @RequestBody Contracts.Workload b) {
        return mutation(a,q,b,()->service.create(context.project(a,true),b));
    }
    @GetMapping("/workloads") Object workloads(Authentication a,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="20") int limit) { return list(a,"workload",offset,limit); }
    @GetMapping("/workloads/{id}") ObjectNode workload(Authentication a,@PathVariable String id) { return service.safe(store.resource(context.project(a,false),"workload",id,false)); }
    @PostMapping("/workloads/{id}/{action:start|stop|scale}") ResponseEntity<?> change(Authentication a,HttpServletRequest q,@PathVariable String id,@PathVariable String action,@Valid @RequestBody Contracts.Change b) {
        return mutation(a,q,b,()->service.change(context.project(a,true),id,action,b.expectedVersion(),b.replicas(),null));
    }
    @PostMapping("/workloads/{id}/revisions") ResponseEntity<?> revision(Authentication a,HttpServletRequest q,@PathVariable String id,@Valid @RequestBody Contracts.Revision b) {
        return mutation(a,q,b,()->service.change(context.project(a,true),id,"revision",b.expectedVersion(),null,b.configuration()));
    }
    @DeleteMapping("/workloads/{id}") ResponseEntity<?> delete(Authentication a,HttpServletRequest q,@PathVariable String id,@RequestHeader("If-Match") String condition) {
        long version;
        try {
            if(!condition.matches("\"[1-9][0-9]*\"")) throw new NumberFormatException();
            version=Long.parseLong(condition.substring(1,condition.length()-1));
        } catch(NumberFormatException invalid) { throw new ApiException(400,"invalid_version_precondition"); }
        return mutation(a,q,Map.of("expected_version",version),()->service.change(context.project(a,true),id,"delete",version,null,null));
    }
    @GetMapping("/operations/{id}") ObjectNode operation(Authentication a,@PathVariable String id) { return store.operation(context.project(a,false),id); }
    @GetMapping("/capabilities") Object capabilities(Authentication a) { context.project(a,false); return Map.of("workload_types",new String[]{"service"},"node_maintenance",false,"image_prewarm",false,"physical_actions",false); }
    private Object list(Authentication a,String kind,int offset,int limit) { return Map.of("items",store.list(context.project(a,false),kind,offset,limit).stream().map(service::safe).toList(),"offset",offset,"limit",limit); }
}
