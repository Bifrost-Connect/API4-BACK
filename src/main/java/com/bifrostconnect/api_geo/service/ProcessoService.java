package com.bifrostconnect.api_geo.service;

import java.nio.file.Path;

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

        /*
         * ETAPA 1 - INGESTÃO
         *
         * O processo já é criado pelo MetadadosCargaService
         * com INGESTAO + EM_ANDAMENTO.
         *
         * Aqui apenas registramos que a ingestão foi concluída
         * e avançamos para o tratamento.
         */
        processoEstadoService.atualizarEstado(
                processo,
                "TRATAMENTO",
                "EM_ANDAMENTO",
                "Ingestão concluída. Iniciando tratamento do arquivo.");

        /*
         * ETAPA 2 - TRATAMENTO
         *
         * O tratamento é concluído e o processo avança
         * para a validação.
         */
        processoEstadoService.atualizarEstado(
                processo,
                "VALIDACAO",
                "EM_ANDAMENTO",
                "Tratamento concluído. Iniciando validações.");

        /*
         * ETAPA 3 - VALIDAÇÃO
         */
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

        /*
         * ETAPA 4 - CÁLCULO ANALÍTICO
         */
        processoEstadoService.atualizarEstado(
                processo,
                "CALCULO_ANALITICO",
                "EM_ANDAMENTO",
                "Validação concluída. Iniciando cálculo analítico.");

        /*
         * Neste momento ainda não existe um motor analítico implementado.
         * Por isso a conclusão da etapa é registrada explicitamente.
         */
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
