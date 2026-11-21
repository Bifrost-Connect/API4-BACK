package com.bifrostconnect.api_geo.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bifrostconnect.api_geo.exception.ErroPadronizadoException;
import org.springframework.web.server.ResponseStatusException;

import com.bifrostconnect.api_geo.security.ApiKeyAuthService.ContextoApiKey;
import com.bifrostconnect.api_geo.security.ApiKeyAuthenticationFilter;
import com.bifrostconnect.api_geo.security.RotaPublica;
import com.bifrostconnect.api_geo.service.ExternoConsumoService;

import jakarta.servlet.http.HttpServletRequest;

// US12 - Tarefa 13: endpoints externos somente leitura protegidos por apiKeyAuth (/api/v1/externo/...).
// Usa @RotaPublica para pular o RbacInterceptor (que exige sessão de usuário interno),
// pois a autenticação já foi executada pelo ApiKeyAuthenticationFilter via X-API-Key.
@RestController
@RequestMapping("/v1/externo")
@RotaPublica
public class ExternoConsumoController {

    private final ExternoConsumoService externoService;

    public ExternoConsumoController(ExternoConsumoService externoService) {
        this.externoService = externoService;
    }

    @GetMapping("/catalogo")
    public ResponseEntity<?> listarCatalogo(
            @RequestParam(required = false) Long conjuntoId,
            @RequestParam(required = false) String anoSafra,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {

        exigirEscopo(request, "CATALOGO", "CATALOGO_CONSULTAR", "LEITURA");

        return ResponseEntity.ok(
                externoService.listarCatalogoPublicado(conjuntoId, anoSafra, page, size));
    }

    @GetMapping("/bases/{id}/metricas")
    public ResponseEntity<?> consultarMetricas(
            @PathVariable Long id,
            HttpServletRequest request) {

        exigirEscopo(request, "METRICAS", "CATALOGO_CONSULTAR", "LEITURA");

        return ResponseEntity.ok(externoService.buscarMetricasBase(id));
    }

    @GetMapping(value = "/bases/{id}/geojson", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> consultarGeoJson(
            @PathVariable Long id,
            HttpServletRequest request) {

        exigirEscopo(request, "GEOJSON", "MAPA_CONSULTAR", "RELATORIO_EXPORTAR", "LEITURA");

        return ResponseEntity.ok(externoService.exportarGeoJsonBase(id));
    }

    private void exigirEscopo(HttpServletRequest request, String... escoposAceitos) {
        Object attr = request.getAttribute(ApiKeyAuthenticationFilter.ATTR_CONTEXTO_API_KEY);
        if (!(attr instanceof ContextoApiKey contexto)) {
            throw new ErroPadronizadoException(
                    HttpStatus.UNAUTHORIZED, "API_KEY_AUSENTE", "Informe a API Key no header X-API-Key.");
        }

        for (String escopo : escoposAceitos) {
            if (contexto.possuiEscopo(escopo)) {
                return;
            }
        }

        // Tarefa 16: chave válida, mas sem o escopo da rota -> 403 (e não 401: não adianta autenticar de novo)
        throw new ErroPadronizadoException(
                HttpStatus.FORBIDDEN,
                "ESCOPO_INSUFICIENTE",
                "A API Key não possui o escopo exigido por esta rota. Escopos aceitos: "
                        + String.join(", ", escoposAceitos) + ".");
    }
}
