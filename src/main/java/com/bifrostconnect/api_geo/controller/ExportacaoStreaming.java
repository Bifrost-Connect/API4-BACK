package com.bifrostconnect.api_geo.controller;

import com.bifrostconnect.api_geo.service.ExportacaoService;
import com.bifrostconnect.api_geo.service.ExportacaoService.ExportacaoPreparada;
import com.bifrostconnect.api_geo.service.ExportacaoService.FiltroExportacao;
import com.bifrostconnect.api_geo.service.ExportacaoService.Solicitante;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.io.IOException;

// US11 - Tarefa 12: a parte HTTP da exportação, usada pelas três rotas que exportam
// (/exportacoes/resultados, /versoes/{id}/exportar e /indicadores/{id}/exportar).
//
// Escreve direto no output stream da resposta, na mesma thread do request (chunked do Tomcat).
// Não usa StreamingResponseBody de propósito: ele roda em outra thread com dispatch assíncrono,
// passaria de novo pelo RbacInterceptor e ainda tem o timeout de 30s do async do Spring.
@Component
public class ExportacaoStreaming {

    public static final String HEADER_TOTAL = "X-Total-Registros";

    private final ExportacaoService exportacaoService;

    public ExportacaoStreaming(ExportacaoService exportacaoService) {
        this.exportacaoService = exportacaoService;
    }

    public void entregar(
            FiltroExportacao filtro,
            String formato,
            Long usuarioIdInformado,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        String login = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                ? authentication.getName()
                : null;

        // valida e audita antes de escrever qualquer coisa: erro aqui ainda vira { "erro": ... }
        ExportacaoPreparada preparada = exportacaoService.preparar(
                filtro, formato, new Solicitante(login, usuarioIdInformado, request.getRemoteAddr()));

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(preparada.formato().getContentType());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + preparada.nomeArquivo() + "\"");
        response.setHeader(HEADER_TOTAL, String.valueOf(preparada.totalRegistros()));
        response.setHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                HttpHeaders.CONTENT_DISPOSITION + ", " + HEADER_TOTAL);

        exportacaoService.escrever(preparada, filtro, response.getOutputStream());
        response.flushBuffer();
    }
}
