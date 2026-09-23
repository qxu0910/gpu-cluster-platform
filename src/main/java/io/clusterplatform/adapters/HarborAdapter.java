package io.clusterplatform.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.clusterplatform.persistence.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class HarborAdapter implements RegistryAdapter {
    private final Store store; private final Secrets secrets; private final String base,auth;
    private final okhttp3.OkHttpClient http=new okhttp3.OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(5)).readTimeout(Duration.ofSeconds(10)).followRedirects(false).build();
    public HarborAdapter(Store store,Secrets secrets,Environment env) {
        this.store=store; this.secrets=secrets; base=env.getRequiredProperty("platform.harbor-url");
        if(!base.startsWith("https://")) throw new IllegalStateException("Harbor requires HTTPS");
        auth="Basic "+Base64.getEncoder().encodeToString((env.getProperty("platform.harbor-user","")+":"+env.getProperty("platform.harbor-password","")).getBytes(StandardCharsets.UTF_8));
    }
    public ObjectNode authorize(String id,ObjectNode upload) {
        String project="p-"+id.substring("upload-".length()).replace("-","");
        JsonNode existing=call("GET","/projects/"+project,null,404);
        if(existing==null) {
            call("POST","/projects",Map.of("project_name",project,"metadata",Map.of("public","false")),409);
            existing=call("GET","/projects/"+project,null,0);
        }
        long projectId=existing.path("project_id").asLong();
        // Dedicated project limits each upload account even when repository-scoped grants are unavailable.
        ObjectNode credentials=robot(project,projectId,"upload",1,List.of("pull","push"),"Aa1"+secrets.decrypt(upload.path("upload_secret_seed").asText()));
        ObjectNode pull=robot(project,projectId,"pull",-1,List.of("pull"),"Aa1"+secrets.decrypt(upload.path("pull_secret_seed").asText()));
        upload.put("harbor_project",project); upload.put("target",URI.create(base).getAuthority()+"/"+project+"/"+upload.path("repository").asText()+":"+upload.path("tag").asText());
        upload.put("credentials",secrets.encrypt(credentials.toString())); upload.put("pull_credentials",secrets.encrypt(pull.toString()));
        return upload;
    }
    private ObjectNode robot(String project,long projectId,String name,int days,List<String> actions,String desiredSecret) {
        // Harbor's project robot query uses case-sensitive Level and ProjectID, not the display name.
        JsonNode robots=call("GET","/robots?q=Level%3Dproject%2CProjectID%3D"+projectId+"&page_size=100",null,0);
        JsonNode found=null; if(robots!=null) for(JsonNode r:robots) if(r.path("name").asText().endsWith(project+"+"+name)) found=r;
        ObjectNode credential=store.json.createObjectNode();
        if(found==null) {
            List<Map<String,String>> access=actions.stream().map(a->Map.of("resource","repository","action",a)).toList();
            JsonNode r=call("POST","/robots",Map.of("name",name,"description","Managed by gpu-cluster-platform", "duration",days,"level","project","permissions",List.of(Map.of("kind","project","namespace",project,"access",access))),0);
            credential.put("username",r.path("name").asText()); credential.put("robot_id",r.path("id").asLong());
        } else {
            credential.put("username",found.path("name").asText()); credential.put("robot_id",found.path("id").asLong());
        }
        // A durable encrypted seed makes retries converge to the same secret, including ambiguous PATCH outcomes.
        call("PATCH","/robots/"+credential.path("robot_id").asLong(),Map.of("secret",desiredSecret),0);
        credential.put("password",desiredSecret);
        credential.put("registry",URI.create(base).getAuthority()); return credential;
    }
    public ObjectNode verify(ObjectNode upload) {
        String project=upload.path("harbor_project").asText(), repository=upload.path("repository").asText();
        JsonNode artifact=call("GET","/projects/"+project+"/repositories/"+repository+"/artifacts/"+upload.path("tag").asText(),null,0);
        String digest=artifact.path("digest").asText();
        if(!digest.matches("sha256:[a-f0-9]{64}")) throw new DependencyException("invalid_registry_digest",false);
        if(upload.has("expected_digest") && !digest.equals(upload.path("expected_digest").asText())) throw new DependencyException("digest_mismatch",false);
        String os=artifact.path("extra_attrs").path("os").asText(),arch=artifact.path("extra_attrs").path("architecture").asText();
        if(!os.equals("linux") || !arch.equals("amd64")) throw new DependencyException("unsupported_image_platform",false);
        ObjectNode image=store.json.createObjectNode(); image.put("digest",digest); image.put("tag",upload.path("tag").asText());
        image.put("reference",URI.create(base).getAuthority()+"/"+project+"/"+repository+"@"+digest);
        image.put("architecture",arch); image.put("os",os); image.put("status","available");
        image.put("pull_credentials",upload.path("pull_credentials").asText()); return image;
    }
    public void revoke(ObjectNode upload) {
        if(upload.has("credentials")) {
            ObjectNode c=store.parse(secrets.decrypt(upload.path("credentials").asText()));
            call("DELETE","/robots/"+c.path("robot_id").asLong(),null,404);
        }
    }
    private JsonNode call(String method,String path,Object body,int allowed) {
        try {
            // OkHttp also supports PATCH; reuse the Kubernetes client's HTTP implementation without a JDK selector.
            var request=new okhttp3.Request.Builder().url(base+"/api/v2.0"+path).header("Authorization",auth);
            okhttp3.RequestBody payload=body==null?null:okhttp3.RequestBody.create(store.json.writeValueAsString(body),okhttp3.MediaType.get("application/json"));
            if(payload==null && (method.equals("POST")||method.equals("PUT")||method.equals("PATCH"))) payload=okhttp3.RequestBody.create("",okhttp3.MediaType.get("application/json"));
            request.method(method,payload);
            try(var response=http.newCall(request.build()).execute()) {
            int status=response.code();
            if(status==allowed) return null;
            if(status==401 || status==403) throw new DependencyException("registry_authentication_failed",false);
            if(status==404) throw new DependencyException("image_not_found",false);
            if(status>=400 && status<500) throw new DependencyException("registry_request_rejected_"+status,false);
            if(status<200 || status>=300) throw new DependencyException("registry_unavailable",true);
            String text=response.body()==null?"":response.body().string();
            return text.isBlank()?store.json.createObjectNode():store.json.readTree(text);
            }
        } catch(DependencyException x) { throw x; }
        catch(javax.net.ssl.SSLException x) { throw new DependencyException("registry_tls_failed",true); }
        catch(java.net.UnknownHostException x) { throw new DependencyException("registry_dns_failed",true); }
        catch(java.net.SocketTimeoutException x) { throw new DependencyException("registry_timeout",true); }
        catch(java.net.ConnectException x) { throw new DependencyException("registry_connection_failed",true); }
        catch(Exception x) { throw new DependencyException("registry_unavailable",true); }
    }
}
