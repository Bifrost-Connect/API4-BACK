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
import com.bifrostconnect.api_geo.service.MetadadosCargaService;

import jakarta.validation.Valid;

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

    // Tarefa 3 e 5: Recebe, valida e persiste os metadados. 
    // O Status 400 (Bad Request) em caso de falta de dados é tratado automaticamente pelo @Valid + GlobalExceptionHandler
    @PostMapping("/metadados")
    public ResponseEntity<Processo> cadastrarMetadados(
            @Valid @RequestBody MetadadosCargaRequest request) {

        Processo processoSalvo = metadadosCargaService.salvarMetadados(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(processoSalvo);
    }

    // Tarefa 4 e 5: Recebe o arquivo, gera hash e bloqueia formatos não permitidos (Status 415)
    @PostMapping("/upload")
    public ResponseEntity<?> uploadArquivo(
            @RequestParam("processoId") Long processoId,
            @RequestParam("file") MultipartFile file) {

        // Validação básica para evitar erro de processamento interno (Status 400)
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("O arquivo enviado está vazio.");
        }

        // Tarefa 5: Validação do formato do arquivo. Gera Status 415 (Unsupported Media Type) através da exceção
        String nomeArquivo = file.getOriginalFilename();
        if (nomeArquivo == null || (!nomeArquivo.toLowerCase().endsWith(".zip") && !nomeArquivo.toLowerCase().endsWith(".geojson"))) {
            throw new UnsupportedFileFormatException("Formato de arquivo não permitido. Envie apenas arquivos .zip ou .geojson.");
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