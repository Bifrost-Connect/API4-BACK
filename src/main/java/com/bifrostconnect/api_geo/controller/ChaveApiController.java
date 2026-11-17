package com.bifrostconnect.api_geo.controller;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bifrostconnect.api_geo.dto.EmitirChaveApiRequest;
import com.bifrostconnect.api_geo.security.Permissao;
import com.bifrostconnect.api_geo.security.RequirePermission;
import com.bifrostconnect.api_geo.service.ChaveApiService;

import jakarta.validation.Valid;

// Tarefa 11: ciclo de vida das chaves de API (rotas finais: /api/integracoes/chaves...).
// Usa a permissão que já existe (USUARIO_GERENCIAR, só ADMINISTRADOR): hoje o perfil Integrador
// não existe na tabela perfil, então não foi preciso criar permissão nova nem mexer na MatrizPermissoes.
@RestController
@RequestMapping("/integracoes/chaves")
@RequirePermission(Permissao.USUARIO_GERENCIAR)
public class ChaveApiController {

    private final ChaveApiService service;

    public ChaveApiController(ChaveApiService service) {
        this.service = service;
    }

    // 201 Created: única resposta que traz a chave aberta (campo "chave")
    @PostMapping
    public ResponseEntity<Map<String, Object>> emitir(
            @Valid @RequestBody EmitirChaveApiRequest body,
            Authentication auth) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(service.emitir(body, auth.getName()));
    }

    @GetMapping
    public Map<String, Object> listar(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return service.listar(status, page, size);
    }

    @PatchMapping("/{id}/revogar")
    public Map<String, Object> revogar(
            @PathVariable Long id,
            Authentication auth) {

        return service.revogar(id, auth.getName());
    }
}