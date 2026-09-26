package com.bifrostconnect.api_geo.controller;

import com.bifrostconnect.api_geo.dto.LogEspacialResponse;
import com.bifrostconnect.api_geo.dto.ProcessoDashboardResponse;
import com.bifrostconnect.api_geo.dto.ProcessoMetricasResponse;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.service.ProcessoDashboardService;
import com.bifrostconnect.api_geo.service.ProcessoService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/processos")
public class ProcessoController {

    private final ProcessoService processoService;
    private final ProcessoDashboardService processoDashboardService;

    public ProcessoController(
            ProcessoService processoService,
            ProcessoDashboardService processoDashboardService) {

        this.processoService = processoService;
        this.processoDashboardService = processoDashboardService;
    }

    /**
     * Tarefa 1:
     * Lista os processos com paginação e filtros.
     */
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

    /**
     * Tarefa 2:
     * Retorna as métricas agregadas para o Dashboard.
     */
    @GetMapping("/metricas")
    public ResponseEntity<ProcessoMetricasResponse> buscarMetricas() {

        ProcessoMetricasResponse metricas =
                processoDashboardService.buscarMetricas();

        return ResponseEntity.ok(metricas);
    }

    /**
     * Tarefa 3:
     * Retorna o log completo disponível para o processo.
     */
    @GetMapping("/{id}/log-espacial")
    public ResponseEntity<List<LogEspacialResponse>> buscarLogEspacial(
            @PathVariable Long id) {

        List<LogEspacialResponse> logs =
                processoDashboardService.buscarLogEspacial(id);

        return ResponseEntity.ok(logs);
    }

    /**
     * Retorna os detalhes completos do processo pelo ID mapeado exatamente
     * para o que o frontend espera (ProcessLogDetails).
     */
    @GetMapping("/{id}")
    public ResponseEntity<com.bifrostconnect.api_geo.dto.ProcessLogDetailsResponse> buscarDetalhesProcesso(
            @PathVariable Long id) {

        com.bifrostconnect.api_geo.dto.ProcessLogDetailsResponse response = 
                processoDashboardService.buscarDetalhesProcessoFrontEnd(id);

        if (response != null) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Endpoints já existentes:
     * Executa o processamento/validação do processo.
     *
     * Mantido sem alteração de comportamento.
     */
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

            Processo processoAtualizado =
                    processoService.processarArquivo(
                            processo,
                            nomeArquivo,
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