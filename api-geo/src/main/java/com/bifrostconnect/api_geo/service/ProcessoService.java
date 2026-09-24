package com.bifrostconnect.api_geo.service;

import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessoService {

    private final ProcessoRepository processoRepository;
    private final ValidacaoService validacaoService;
    private final AuditoriaLogService auditoriaLogService;

    public ProcessoService(ProcessoRepository processoRepository,
                           ValidacaoService validacaoService,
                           AuditoriaLogService auditoriaLogService) {
        this.processoRepository = processoRepository;
        this.validacaoService = validacaoService;
        this.auditoriaLogService = auditoriaLogService;
    }

    // Método solicitado pelo ProcessoController
    public Processo buscarPorId(Long id) {
        return processoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Processo não encontrado para o ID: " + id));
    }

    @Transactional
    public Processo processarArquivo(Processo processo, String nomeArquivo, Long usuarioId) {
        auditoriaLogService.registrarLog(processo, "INFO", "Iniciando processamento do arquivo: " + nomeArquivo);
        auditoriaLogService.registrarAuditoria(usuarioId, "INICIO_PROCESSO", "Processo ID: " + processo.getId());

        boolean valido = validacaoService.executarValidacoes(processo, nomeArquivo);

        if (!valido) {
            auditoriaLogService.registrarLog(processo, "ERROR", "Falha nas validações do arquivo.");
            return processoRepository.save(processo);
        }

        auditoriaLogService.registrarLog(processo, "INFO", "Validação concluída com sucesso.");
        return processoRepository.save(processo);
    }
}