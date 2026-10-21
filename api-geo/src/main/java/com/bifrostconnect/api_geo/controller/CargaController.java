package com.bifrostconnect.api_geo.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.bifrostconnect.api_geo.dto.CancelamentoRequest;
import com.bifrostconnect.api_geo.dto.LinhagemResponseDTO;
import com.bifrostconnect.api_geo.dto.MetadadosCargaRequest;
import com.bifrostconnect.api_geo.entity.ArquivoOriginal;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.exception.UnsupportedFileFormatException;
import com.bifrostconnect.api_geo.service.ArquivoUploadService;
import com.bifrostconnect.api_geo.service.AuditoriaLogService;
import com.bifrostconnect.api_geo.service.CancelamentoCargaService;
import com.bifrostconnect.api_geo.service.EncaminhamentoQuarentenaService;
import com.bifrostconnect.api_geo.service.LinhagemService;
import com.bifrostconnect.api_geo.service.MetadadosCargaService;
import com.bifrostconnect.api_geo.service.ReprocessamentoCargaService;
import com.bifrostconnect.api_geo.service.ExportacaoRejeicoesService;

import jakarta.validation.Valid;

@RestController
@RequestMapping({"/carga", "/api/carga"})
public class CargaController {

    private final MetadadosCargaService metadadosCargaService;
    private final ArquivoUploadService arquivoUploadService;
    private final AuditoriaLogService auditoriaLogService;
    private final LinhagemService linhagemService;
    private final ExportacaoRejeicoesService exportacaoRejeicoesService; 
    private final EncaminhamentoQuarentenaService encaminhamentoQuarentenaService;    
    private final ReprocessamentoCargaService reprocessamentoCargaService;
    private final CancelamentoCargaService cancelamentoCargaService;
    
    public CargaController(
            MetadadosCargaService metadadosCargaService,
            ArquivoUploadService arquivoUploadService,
            AuditoriaLogService auditoriaLogService,
            LinhagemService linhagemService,
            ExportacaoRejeicoesService exportacaoRejeicoesService,
            EncaminhamentoQuarentenaService encaminhamentoQuarentenaService,
            ReprocessamentoCargaService reprocessamentoCargaService,
            CancelamentoCargaService cancelamentoCargaService) {

        this.metadadosCargaService = metadadosCargaService;
        this.arquivoUploadService = arquivoUploadService;
        this.auditoriaLogService = auditoriaLogService;
        this.linhagemService = linhagemService;
        this.exportacaoRejeicoesService = exportacaoRejeicoesService; 
        this.encaminhamentoQuarentenaService = encaminhamentoQuarentenaService;
        this.reprocessamentoCargaService = reprocessamentoCargaService;
        this.cancelamentoCargaService = cancelamentoCargaService;
    }

    // Tarefa 3 e 5: Recebe, valida e persiste os metadados.
    @PreAuthorize("hasAnyRole('OPERADOR', 'ADMINISTRADOR')")
    @PostMapping("/metadados")
    public ResponseEntity<Processo> cadastrarMetadados(
            @Valid @RequestBody MetadadosCargaRequest request) {

        Processo processoSalvo = metadadosCargaService.salvarMetadados(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(processoSalvo);
    }

    // Tarefa 4, 5 e 7: Recebe o arquivo, realiza o upload e registra na auditoria.
    @PreAuthorize("hasAnyRole('OPERADOR', 'ADMINISTRADOR')")
    @PostMapping("/upload")
    public ResponseEntity<?> uploadArquivo(
            @RequestParam("processoId") Long processoId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(
                    value = "usuarioId",
                    required = false,
                    defaultValue = "1") Long usuarioId) {

        if (file.isEmpty()) {
            return ResponseEntity
                    .badRequest()
                    .body("O arquivo enviado está vazio.");
        }

        String nomeArquivo = file.getOriginalFilename();

        if (nomeArquivo == null
                || (!nomeArquivo.toLowerCase().endsWith(".zip")
                && !nomeArquivo.toLowerCase().endsWith(".geojson"))) {

            throw new UnsupportedFileFormatException(
                    "Formato de arquivo não permitido. "
                    + "Envie apenas arquivos .zip ou .geojson.");
        }

        try {

            ArquivoOriginal arquivoSalvo =
                    arquivoUploadService.processarUpload(
                            processoId,
                            file);

            // Tarefa 7: Registro do evento de auditoria após upload bem-sucedido.
            auditoriaLogService.registrarAuditoria(
                    usuarioId,
                    "UPLOAD_ARQUIVO",
                    "Upload realizado com sucesso para o Processo ID: "
                            + processoId
                            + ", Arquivo: "
                            + nomeArquivo
            );

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(arquivoSalvo);

        } catch (IllegalArgumentException e) {

            return ResponseEntity
                    .badRequest()
                    .body(e.getMessage());

        } catch (org.springframework.dao.DataIntegrityViolationException e) {

            return ResponseEntity
                    .badRequest()
                    .body(
                            "Este arquivo já foi processado anteriormente "
                            + "(Arquivo duplicado detectado pelo Hash SHA-256)."
                    );

        } catch (Exception e) {

            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(
                            "Erro ao processar o upload do arquivo: "
                            + e.getMessage()
                    );
        }
    }

    // Download do arquivo original.
    @GetMapping("/download/{processoId}")
    public ResponseEntity<Resource> baixarArquivoOriginal(
            @PathVariable Long processoId) {

        try {

            ArquivoOriginal arquivo =
                    arquivoUploadService
                            .buscarArquivoOriginalPorProcesso(processoId);

            Resource resource =
                    arquivoUploadService
                            .abrirArquivoParaDownload(arquivo);

            long tamanho =
                    arquivoUploadService
                            .obterTamanhoArquivo(arquivo);

            MediaType mediaType =
                    MediaType.APPLICATION_OCTET_STREAM;

            if (arquivo.getTipoMime() != null
                    && !arquivo.getTipoMime().isBlank()) {

                try {
                    mediaType =
                            MediaType.parseMediaType(
                                    arquivo.getTipoMime());
                } catch (Exception ignored) {
                }
            }

            ContentDisposition contentDisposition =
                    ContentDisposition
                            .attachment()
                            .filename(
                                    arquivo.getNomeOriginal(),
                                    StandardCharsets.UTF_8)
                            .build();

            return ResponseEntity
                    .ok()
                    .contentType(mediaType)
                    .contentLength(tamanho)
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            contentDisposition.toString())
                    .body(resource);

        } catch (IllegalArgumentException | IOException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ==========================================
    // Tarefa 15: API de Rastreabilidade e Linhagem
    // ==========================================
    @GetMapping("/{id}/linhagem")
    public ResponseEntity<LinhagemResponseDTO> obterLinhagem(@PathVariable Long id) {
        LinhagemResponseDTO linhagem = linhagemService.buscarLinhagem(id);
        return ResponseEntity.ok(linhagem);
    }
    
    // ==========================================
    // Tarefa 16: API de Exportação de Rejeições (CSV)
    // ==========================================
    @GetMapping("/{id}/rejeicoes/exportar")
    public ResponseEntity<byte[]> exportarRejeicoesEmCsv(@PathVariable Long id) {
        try {
            byte[] csvData = exportacaoRejeicoesService.exportarRejeicoesCsv(id);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
            
            ContentDisposition contentDisposition = ContentDisposition
                    .attachment()
                    .filename("rejeicoes_processo_" + id + ".csv", StandardCharsets.UTF_8)
                    .build();
            headers.setContentDisposition(contentDisposition);

            return new ResponseEntity<>(csvData, headers, HttpStatus.OK);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ==========================================
    // Tarefa 17: API de Encaminhamento Auditor ➔ Operador
    // ==========================================
    @PreAuthorize("hasAnyRole('AUDITOR', 'ADMINISTRADOR')")
    @PostMapping("/{id}/quarentena/notificar-operador")
    public ResponseEntity<Void> notificarOperador(
            @PathVariable Long id,
            @RequestParam(value = "usuarioId", required = false, defaultValue = "1") Long usuarioId) {
        
        encaminhamentoQuarentenaService.notificarOperador(id, usuarioId);
        return ResponseEntity.noContent().build();
    }

    // ==========================================
    // Tarefa 18: API de Reprocessamento de Carga
    // ==========================================
    @PreAuthorize("hasAnyRole('OPERADOR', 'ADMINISTRADOR')")
    @PostMapping({ "/{id}/reprocessar", "/cargas/{id}/reprocessar" })
    public ResponseEntity<?> reprocessarCarga(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "usuarioId", required = false, defaultValue = "1") Long usuarioId) {

        if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("O arquivo enviado para reprocessamento está vazio.");
        }

        try {
                ArquivoOriginal arquivoNovo = reprocessamentoCargaService.reprocessarCarga(id, file, usuarioId);
                return ResponseEntity.status(HttpStatus.CREATED).body(arquivoNovo);

        } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(e.getMessage());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
                return ResponseEntity.badRequest().body("Este arquivo corrigido já foi processado anteriormente (Hash SHA-256 duplicado).");
        } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Erro ao reprocessar a carga: " + e.getMessage());
        }
    }

    // ==========================================
    // Tarefa 19: API de Cancelamento/Invalidação
    // ==========================================
    @PreAuthorize("hasAnyRole('OPERADOR', 'ADMINISTRADOR')")
    @PatchMapping({ "/{id}/cancelar", "/cargas/{id}/cancelar" })
    public ResponseEntity<Void> cancelarCarga(
            @PathVariable Long id,
            @Valid @RequestBody CancelamentoRequest request,
            @RequestParam(value = "usuarioId", required = false, defaultValue = "1") Long usuarioId) {

        cancelamentoCargaService.cancelarCarga(id, request.getMotivo(), usuarioId);
        return ResponseEntity.noContent().build();
    }
}