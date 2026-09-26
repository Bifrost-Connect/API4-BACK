package com.bifrostconnect.api_geo.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.bifrostconnect.api_geo.entity.Auditoria;
import com.bifrostconnect.api_geo.entity.LogProcessamento;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.AuditoriaRepository;
import com.bifrostconnect.api_geo.repository.LogProcessamentoRepository;

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
        
        if (processo != null) {
            log.setProcessoId(processo.getId());
            
            // Atribui o etapaAtualId ou fallback para 1L para evitar erro NOT NULL na FK
            Long etapaId = (processo.getEtapaAtualId() != null) ? processo.getEtapaAtualId() : 1L;
            log.setProcessoEtapaId(etapaId);
        }

        log.setNivel(nivel);
        log.setMensagem(mensagem);
        log.setDataHora(LocalDateTime.now());

        logRepository.save(log);
    }

    public void registrarAuditoria(Long usuarioId, String acao, String detalhes) {
        Auditoria auditoria = new Auditoria();
        auditoria.setUsuarioId(usuarioId);
        auditoria.setAcao(acao);
        auditoria.setDetalhes(detalhes);
        auditoria.setDataHora(LocalDateTime.now());

        auditoriaRepository.save(auditoria);
    }
}