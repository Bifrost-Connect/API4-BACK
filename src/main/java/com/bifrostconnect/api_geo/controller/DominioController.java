package com.bifrostconnect.api_geo.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bifrostconnect.api_geo.service.DominioService;
import com.bifrostconnect.api_geo.security.Permissao;
import com.bifrostconnect.api_geo.security.RequirePermission;

@RestController
@RequestMapping("/dominios")
public class DominioController {

    private final DominioService dominioService;

    public DominioController(DominioService dominioService) {
        this.dominioService = dominioService;
    }

    @RequirePermission(Permissao.DOMINIO_CONSULTAR)
    @GetMapping("/filtros")
    public ResponseEntity<Map<String, Object>> listarFiltros() {
        return ResponseEntity.ok(dominioService.buscarOpcoesFiltros());
    }

    @RequirePermission(Permissao.DOMINIO_CONSULTAR)
    @GetMapping("/carga")
    public ResponseEntity<Map<String, Object>> listarOpcoesCarga() {
        return ResponseEntity.ok(dominioService.buscarOpcoesCarga());
    }
}
