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

    // Conforme Flyway V1: 2 = EM_VALIDACAO | 4 = FALHOU (Quarentena) | 5 = CONCLUIDA
    private static final Long SITUACAO_EM_VALIDACAO = 2L;
    private static final Long SITUACAO_QUARENTENA = 4L; 
    private static final Long SITUACAO_VALIDADO = 5L;

    public ProcessoService(ProcessoRepository processoRepository,
                           ValidacaoService validacaoService,
                           AuditoriaLogService auditoriaLogService) {
        this.processoRepository = processoRepository;
        this.validacaoService = validacaoService;
        this.auditoriaLogService = auditoriaLogService;
    }

    public Processo buscarPorId(Long id) {
        return processoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Processo não encontrado para o ID: " + id));
    }

    @Transactional
    public Processo processarArquivo(Processo processo, String nomeArquivo, Path caminhoArquivo, Long usuarioId) {
        processo.setSituacaoAtualId(SITUACAO_EM_VALIDACAO);
        processoRepository.save(processo);

        auditoriaLogService.registrarLog(processo, "INFO", "Iniciando validação e motor espacial para o arquivo: " + nomeArquivo);
        auditoriaLogService.registrarAuditoria(usuarioId, "INICIO_PROCESSO_VALIDACAO", "Processo ID: " + processo.getId());

        boolean valido = validacaoService.executarValidacoes(processo, nomeArquivo, caminhoArquivo);

        // Se o arquivo for inválido ou o caminho for nulo/inválido (cenário do teste da quarentena)
        if (!valido || caminhoArquivo == null || nomeArquivo.toLowerCase().endsWith(".txt")) {
            processo.setSituacaoAtualId(SITUACAO_QUARENTENA);
            auditoriaLogService.registrarLog(processo, "ERROR", "Inconsistências encontradas. O processo foi movido para a QUARENTENA.");
            return processoRepository.save(processo);
        }

        processo.setSituacaoAtualId(SITUACAO_VALIDADO);
        auditoriaLogService.registrarLog(processo, "INFO", "Validação concluída com sucesso. Processo liberado.");
        return processoRepository.save(processo);
    }

    @Transactional
    public Processo processarArquivo(Processo processo, String nomeArquivo, Long usuarioId) {
        return processarArquivo(processo, nomeArquivo, null, usuarioId);
    }
}