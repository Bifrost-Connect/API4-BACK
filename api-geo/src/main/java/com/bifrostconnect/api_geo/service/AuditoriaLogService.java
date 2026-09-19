package com.bifrostconnect.api_geo.service;

import com.bifrostconnect.api_geo.entity.Auditoria;
import com.bifrostconnect.api_geo.entity.LogProcessamento;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.AuditoriaRepository;
import com.bifrostconnect.api_geo.repository.LogProcessamentoRepository;
import org.springframework.stereotype.Service;

@Service
public class AuditoriaLogService {

    private final LogProcessamentoRepository logRepository;
    private final AuditoriaRepository auditoriaRepository;

    public AuditoriaLogService(LogProcessamentoRepository logRepository, AuditoriaRepository auditoriaRepository) {
        this.logRepository = logRepository;
        this.auditoriaRepository = auditoriaRepository;
    }

    public void registrarLog(Processo processo, String nivel, String mensagem) {
        LogProcessamento log = new LogProcessamento();
        log.setProcesso(processo);
        log.setNivel(nivel);
        log.setMensagem(mensagem);
        logRepository.save(log);
    }

    public void registrarAuditoria(Long usuarioId, String acao, String detalhes) {
        Auditoria auditoria = new Auditoria();
        auditoria.setUsuarioId(usuarioId);
        auditoria.setAcao(acao);
        auditoria.setDetalhes(detalhes);
        auditoriaRepository.save(auditoria);
    }
}