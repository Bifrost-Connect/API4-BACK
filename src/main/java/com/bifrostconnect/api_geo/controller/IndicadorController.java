package com.bifrostconnect.api_geo.controller;

import com.bifrostconnect.api_geo.dto.LinhagemResponseDTO;
import com.bifrostconnect.api_geo.security.Permissao;
import com.bifrostconnect.api_geo.security.RequirePermission;
import com.bifrostconnect.api_geo.service.ExportacaoService.FiltroExportacao;
import com.bifrostconnect.api_geo.service.LinhagemService;
import com.bifrostconnect.api_geo.service.ProcessoService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

// o spring.mvc.servlet.path já é /api, então a rota final fica /api/indicadores/...
@RestController
@RequestMapping("/indicadores")
@RequiredArgsConstructor
public class IndicadorController {

    private final LinhagemService linhagemService;
    private final ProcessoService processoService;
    private final ExportacaoStreaming exportacaoStreaming;

    @RequirePermission(Permissao.LINHAGEM_CONSULTAR)
    @GetMapping("/{id}/linhagem")
    public ResponseEntity<LinhagemResponseDTO> consultarLinhagem(@PathVariable Long id) {
        LinhagemResponseDTO linhagem = linhagemService.buscarLinhagem(id);
        return ResponseEntity.ok(linhagem);
    }

    @RequirePermission(Permissao.VERSAO_PUBLICAR)
    @PatchMapping("/{id}/fechar")
    public ResponseEntity<Void> fecharIndicador(
            @PathVariable Long id,
            @RequestHeader(value = "X-Gestor-ID", required = false) Long gestorIdHeader,
            @RequestParam(value = "gestorId", required = false) Long gestorIdParam) {

        Long gestorId = gestorIdHeader != null ? gestorIdHeader : (gestorIdParam != null ? gestorIdParam : 1L);
        processoService.fecharIndicador(id, gestorId);
        return ResponseEntity.noContent().build();
    }

    // Tarefa 7 (API de Exportação): Rota GET /api/indicadores/{id}/exportar.
    // Fica pro front atual não quebrar, mas agora é a mesma exportação de /exportacoes/resultados
    // (Tarefa 12, streaming) e /versoes/{id}/exportar. A auditoria da Tarefa 14 (gestor, versão,
    // formato, filtros, linhas e data/hora) foi junto pro ExportacaoService: se o registro falhar,
    // o arquivo não é entregue
    @RequirePermission(Permissao.RELATORIO_EXPORTAR)
    @GetMapping("/{id}/exportar")
    public void exportarVersao(
            @PathVariable Long id,
            @RequestParam(value = "formato", defaultValue = "geojson") String formato,
            @RequestHeader(value = "X-Gestor-ID", required = false) Long gestorIdHeader,
            @RequestParam(value = "gestorId", required = false) Long gestorIdParam,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        exportacaoStreaming.entregar(
                new FiltroExportacao(id, null, null, null, null),
                formato,
                gestorIdHeader != null ? gestorIdHeader : gestorIdParam,
                authentication, request, response);
    }
}
