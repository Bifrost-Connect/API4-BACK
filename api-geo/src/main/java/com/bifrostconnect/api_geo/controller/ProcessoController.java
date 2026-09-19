package com.bifrostconnect.api_geo.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
            return ResponseEntity.ok(processo);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // Tarefas 6 e 7: Executa a validação das regras e os registros de logs/auditoria do processo
    @PostMapping("/{id}/processar")
    public ResponseEntity<?> processarProcesso(
            @PathVariable Long id,
            @RequestParam String nomeArquivo,
            @RequestParam(required = false, defaultValue = "1") Long usuarioId) {
        try {
            Processo processo = processoService.buscarPorId(id);
            Processo processoAtualizado = processoService.processarArquivo(processo, nomeArquivo, usuarioId);
            return ResponseEntity.ok(processoAtualizado);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Erro ao processar o processo: " + e.getMessage());
        }
    }
}