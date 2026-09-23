package io.clusterplatform.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest q,HttpServletResponse r,FilterChain chain) throws IOException,ServletException {
        String id=q.getHeader("X-Request-ID");
        if(id==null || !id.matches("[A-Za-z0-9-]{1,80}")) id=UUID.randomUUID().toString();
        q.setAttribute("requestId",id); r.setHeader("X-Request-ID",id); chain.doFilter(q,r);
    }
}
