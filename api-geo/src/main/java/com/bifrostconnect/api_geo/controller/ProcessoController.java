package com.bifrostconnect.api_geo.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.service.ProcessoService;

@RestController
@RequestMapping("/processos")
public class ProcessoController {

    private final ProcessoService processoService;

    public ProcessoController(ProcessoService processoService) {
        this.processoService = processoService;
    }

    // Tarefa 2: Retorna os detalhes completos do processo pelo ID
    @GetMapping("/{id}")
    public ResponseEntity<Processo> buscarDetalhesProcesso(@PathVariable Long id) {
        try {
            Processo processo = processoService.buscarPorId(id);
            return ResponseEntity.ok(processo); // Retorna HTTP 200 com os dados
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build(); // Retorna HTTP 404 se não achar
        }
    }
}