package com.bifrostconnect.api_geo.security;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.bifrostconnect.api_geo.security.ApiKeyAuthService.ContextoApiKey;
import com.bifrostconnect.api_geo.security.ApiKeyAuthService.ResultadoAutenticacao;

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

    // Olha a URI inteira (menos o context path). Antes usava o getServletPath(), mas com
    // spring.mvc.servlet.path=/api ele devolve só "/api" e o filtro nunca rodava fora dos testes
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return true;
        }
        String contexto = request.getContextPath();
        String path = contexto != null && !contexto.isEmpty() && uri.startsWith(contexto)
                ? uri.substring(contexto.length())
                : uri;
        return !(path.startsWith("/v1/externo") || path.startsWith("/api/v1/externo"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader(HEADER_API_KEY);
        ResultadoAutenticacao resultado = apiKeyAuthService.validar(apiKey);

        // Tarefa 16: ausente, inexistente, expirada, revogada (ou dono inativo) -> 401
        if (!resultado.autenticada()) {
            response.setHeader("WWW-Authenticate", "ApiKey header=\"" + HEADER_API_KEY + "\"");
            RespostaErroPadrao.escrever(response, HttpStatus.UNAUTHORIZED,
                    resultado.motivo().getCodigo(), resultado.motivo().getMensagem(), request.getRequestURI());
            return;
        }

        ContextoApiKey contexto = resultado.contexto();

        // Tarefa 14 / 16: passou do limite por minuto -> 429 com o tempo de espera no Retry-After
        if (!apiKeyAuthService.permitirRequisicao(contexto.chaveId())) {
            response.setHeader("Retry-After", String.valueOf(apiKeyAuthService.segundosAteLiberar(contexto.chaveId())));
            response.setHeader("X-RateLimit-Limit", String.valueOf(ApiKeyAuthService.LIMITE_REQUISICOES_POR_MINUTO));
            response.setHeader("X-RateLimit-Remaining", "0");
            RespostaErroPadrao.escrever(response, HttpStatus.TOO_MANY_REQUESTS,
                    "LIMITE_REQUISICOES_EXCEDIDO",
                    "Limite de " + ApiKeyAuthService.LIMITE_REQUISICOES_POR_MINUTO
                            + " requisições por minuto excedido para esta API Key.",
                    request.getRequestURI());
            return;
        }

        // Registra o uso e a auditoria
        String ip = extrairIp(request);
        apiKeyAuthService.registrarUsoEAuditoria(contexto, request.getMethod(), request.getRequestURI(), ip);

        // Injeta o contexto na requisição e autentica no Spring Security
        request.setAttribute(ATTR_CONTEXTO_API_KEY, contexto);
        var authorities = contexto.escopos().stream()
                .map(escopo -> new SimpleGrantedAuthority("SCOPE_" + escopo))
                .toList();

        var auth = new UsernamePasswordAuthenticationToken(
                "apikey:" + contexto.chaveId(),
                null,
                authorities.isEmpty() ? List.of(new SimpleGrantedAuthority("ROLE_EXTERNO")) : authorities);

        SecurityContextHolder.getContext().setAuthentication(auth);
        filterChain.doFilter(request, response);
    }

    private String extrairIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}