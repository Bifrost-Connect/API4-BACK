package com.bifrostconnect.api_geo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // Tarefa 8: Restringe a rota PATCH de fechar (retorna 403 Forbidden se não tiver o perfil GESTOR)
                .requestMatchers(HttpMethod.PATCH, "/api/indicadores/*/fechar", "/versoes/*/fechar", "/api/versoes/*/fechar").hasRole("GESTOR")
                
                // Permissões gerais das rotas
                .requestMatchers(HttpMethod.GET, "/api/indicadores/**", "/versoes/**", "/api/v1/**", "/processos/**", "/carga/**").hasAnyRole("GESTOR", "AUDITOR", "ADMINISTRADOR", "OPERADOR")
                .requestMatchers(HttpMethod.POST, "/api/v1/**", "/processos/**", "/carga/**").hasAnyRole("ADMINISTRADOR", "OPERADOR")
                .requestMatchers(HttpMethod.PUT, "/api/v1/**", "/api/indicadores/**", "/versoes/**").hasAnyRole("ADMINISTRADOR", "OPERADOR")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/**").hasRole("ADMINISTRADOR")
                
                .anyRequest().permitAll()
            )
            .httpBasic(withDefaults());

        return http.build();
    }
}