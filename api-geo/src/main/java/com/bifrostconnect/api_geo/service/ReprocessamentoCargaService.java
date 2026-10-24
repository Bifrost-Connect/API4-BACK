package com.bifrostconnect.api_geo.service;

import com.bifrostconnect.api_geo.entity.ArquivoOriginal;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ReprocessamentoCargaService {

    private final ProcessoRepository processoRepository;
    private final ArquivoUploadService arquivoUploadService;
    private final AuditoriaLogService auditoriaLogService;

    public ArquivoOriginal reprocessarCarga(Long processoId, MultipartFile file, Long usuarioId) {
        // 1. Verifica se o processo existe
        Processo processo = processoRepository.findById(processoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Processo não encontrado."));

        try {
            // 2. Processa o upload do novo arquivo corrigido com tratamento de exceção
            ArquivoOriginal novoArquivo = arquivoUploadService.processarUpload(processo.getId(), file, usuarioId);

            // 3. Registra na auditoria
            auditoriaLogService.registrarAuditoria(
                    usuarioId,
                    "REPROCESSAMENTO_CARGA",
                    "Operador enviou novo arquivo corrigido para o Processo ID: " + processoId + 
                    ", Novo Arquivo: " + file.getOriginalFilename() + 
                    ", Novo Hash SHA-256 gerado."
            );

            return novoArquivo;

        } catch (IllegalArgumentException | org.springframework.dao.DataIntegrityViolationException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Erro ao processar o reupload do arquivo: " + e.getMessage());
        }
    }
}