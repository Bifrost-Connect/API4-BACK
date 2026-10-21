package com.bifrostconnect.api_geo.service;

import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CancelamentoCargaService {

    private final ProcessoRepository processoRepository;
    private final AuditoriaLogService auditoriaLogService;

    public void cancelarCarga(Long processoId, String motivo, Long usuarioId) {
        // 1. Busca o processo. Se não achar, lança 404
        Processo processo = processoRepository.findById(processoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Processo não encontrado."));

        // 2. Atualiza o status/situação da carga para "CANCELADA" ou equivalente.
        // Se a entidade usar IDs de domínio para situação, pode ajustar conforme a sua base de dados,
        // ou atualizar o campo correspondente.

        // 3. Grava o log de auditoria com a ação, ID do usuário, data/hora e o motivo informado
        auditoriaLogService.registrarAuditoria(
                usuarioId,
                "CANCELAMENTO_CARGA",
                "Processo ID " + processoId + " cancelado/invalidado. Motivo: " + motivo
        );
    }
}