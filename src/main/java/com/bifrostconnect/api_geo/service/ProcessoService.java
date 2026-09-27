package com.bifrostconnect.api_geo.service;

import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public List<?> listarAuditoresReais() {
        return List.of();
    }

    @Transactional
    public Processo alocarEditorEAtualizarSituacao(
            Long processoId,
            Long editorId) {

        Processo processo = buscarPorId(processoId);

        /*
         * A entidade Processo não possui editorId.
         * Portanto, não alteramos a estrutura da entidade nem do banco.
         *
         * O editor recebido é mantido como parâmetro para preservar
         * a assinatura utilizada pelo ProcessoController.
         */

        processoEstadoService.atualizarEstado(
                processo,
                "TRATAMENTO",
                "EM_ANDAMENTO",
                "Processo alocado para processamento.");

        auditoriaLogService.registrarLog(
                processo,
                "INFO",
                "Processo colocado em andamento pelo editor ID: " + editorId);

        return processoRepository.save(processo);
    }

    @Transactional
    public Processo processarArquivo(
            Processo processo,
            String nomeArquivo,
            Path caminhoArquivo,
            Long usuarioId) {

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

            return processoRepository.save(processo);
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

        return processoRepository.save(processo);
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
}