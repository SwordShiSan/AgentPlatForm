package com.agentplatform.trigger.http;

import com.agentplatform.api.identity.IdentityApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HttpSecurityConfiguration {
    private static void stateless(HttpSecurity http) throws Exception {
        // The platform's existing DB token/CSRF/Origin contract is enforced by IdentityBoundaryFilter.
        http.csrf(csrf -> csrf.disable()).formLogin(login -> login.disable()).httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable()).requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    }
    @Bean @Profile("identity-local")
    SecurityFilterChain identitySecurity(HttpSecurity http, IdentityApi identity,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) throws Exception {
        stateless(http);
        http.addFilterBefore(new IdentityBoundaryFilter(identity, errors), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(access -> access.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers(IdentityBoundaryFilter.PUBLIC.toArray(String[]::new)).permitAll()
                        .requestMatchers("/api/v1/**").authenticated().anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, failure) ->
                        errors.resolveException(request, response, null,
                                new com.agentplatform.domain.identity.IdentityFailure(401, "AUTHENTICATION_REQUIRED"))));
        return http.build();
    }
    @Bean @Profile("!identity-local")
    SecurityFilterChain baselineSecurity(HttpSecurity http) throws Exception {
        stateless(http);
        http.authorizeHttpRequests(access -> access.anyRequest().permitAll());
        return http.build();
    }
}
