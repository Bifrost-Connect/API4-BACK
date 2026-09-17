package com.bifrostconnect.api_geo.controller;

import com.bifrostconnect.api_geo.dto.MetadadosCargaRequest;
import com.bifrostconnect.api_geo.entity.ArquivoOriginal;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.service.ArquivoUploadService;
import com.bifrostconnect.api_geo.service.MetadadosCargaService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/carga")
public class CargaController {

    private final MetadadosCargaService metadadosCargaService;
    private final ArquivoUploadService arquivoUploadService;

    // Construtor com as duas dependências necessárias
    public CargaController(MetadadosCargaService metadadosCargaService,
                           ArquivoUploadService arquivoUploadService) {
        this.metadadosCargaService = metadadosCargaService;
        this.arquivoUploadService = arquivoUploadService;
    }

    // Tarefa 3: Recebe, valida e persiste os metadados na tabela processo
    @PostMapping("/metadados")
    public ResponseEntity<Processo> cadastrarMetadados(
            @Valid @RequestBody MetadadosCargaRequest request) {

        Processo processoSalvo = metadadosCargaService.salvarMetadados(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(processoSalvo);
    }

    // Tarefa 4: Recebe o arquivo, calcula o Hash SHA-256 e persiste na tabela arquivo_original
    @PostMapping("/upload")
    public ResponseEntity<?> uploadArquivo(
            @RequestParam("processoId") Long processoId,
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("O arquivo enviado está vazio.");
        }

        try {
            ArquivoOriginal arquivoSalvo = arquivoUploadService.processarUpload(processoId, file);
            return ResponseEntity.status(HttpStatus.CREATED).body(arquivoSalvo);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Erro ao processar o upload do arquivo: " + e.getMessage());
        }
    }
}