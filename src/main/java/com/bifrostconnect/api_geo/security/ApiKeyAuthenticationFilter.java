package com.bifrostconnect.api_geo.security;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.bifrostconnect.api_geo.security.ApiKeyAuthService.ContextoApiKey;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String ATTR_CONTEXTO_API_KEY = "API_KEY_CONTEXTO";
    private static final String HEADER_API_KEY = "X-API-Key";

    private final ApiKeyAuthService apiKeyAuthService;

    public ApiKeyAuthenticationFilter(ApiKeyAuthService apiKeyAuthService) {
        this.apiKeyAuthService = apiKeyAuthService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = extrairCaminho(request);
        if (path == null) {
            return true;
        }
        return !(path.equals("/v1/externo")
                || path.startsWith("/v1/externo/")
                || path.equals("/api/v1/externo")
                || path.startsWith("/api/v1/externo/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader(HEADER_API_KEY);
        Optional<ContextoApiKey> contextoOpt = apiKeyAuthService.autenticar(apiKey);

        if (contextoOpt.isEmpty()) {
            escreverErroJson(response, HttpStatus.UNAUTHORIZED,
                    "API Key ausente, inválida, expirada, revogada ou vinculada a usuário inativo.");
            return;
        }

        ContextoApiKey contexto = contextoOpt.get();
        String ip = extrairIp(request);
        String rota = request.getRequestURI();

        // Tarefa 14: Rate Limiting
        if (!apiKeyAuthService.permitirRequisicao(contexto.chaveId())) {
            apiKeyAuthService.registrarLogAcessoExterno(
                    contexto.chaveId(), rota, HttpStatus.TOO_MANY_REQUESTS.value(), ip);
            escreverErroJson(response, HttpStatus.TOO_MANY_REQUESTS,
                    "Limite de 60 requisições por minuto excedido para esta API Key.");
            return;
        }

        // Registra o uso e a auditoria
        apiKeyAuthService.registrarUsoEAuditoria(contexto, request.getMethod(), rota, ip);

        // Injeta o contexto na requisição e autentica no Spring Security
        request.setAttribute(ATTR_CONTEXTO_API_KEY, contexto);
        var authorities = contexto.escopos().stream()
                .map(escopo -> new SimpleGrantedAuthority("SCOPE_" + escopo))
                .toList();

        var auth = new UsernamePasswordAuthenticationToken(
                "apikey:" + contexto.chaveId(),
                null,
                authorities.isEmpty() ? List.of(new SimpleGrantedAuthority("ROLE_EXTERNO")) : authorities);

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(auth);
        SecurityContextHolder.setContext(securityContext);

        boolean falhou = false;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException ex) {
            falhou = true;
            throw ex;
        } finally {
            int statusHttp = response.getStatus();
            if (falhou && statusHttp < 400) {
                statusHttp = HttpStatus.INTERNAL_SERVER_ERROR.value();
            }
            apiKeyAuthService.registrarLogAcessoExterno(contexto.chaveId(), rota, statusHttp, ip);
        }
    }

    private String extrairCaminho(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri != null && !uri.isEmpty()) {
            if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
                return uri.substring(contextPath.length());
            }
            return uri;
        }
        String servletPath = request.getServletPath() != null ? request.getServletPath() : "";
        String pathInfo = request.getPathInfo() != null ? request.getPathInfo() : "";
        return servletPath + pathInfo;
    }

    private void escreverErroJson(HttpServletResponse response, HttpStatus status, String mensagem) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"erro\":\"" + mensagem.replace("\"", "\\\"") + "\"}");
    }

    private String extrairIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
