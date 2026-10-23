package com.agentplatform.trigger.http;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods=false) @Profile("workspace-runner")
public class RunnerSecurityConfiguration {
    @Bean SecurityFilterChain runnerSecurity(HttpSecurity http,JwtDecoder decoder) throws Exception {
        http.csrf(csrf->csrf.disable()).formLogin(form->form.disable()).httpBasic(basic->basic.disable())
                .logout(logout->logout.disable()).requestCache(cache->cache.disable())
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(access->access.requestMatchers("/internal/operations","/internal/operations/*",
                        "/internal/operations/v2/environments","/internal/operations/v2/environments/*",
                        "/internal/workspace-uses/v1","/internal/workspace-uses/v1/*").authenticated().anyRequest().denyAll())
                .oauth2ResourceServer(resource->resource.jwt(jwt->jwt.decoder(decoder)));
        http.addFilterAfter(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response,jakarta.servlet.FilterChain chain)
                    throws java.io.IOException,jakarta.servlet.ServletException {
                boolean environment="/internal/operations/v2/environments".equals(request.getRequestURI());
                long bound=environment?16384:65536;
                if("POST".equals(request.getMethod())&&("/internal/operations".equals(request.getRequestURI())||environment||"/internal/workspace-uses/v1".equals(request.getRequestURI()))
                        &&(request.getContentLengthLong()<0||request.getContentLengthLong()>bound)) {
                    response.setStatus(request.getContentLengthLong()<0?411:413);
                    response.setContentType("application/problem+json");
                    response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Bounded runner request required\",\"code\":\"RUNNER_BODY_REJECTED\"}");
                    return;
                }
                chain.doFilter(request,response);
            }
        },BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
