package com.bifrostconnect.api_geo.controller;

import com.bifrostconnect.api_geo.security.Permissao;
import com.bifrostconnect.api_geo.security.RequirePermission;
import com.bifrostconnect.api_geo.service.ExportacaoService;
import com.bifrostconnect.api_geo.service.ExportacaoService.FiltroExportacao;
import com.bifrostconnect.api_geo.service.ExportacaoService.FiltroHistorico;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

// US11 - Tarefas 12 e 13. O servlet path já é /api, então as rotas finais são /api/exportacoes/...
@RestController
@RequestMapping("/exportacoes")
public class ExportacaoController {

    private final ExportacaoService exportacaoService;
    private final ExportacaoStreaming exportacaoStreaming;

    public ExportacaoController(ExportacaoService exportacaoService, ExportacaoStreaming exportacaoStreaming) {
        this.exportacaoService = exportacaoService;
        this.exportacaoStreaming = exportacaoStreaming;
    }

    // Tarefa 12: arquivo inteiro (CSV ou GeoJSON) em blocos, como anexo
    @RequirePermission(Permissao.RELATORIO_EXPORTAR)
    @GetMapping("/resultados")
    public void exportarResultados(
            @RequestParam(name = "processo_id", required = false) Long processoId,
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

        FiltroExportacao filtro = new FiltroExportacao(
                processoId, codigoImovel, comSobreposicao, hectaresMin, fonteCamada);

        exportacaoStreaming.entregar(filtro, formato,
                gestorIdHeader != null ? gestorIdHeader : gestorIdParam,
                authentication, request, response);
    }

    // Tarefa 13: contagem + 10 linhas de amostra com os mesmos filtros da exportação
    @RequirePermission(Permissao.RELATORIO_EXPORTAR)
    @GetMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview(
            @RequestParam(name = "processo_id", required = false) Long processoId,
            @RequestParam(name = "codigo_imovel", required = false) String codigoImovel,
            @RequestParam(name = "com_sobreposicao", required = false) Boolean comSobreposicao,
            @RequestParam(name = "hectares_min", required = false) BigDecimal hectaresMin,
            @RequestParam(name = "fonte_camada", required = false) String fonteCamada) {

        return ResponseEntity.ok(exportacaoService.preview(new FiltroExportacao(
                processoId, codigoImovel, comSobreposicao, hectaresMin, fonteCamada)));
    }

    // Tarefa 13: downloads já feitos, paginado no mesmo formato do catálogo ({ items, page, ... })
    @RequirePermission(Permissao.EXPORTACAO_HISTORICO_CONSULTAR)
    @GetMapping("/historico")
    public ResponseEntity<Map<String, Object>> historico(
            @RequestParam(name = "usuario_id", required = false) Long usuarioId,
            @RequestParam(name = "processo_id", required = false) Long processoId,
            @RequestParam(name = "formato", required = false) String formato,
            @RequestParam(name = "de", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(name = "ate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + ExportacaoService.TAMANHO_PADRAO_HISTORICO) int size) {

        return ResponseEntity.ok(exportacaoService.historico(
                new FiltroHistorico(usuarioId, processoId, formato, de, ate), page, size));
    }
}
