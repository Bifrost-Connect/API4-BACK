package com.bifrostconnect.api_geo.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.bifrostconnect.api_geo.entity.ArquivoOriginal;
import com.bifrostconnect.api_geo.service.ArquivoService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/arquivos")
@RequiredArgsConstructor
public class ArquivoController {

    private final ArquivoService arquivoService;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadArquivoZonaBruta(
            @RequestParam("file") MultipartFile file,
            @RequestParam("processoId") Long processoId,
            @RequestParam("usuarioId") Long usuarioId) {

        try {
            // Chama a regra de negócio
            ArquivoOriginal arquivoSalvo = arquivoService.processarEArmazenarArquivo(file, processoId, usuarioId);

            // Monta o JSON de resposta (Comprovante)
            Map<String, Object> response = new HashMap<>();
            response.put("sucesso", true);
            response.put("mensagem", "Carga realizada na Zona Bruta com sucesso!");
            response.put("hash_sha256", arquivoSalvo.getHashSha256());
            response.put("id_arquivo", arquivoSalvo.getId());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (Exception e) {
            Map<String, Object> erroResponse = new HashMap<>();
            erroResponse.put("sucesso", false);
            erroResponse.put("erro", e.getMessage());
            
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(erroResponse);
        }
    }
}