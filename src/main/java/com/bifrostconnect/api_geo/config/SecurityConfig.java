
package com.bifrostconnect.api_geo.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.config.http.SessionCreationPolicy;

import com.bifrostconnect.api_geo.security.AcessoNegadoHandler;
import com.bifrostconnect.api_geo.security.JwtAuthenticationFilter;
import com.bifrostconnect.api_geo.security.JwtService;
import com.bifrostconnect.api_geo.security.UsuarioSessaoService;

@Configuration
@EnableMethodSecurity
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return bcrypt.encode(rawPassword);
            }

            @Override
            public boolean matches(
                    CharSequence rawPassword,
                    String encodedPassword) {

                if (encodedPassword == null) {
                    return false;
                }

                if (encodedPassword.startsWith("$2a$")
                        || encodedPassword.startsWith("$2b$")
                        || encodedPassword.startsWith("$2y$")) {
                    return bcrypt.matches(rawPassword, encodedPassword);
                }

                // Compatibilidade temporária com senhas antigas já existentes.
                return rawPassword != null
                        && rawPassword.toString().equals(encodedPassword);
            }
        };
    }

    // US13 - Tarefa 14: nenhuma rota fica aberta. Só o login e o preflight do CORS passam sem
    // autenticação; o resto exige usuário (JWT ou Basic) e cada rota ainda pede a sua
    // permissão com @rbac.requirePermission (RbacService).
    // O Basic continua aceito porque o front atual ainda manda as credenciais assim.
    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtService jwtService,
            UsuarioSessaoService usuarioSessaoService,
            AcessoNegadoHandler acessoNegadoHandler)
            throws Exception {

        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(e -> e.accessDeniedHandler(acessoNegadoHandler))
            .addFilterBefore(
                new JwtAuthenticationFilter(jwtService, usuarioSessaoService),
                BasicAuthenticationFilter.class)
            .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder) {

        UserDetails operador = User.withUsername("operador")
                .password(passwordEncoder.encode("Troca123"))
                .roles("OPERADOR")
                .build();

        UserDetails auditor = User.withUsername("auditor")
                .password(passwordEncoder.encode("Troca123"))
                .roles("AUDITOR")
                .build();

        UserDetails analista = User.withUsername("analista")
                .password(passwordEncoder.encode("Troca123"))
                .roles("ANALISTA")
                .build();

        UserDetails admin = User.withUsername("admin")
                .password(passwordEncoder.encode("Troca123"))
                .roles("ADMINISTRADOR")
                .build();

        UserDetailsService usuariosMemoria = new InMemoryUserDetailsManager(
                operador, auditor, analista, admin);

        return username -> {
            String identificador = username == null
                    ? ""
                    : username.trim();

            try {
                List<UserDetails> encontrados = jdbc.query(
                        "SELECT u.email, u.senha_hash, u.ativo, p.nome AS perfil " +
                        "FROM usuario u " +
                        "JOIN perfil p ON p.id = u.perfil_id " +
                        "WHERE LOWER(u.email) = LOWER(?) " +
                        "OR LOWER(SPLIT_PART(u.email, '@', 1)) = LOWER(?) " +
                        "ORDER BY CASE WHEN LOWER(u.email) = LOWER(?) " +
                        "THEN 0 ELSE 1 END LIMIT 1",
                        (rs, rowNum) -> User.withUsername(identificador)
                                .password(rs.getString("senha_hash"))
                                .roles(rs.getString("perfil"))
                                .disabled(!rs.getBoolean("ativo"))
                                .build(),
                        identificador,
                        identificador,
                        identificador);

                if (!encontrados.isEmpty()) {
                    return encontrados.get(0);
                }
            } catch (EmptyResultDataAccessException ignored) {
                // Se não houver registro no banco, tenta os usuários legados.
            }

            try {
                return usuariosMemoria.loadUserByUsername(identificador);
            } catch (UsernameNotFoundException e) {
                throw new UsernameNotFoundException(
                        "Usuário não encontrado ou inativo.", e);
            }
        };
    }
}
