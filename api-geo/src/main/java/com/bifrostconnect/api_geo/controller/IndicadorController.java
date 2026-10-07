package com.bifrostconnect.api_geo.controller;

import com.bifrostconnect.api_geo.dto.LinhagemResponseDTO;
import com.bifrostconnect.api_geo.service.LinhagemService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/indicadores")
@RequiredArgsConstructor
public class IndicadorController {

    private final LinhagemService linhagemService;

    // Tarefa 6: Rota GET /indicadores/{id}/linhagem
    @GetMapping("/{id}/linhagem")
    public ResponseEntity<LinhagemResponseDTO> consultarLinhagem(@PathVariable Long id) {
        LinhagemResponseDTO linhagem = linhagemService.buscarLinhagem(id);
        return ResponseEntity.ok(linhagem);
    }
}