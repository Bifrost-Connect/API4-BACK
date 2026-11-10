package com.bifrostconnect.api_geo.security;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.bifrostconnect.api_geo.security.JwtService.ClaimsToken;
import com.bifrostconnect.api_geo.security.JwtService.TokenInvalidoException;
import com.bifrostconnect.api_geo.security.UsuarioSessaoService.EstadoUsuario;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// US13 - Tarefas 14 e 16: todo "Authorization: Bearer" passa aqui. Além da assinatura e da
// expiração, confere no banco se o usuário continua ativo e se a versao_sessao do token é a
// atual. Inativou ou trocou o perfil? A versão subiu e o token antigo leva 401 na hora.
// O perfil que vale é o do banco, não o que foi gravado no token.
// Não é @Component de propósito: senão o Spring registra ele de novo como filtro de servlet
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIXO = "Bearer ";

    private final JwtService jwtService;
    private final UsuarioSessaoService usuarioSessaoService;

    public JwtAuthenticationFilter(JwtService jwtService, UsuarioSessaoService usuarioSessaoService) {
        this.jwtService = jwtService;
        this.usuarioSessaoService = usuarioSessaoService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(PREFIXO)) {
            // sem Bearer segue o fluxo normal (Basic do front atual, ou anônimo)
            chain.doFilter(request, response);
            return;
        }

        try {
            ClaimsToken claims = jwtService.validar(header.substring(PREFIXO.length()).trim());

            EstadoUsuario usuario = usuarioSessaoService.buscarPorId(claims.usuarioId())
                    .orElseThrow(() -> new TokenInvalidoException("Usuário do token não existe mais."));

            if (!usuario.ativo()) {
                throw new TokenInvalidoException("Usuário inativado.");
            }
            if (usuario.versaoSessao() != claims.versaoSessao()) {
                throw new TokenInvalidoException("Sessão revogada: o perfil ou o status do usuário mudou. Faça login de novo.");
            }

            UsernamePasswordAuthenticationToken autenticacao = new UsernamePasswordAuthenticationToken(
                    usuario.email(), null, List.of(new SimpleGrantedAuthority("ROLE_" + usuario.perfil())));
            SecurityContextHolder.getContext().setAuthentication(autenticacao);

        } catch (TokenInvalidoException e) {
            SecurityContextHolder.clearContext();
            responder401(response, e.getMessage());
            return;
        }

        chain.doFilter(request, response);
    }

    static void responder401(HttpServletResponse response, String mensagem) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"erro\":\"" + mensagem.replace("\"", "'") + "\"}");
    }
}
