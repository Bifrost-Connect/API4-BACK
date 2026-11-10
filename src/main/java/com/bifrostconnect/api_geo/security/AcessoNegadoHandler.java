package com.bifrostconnect.api_geo.security;

import java.io.IOException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.stereotype.Component;

import com.bifrostconnect.api_geo.security.UsuarioSessaoService.EstadoUsuario;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// US13 - Tarefa 18: todo 403 de perfil passa por aqui e vira um ACESSO_NEGADO_403 na auditoria,
// com usuário, endpoint, método HTTP e IP.
//
// Se a auditoria falhar a resposta continua sendo 403: um banco fora do ar não pode transformar
// acesso negado em 500. Quem não está logado nem chega aqui, cai no 401
@Component
public class AcessoNegadoHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(AcessoNegadoHandler.class);

    private final UsuarioSessaoService usuarioSessaoService;
    private final JdbcTemplate jdbc;
    private final AccessDeniedHandler respostaPadrao = new AccessDeniedHandlerImpl();

    public AcessoNegadoHandler(UsuarioSessaoService usuarioSessaoService, JdbcTemplate jdbc) {
        this.usuarioSessaoService = usuarioSessaoService;
        this.jdbc = jdbc;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException negacao) throws IOException, ServletException {

        try {
            registrar(request, negacao);
        } catch (RuntimeException e) {
            log.error("Não foi possível gravar o ACESSO_NEGADO_403 de {} {}",
                    request.getMethod(), request.getRequestURI(), e);
        }

        respostaPadrao.handle(request, response, negacao);
    }

    private void registrar(HttpServletRequest request, AccessDeniedException negacao) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String login = auth == null ? "desconhecido" : auth.getName();

        Optional<EstadoUsuario> usuario = usuarioSessaoService.buscarPorLogin(login);

        // usuário de memória não tem id: o login fica só nos detalhes (V32)
        jdbc.update("""
                INSERT INTO auditoria (usuario_id, processo_id, acao, descricao, detalhes, data_hora, ip)
                VALUES (?, NULL, 'ACESSO_NEGADO_403', ?, ?, now(), ?)
                """,
                usuario.map(EstadoUsuario::id).orElse(null),
                negacao.getMessage(),
                "metodo=" + request.getMethod() + "; endpoint=" + request.getRequestURI() + "; usuario=" + login,
                request.getRemoteAddr());
    }
}
