
package com.bifrostconnect.api_geo.controller;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.bifrostconnect.api_geo.service.AdminGovernancaService;

@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class AdminGovernancaController {

    private final AdminGovernancaService service;

    public AdminGovernancaController(AdminGovernancaService service) {
        this.service = service;
    }

    @GetMapping("/perfis-permissoes")
    public List<Map<String, Object>> matrizPermissoes() {
        return service.matriz();
    }

    @GetMapping("/logs-seguranca")
    public Map<String, Object> logsSeguranca(
            @RequestParam(required = false) Long usuarioId,
            @RequestParam(required = false) String acao,
            @RequestParam(required = false) String de,
            @RequestParam(required = false) String ate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return service.logs(usuarioId, acao, de, ate, page, size);
    }
}
