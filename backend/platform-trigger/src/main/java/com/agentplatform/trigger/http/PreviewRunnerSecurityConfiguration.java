package com.agentplatform.trigger.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** A preview-only runner exposes no workspace, file-operation, shell, or environment control route. */
@Configuration(proxyBeanMethods=false)
@Profile("preview-runner")
public class PreviewRunnerSecurityConfiguration {
    @Bean SecurityFilterChain previewRunnerSecurity(HttpSecurity http, JwtDecoder decoder) throws Exception {
        http.csrf(csrf->csrf.disable()).formLogin(form->form.disable()).httpBasic(basic->basic.disable())
                .logout(logout->logout.disable()).requestCache(cache->cache.disable())
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(access->access.requestMatchers("/internal/extractions/v1","/internal/extractions/v1/*")
                        .authenticated().anyRequest().denyAll())
                .oauth2ResourceServer(resource->resource.jwt(jwt->jwt.decoder(decoder)));
        http.addFilterAfter(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
                    throws ServletException,IOException {
                if("POST".equals(request.getMethod())&&"/internal/extractions/v1".equals(request.getRequestURI())
                        &&(request.getContentLengthLong()<0||request.getContentLengthLong()>65536)) {
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
