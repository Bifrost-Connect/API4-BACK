package com.bifrostconnect.api_geo.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.bifrostconnect.api_geo.dto.MetadadosCargaRequest;
import com.bifrostconnect.api_geo.entity.ArquivoOriginal;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.exception.UnsupportedFileFormatException;
import com.bifrostconnect.api_geo.service.ArquivoUploadService;
import com.bifrostconnect.api_geo.service.AuditoriaLogService;
import com.bifrostconnect.api_geo.service.MetadadosCargaService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/carga")
public class CargaController {

    private final MetadadosCargaService metadadosCargaService;
    private final ArquivoUploadService arquivoUploadService;
    private final AuditoriaLogService auditoriaLogService;

    // Construtor atualizado com as dependências necessárias
    public CargaController(MetadadosCargaService metadadosCargaService,
                           ArquivoUploadService arquivoUploadService,
                           AuditoriaLogService auditoriaLogService) {
        this.metadadosCargaService = metadadosCargaService;
        this.arquivoUploadService = arquivoUploadService;
        this.auditoriaLogService = auditoriaLogService;
    }

    // Tarefa 3 e 5: Recebe, valida e persiste os metadados.
    @PostMapping("/metadados")
    public ResponseEntity<Processo> cadastrarMetadados(
            @Valid @RequestBody MetadadosCargaRequest request) {

        Processo processoSalvo = metadadosCargaService.salvarMetadados(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(processoSalvo);
    }

    // Tarefa 4, 5 e 7: Recebe o arquivo, realiza o upload e registra na auditoria
    @PostMapping("/upload")
    public ResponseEntity<?> uploadArquivo(
            @RequestParam("processoId") Long processoId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "usuarioId", required = false, defaultValue = "1") Long usuarioId) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("O arquivo enviado está vazio.");
        }

        String nomeArquivo = file.getOriginalFilename();
        if (nomeArquivo == null || (!nomeArquivo.toLowerCase().endsWith(".zip") && !nomeArquivo.toLowerCase().endsWith(".geojson"))) {
            throw new UnsupportedFileFormatException("Formato de arquivo não permitido. Envie apenas arquivos .zip ou .geojson.");
        }

        try {
            ArquivoOriginal arquivoSalvo = arquivoUploadService.processarUpload(processoId, file);

            // Tarefa 7: Registro do evento de auditoria após upload bem-sucedido
            auditoriaLogService.registrarAuditoria(
                    usuarioId,
                    "UPLOAD_ARQUIVO",
                    "Upload realizado com sucesso para o Processo ID: " + processoId + ", Arquivo: " + nomeArquivo
            );

            return ResponseEntity.status(HttpStatus.CREATED).body(arquivoSalvo);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return ResponseEntity.badRequest().body("Este arquivo já foi processado anteriormente (Arquivo duplicado detectado pelo Hash SHA-256).");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Erro ao processar o upload do arquivo: " + e.getMessage());
        }
    }
}