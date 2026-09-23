package io.clusterplatform.api;

import io.clusterplatform.domain.ApiException;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class RequestContext {
    private final Environment env;
    public RequestContext(Environment env) { this.env=env; }
    public String project(Authentication auth,boolean write) {
        if(!env.getRequiredProperty("platform.subject").equals(auth.getName())) throw new ApiException(403,"caller_not_mapped");
        String scope=write?"SCOPE_platform.write":"SCOPE_platform.read";
        if(auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals(scope))) throw new ApiException(403,"insufficient_scope");
        return env.getRequiredProperty("platform.project");
    }
}
