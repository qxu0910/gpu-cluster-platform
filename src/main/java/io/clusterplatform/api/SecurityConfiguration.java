package io.clusterplatform.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfiguration {
    @Bean SecurityFilterChain security(HttpSecurity http, Environment env) throws Exception {
        http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers("/", "/index.html", "/app.js", "/style.css", "/actuator/health/**").permitAll().anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->error(r,401,"unauthorized"))
                .accessDeniedHandler((q,r,x)->error(r,403,"forbidden")));
        String mode=env.getProperty("platform.auth-mode");
        if ("demo".equals(mode)) {
            if (!env.getProperty("platform.cpu-test",Boolean.class,false)) throw new IllegalStateException("Demo authentication requires CPU_TEST=true");
            http.addFilterBefore(new OncePerRequestFilter() {
                protected void doFilterInternal(HttpServletRequest q,HttpServletResponse r,FilterChain chain) throws IOException,ServletException {
                    String host=q.getServerName();
                    if(!"localhost".equalsIgnoreCase(host) && !"127.0.0.1".equals(host) && !"[::1]".equals(host)) {
                        error(r,403,"demo_loopback_only"); return;
                    }
                    String origin=q.getHeader("Origin");
                    String expected=q.getScheme()+"://"+q.getHeader("Host");
                    if(origin!=null && !origin.equalsIgnoreCase(expected)) {
                        error(r,403,"demo_cross_origin_forbidden"); return;
                    }
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        env.getRequiredProperty("platform.subject"),null,List.of(new SimpleGrantedAuthority("SCOPE_platform.write"),new SimpleGrantedAuthority("SCOPE_platform.read"))));
                    chain.doFilter(q,r);
                }
            },UsernamePasswordAuthenticationFilter.class);
        } else if ("local".equals(mode)) {
            String token=env.getRequiredProperty("platform.local-token");
            if(token.length()<32) throw new IllegalStateException("LOCAL_TOKEN must contain at least 32 characters");
            http.addFilterBefore(new OncePerRequestFilter() {
                protected void doFilterInternal(HttpServletRequest q,HttpServletResponse r,FilterChain chain) throws IOException,ServletException {
                    String actual=q.getHeader("Authorization");
                    if(actual!=null && MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),("Bearer "+token).getBytes(StandardCharsets.UTF_8))) {
                        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                            env.getRequiredProperty("platform.subject"),null,List.of(new SimpleGrantedAuthority("SCOPE_platform.write"),new SimpleGrantedAuthority("SCOPE_platform.read"))));
                    }
                    chain.doFilter(q,r);
                }
            },UsernamePasswordAuthenticationFilter.class);
        } else if ("jwt".equals(mode)) {
            String issuer=env.getRequiredProperty("platform.issuer");
            if(!issuer.startsWith("https://")) throw new IllegalStateException("JWT_ISSUER must use HTTPS");
            NimbusJwtDecoder decoder=JwtDecoders.fromIssuerLocation(issuer);
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), jwt ->
                jwt.getAudience().contains(env.getRequiredProperty("platform.audience")) ? OAuth2TokenValidatorResult.success() :
                    OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
            http.oauth2ResourceServer(o->o.jwt(j->j.decoder(decoder)));
        } else {
            throw new IllegalStateException("AUTH_MODE must be demo, local, or jwt");
        }
        return http.build();
    }
    static void error(HttpServletResponse r,int status,String code) throws IOException {
        r.setStatus(status); r.setContentType("application/json"); r.getWriter().write("{\"error\":{\"code\":\""+code+"\",\"message\":\""+code+"\"}}");
    }
}
