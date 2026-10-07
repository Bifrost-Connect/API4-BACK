package com.bifrostconnect.api_geo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // Ajuste conforme a sua política de CSRF
            .authorizeHttpRequests(auth -> auth
                // Tarefa 7 e 8: Auditor tem acesso a GET, mas é bloqueado em POST/PUT/DELETE
                .requestMatchers(HttpMethod.GET, "/api/v1/**", "/processos/**", "/carga/**").hasAnyRole("AUDITOR", "ADMINISTRADOR", "OPERADOR")
                
                // Rotas de escrita são exclusivas para operadores ou admins. Auditor recebe 403 Forbidden automaticamente.
                .requestMatchers(HttpMethod.POST, "/api/v1/**", "/processos/**", "/carga/**").hasAnyRole("ADMINISTRADOR", "OPERADOR")
                .requestMatchers(HttpMethod.PUT, "/api/v1/**").hasAnyRole("ADMINISTRADOR", "OPERADOR")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/**").hasRole("ADMINISTRADOR")
                
                .anyRequest().permitAll()
            );

        return http.build();
    }
}