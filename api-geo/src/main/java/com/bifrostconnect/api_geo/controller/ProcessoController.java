package com.bifrostconnect.api_geo.controller;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bifrostconnect.api_geo.dto.LogEspacialResponse;
import com.bifrostconnect.api_geo.dto.ProcessLogDetailsResponse;
import com.bifrostconnect.api_geo.dto.ProcessoDashboardResponse;
import com.bifrostconnect.api_geo.dto.ProcessoMetricasResponse;
import com.bifrostconnect.api_geo.entity.ArquivoOriginal;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ArquivoOriginalRepository;
import com.bifrostconnect.api_geo.service.ProcessoDashboardService;
import com.bifrostconnect.api_geo.service.ProcessoService;

@RestController
@RequestMapping("/processos")
public class ProcessoController {

    private final ProcessoService processoService;
    private final ProcessoDashboardService processoDashboardService;
    private final ArquivoOriginalRepository arquivoOriginalRepository;

    public ProcessoController(
            ProcessoService processoService,
            ProcessoDashboardService processoDashboardService,
            ArquivoOriginalRepository arquivoOriginalRepository) {

        this.processoService = processoService;
        this.processoDashboardService = processoDashboardService;
        this.arquivoOriginalRepository = arquivoOriginalRepository;
    }

    @GetMapping
    public ResponseEntity<Page<ProcessoDashboardResponse>> listarProcessos(
            @RequestParam(required = false) LocalDate dataInicio,
            @RequestParam(required = false) LocalDate dataFim,
            @RequestParam(required = false) Long conjuntoId,
            @RequestParam(required = false) Long etapaId,
            @RequestParam(required = false) Long situacaoId,
            @PageableDefault(
                    size = 10,
                    sort = "dataCriacao",
                    direction = Sort.Direction.DESC
            ) Pageable pageable) {

        Page<ProcessoDashboardResponse> processos =
                processoDashboardService.listarProcessos(
                        dataInicio,
                        dataFim,
                        conjuntoId,
                        etapaId,
                        situacaoId,
                        pageable
                );

        return ResponseEntity.ok(processos);
    }

    @GetMapping("/metricas")
    public ResponseEntity<ProcessoMetricasResponse> buscarMetricas() {

        ProcessoMetricasResponse metricas =
                processoDashboardService.buscarMetricas();

        return ResponseEntity.ok(metricas);
    }

    // --- TAREFA 5: Endpoint para listar auditores reais no modal de quarentena ---
    @GetMapping("/usuarios/auditores")
    public ResponseEntity<?> listarAuditores() {
        // Delega para o service buscar os analistas/auditores cadastrados reais
        return ResponseEntity.ok(processoService.listarAuditoresReais());
    }

    // --- TAREFA 5: Endpoint para associar o editor e alterar a situação para EM_ANDAMENTO ---
    @PutMapping("/{id}/editor")
    public ResponseEntity<Processo> alocarEditor(
            @PathVariable Long id,
            @RequestBody Map<String, Long> payload) {

        Long editorId = payload.get("editorId");
        Processo processoAtualizado = processoService.alocarEditorEAtualizarSituacao(id, editorId);

        return ResponseEntity.ok(processoAtualizado);
    }

    @GetMapping("/{id}/log-espacial")
    public ResponseEntity<List<LogEspacialResponse>> buscarLogEspacial(
            @PathVariable Long id) {

        List<LogEspacialResponse> logs =
                processoDashboardService.buscarLogEspacial(id);

        return ResponseEntity.ok(logs);
    }

    // --- TAREFA 6: Já estruturado para carregar logs, validações e dados detalhados ---
    @GetMapping("/{id}")
    public ResponseEntity<ProcessLogDetailsResponse> buscarDetalhesProcesso(
            @PathVariable Long id) {

        ProcessLogDetailsResponse response =
                processoDashboardService.buscarDetalhesProcessoFrontEnd(id);

        if (response != null) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/processar")
    public ResponseEntity<?> processarProcesso(
            @PathVariable Long id,
            @RequestParam String nomeArquivo,
            @RequestParam(
                    required = false,
                    defaultValue = "1"
            ) Long usuarioId) {

        try {

            Processo processo =
                    processoService.buscarPorId(id);

            ArquivoOriginal arquivo =
                    arquivoOriginalRepository
                            .findFirstByProcessoIdOrderByIdDesc(id)
                            .orElseThrow(() ->
                                    new IllegalArgumentException(
                                            "Nenhum arquivo encontrado para o processo: " + id));

            Path caminhoArquivo =
                    Paths.get(arquivo.getUrlArmazenamento());

            Processo processoAtualizado =
                    processoService.processarArquivo(
                            processo,
                            arquivo.getNomeOriginal(),
                            caminhoArquivo,
                            usuarioId
                    );

            return ResponseEntity.ok(processoAtualizado);

        } catch (IllegalArgumentException e) {

            return ResponseEntity
                    .badRequest()
                    .body(e.getMessage());

        } catch (Exception e) {

            return ResponseEntity
                    .internalServerError()
                    .body(
                            "Erro ao processar o processo: "
                                    + e.getMessage()
                    );
        }
    }
}