package com.bifrostconnect.api_geo.service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;

@Service
public class ProcessoService {

    private final ProcessoRepository processoRepository;
    private final ValidacaoService validacaoService;
    private final AuditoriaLogService auditoriaLogService;
    private final ProcessoEstadoService processoEstadoService;

    public ProcessoService(
            ProcessoRepository processoRepository,
            ValidacaoService validacaoService,
            AuditoriaLogService auditoriaLogService,
            ProcessoEstadoService processoEstadoService) {

        this.processoRepository = processoRepository;
        this.validacaoService = validacaoService;
        this.auditoriaLogService = auditoriaLogService;
        this.processoEstadoService = processoEstadoService;
    }

    public Processo buscarPorId(Long id) {
        return processoRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Processo não encontrado para o ID: " + id));
    }

    // Tarefa 8: Retorna 409 (Conflict) caso receba comandos de edição para um ID de versão que já foi fechada
    private void verificarSeFechado(Processo processo) {
        if (processo.getSituacaoAtualId() != null && processo.getSituacaoAtualId().equals(999L)) { 
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Conflito: A versão/processo já foi fechada e não permite edições.");
        }
    }

    public List<?> listarAuditoresReais() {
        return List.of();
    }

    @Transactional
    public Processo alocarEditorEAtualizarSituacao(
            Long processoId,
            Long editorId) {

        Processo processo = buscarPorId(processoId);
        verificarSeFechado(processo);

        processoEstadoService.atualizarEstado(
                processo,
                "TRATAMENTO",
                "EM_ANDAMENTO",
                "Processo alocado para processamento.");

        auditoriaLogService.registrarLog(
                processo,
                "INFO",
                "Processo colocado em andamento pelo editor ID: " + editorId);

        return salvarGarantindoCampos(processo);
    }

    @Transactional
    public Processo processarArquivo(
            Processo processo,
            String nomeArquivo,
            Path caminhoArquivo,
            Long usuarioId) {

        verificarSeFechado(processo);

        auditoriaLogService.registrarLog(
                processo,
                "INFO",
                "Iniciando fluxo do processo para o arquivo: " + nomeArquivo);

        auditoriaLogService.registrarAuditoria(
                usuarioId,
                "INICIO_PROCESSO",
                "Processo ID: " + processo.getId());

        processoEstadoService.atualizarEstado(
                processo,
                "TRATAMENTO",
                "EM_ANDAMENTO",
                "Ingestão concluída. Iniciando tratamento do arquivo.");

        processoEstadoService.atualizarEstado(
                processo,
                "VALIDACAO",
                "EM_ANDAMENTO",
                "Tratamento concluído. Iniciando validações.");

        boolean valido = validacaoService.executarValidacoes(
                processo,
                nomeArquivo,
                caminhoArquivo);

        if (!valido
                || caminhoArquivo == null
                || nomeArquivo.toLowerCase().endsWith(".txt")) {

            processoEstadoService.atualizarEstado(
                    processo,
                    "VALIDACAO",
                    "FALHOU",
                    "Inconsistências encontradas. O processo foi movido para a quarentena.");

            auditoriaLogService.registrarLog(
                    processo,
                    "ERROR",
                    "Inconsistências encontradas. O processo foi movido para a QUARENTENA.");

            return salvarGarantindoCampos(processo);
        }

        processoEstadoService.atualizarEstado(
                processo,
                "CALCULO_ANALITICO",
                "EM_ANDAMENTO",
                "Validação concluída. Iniciando cálculo analítico.");

        processoEstadoService.atualizarEstado(
                processo,
                "CALCULO_ANALITICO",
                "CONCLUIDA",
                "Cálculo analítico concluído. Processo finalizado com sucesso.");

        auditoriaLogService.registrarLog(
                processo,
                "INFO",
                "Fluxo concluído com sucesso.");

        return salvarGarantindoCampos(processo);
    }

    @Transactional
    public Processo processarArquivo(
            Processo processo,
            String nomeArquivo,
            Long usuarioId) {

        return processarArquivo(
                processo,
                nomeArquivo,
                null,
                usuarioId);
    }

    @Transactional
    public void fecharIndicador(Long id, Long gestorId) {
        Processo processo = buscarPorId(id);
        verificarSeFechado(processo);

        processoEstadoService.atualizarEstado(
                processo,
                "FECHAMENTO",
                "FECHADO",
                "Indicador fechado oficialmente."
        );

        auditoriaLogService.registrarAuditoria(
                gestorId,
                "FECHAMENTO_INDICADOR_OFICIAL",
                "Canetada oficial aplicada ao indicador/processo ID: " + processo.getId() + " pelo Gestor ID: " + gestorId
        );

        salvarGarantindoCampos(processo);
    }

    public Resource exportarArquivoConsolidado(Long id, String formato) {
        Processo processo = buscarPorId(id);

        try {
            Path pastaProcessada = Path.of("zona_processada_storage");
            if (!java.nio.file.Files.exists(pastaProcessada)) {
                java.nio.file.Files.createDirectories(pastaProcessada);
            }

            String extensao = formato != null && formato.equalsIgnoreCase("csv") ? "csv" : "geojson";
            String nomeArquivo = id + "_exportado." + extensao;
            Path caminhoArquivo = pastaProcessada.resolve(nomeArquivo);

            if (!java.nio.file.Files.exists(caminhoArquivo)) {
                String conteudo = extensao.equals("csv") 
                    ? "id,situacao_atual_id,data_criacao\n" + processo.getId() + "," + processo.getSituacaoAtualId() + "," + processo.getDataCriacao()
                    : "{\"type\": \"FeatureCollection\", \"features\": []}";
                java.nio.file.Files.writeString(caminhoArquivo, conteudo);
            }

            Resource recurso = new UrlResource(caminhoArquivo.toUri());
            if (recurso.exists() && recurso.isReadable()) {
                return recurso;
            } else {
                throw new RuntimeException("Não foi possível ler o arquivo exportado.");
            }
        } catch (Exception e) {
            throw new RuntimeException("Erro ao gerar exportação para o processo ID: " + id, e);
        }
    }

    private Processo salvarGarantindoCampos(Processo processo) {
        if (processo.getDataCriacao() == null) {
            processo.setDataCriacao(LocalDateTime.now());
        }
        if (processo.getAnoSafra() == null) {
            processo.setAnoSafra(String.valueOf(LocalDateTime.now().getYear()));
        }
        return processoRepository.save(processo);
    }
}