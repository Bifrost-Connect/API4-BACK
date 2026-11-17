package com.bifrostconnect.api_geo.controller;

import com.bifrostconnect.api_geo.security.Permissao;
import com.bifrostconnect.api_geo.security.RequirePermission;
import com.bifrostconnect.api_geo.service.ExportacaoService.FiltroExportacao;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.math.BigDecimal;

// US07 + US11 - Tarefa 12: /versoes/{id}/exportar é a mesma exportação de /exportacoes/resultados,
// só que com o id da versão (processo) no caminho
@RestController
@RequestMapping("/versoes")
public class VersaoController {

    private final ExportacaoStreaming exportacaoStreaming;

    public VersaoController(ExportacaoStreaming exportacaoStreaming) {
        this.exportacaoStreaming = exportacaoStreaming;
    }

    @RequirePermission(Permissao.RELATORIO_EXPORTAR)
    @GetMapping("/{id}/exportar")
    public void exportarVersao(
            @PathVariable Long id,
            @RequestParam(name = "formato", defaultValue = "geojson") String formato,
            @RequestParam(name = "codigo_imovel", required = false) String codigoImovel,
            @RequestParam(name = "com_sobreposicao", required = false) Boolean comSobreposicao,
            @RequestParam(name = "hectares_min", required = false) BigDecimal hectaresMin,
            @RequestParam(name = "fonte_camada", required = false) String fonteCamada,
            @RequestHeader(value = "X-Gestor-ID", required = false) Long gestorIdHeader,
            @RequestParam(value = "gestorId", required = false) Long gestorIdParam,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        exportacaoStreaming.entregar(
                new FiltroExportacao(id, codigoImovel, comSobreposicao, hectaresMin, fonteCamada),
                formato,
                gestorIdHeader != null ? gestorIdHeader : gestorIdParam,
                authentication, request, response);
    }
}
