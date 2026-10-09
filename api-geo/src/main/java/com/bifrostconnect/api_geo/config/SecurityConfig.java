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
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // Tarefa 8: Restringe a rota PATCH de fechamento exclusivamente ao perfil GESTOR (retorna 403 se não tiver)
                .requestMatchers(HttpMethod.PATCH, "/versoes/*/fechar").hasRole("GESTOR")
                
                // Outras permissões de rotas
                .requestMatchers(HttpMethod.GET, "/versoes/**", "/api/v1/**", "/processos/**", "/carga/**").hasAnyRole("GESTOR", "AUDITOR", "ADMINISTRADOR", "OPERADOR")
                .requestMatchers(HttpMethod.POST, "/api/v1/**", "/processos/**", "/carga/**").hasAnyRole("ADMINISTRADOR", "OPERADOR")
                .requestMatchers(HttpMethod.PUT, "/api/v1/**", "/versoes/**").hasAnyRole("ADMINISTRADOR", "OPERADOR")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/**").hasRole("ADMINISTRADOR")
                
                .anyRequest().permitAll()
            )
            .httpBasic(httpBasic -> {});

        return http.build();
    }
}