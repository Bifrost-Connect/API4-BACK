
package com.bifrostconnect.api_geo.controller;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import com.bifrostconnect.api_geo.security.Permissao;
import com.bifrostconnect.api_geo.security.RequirePermission;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import com.bifrostconnect.api_geo.service.AdminUsuarioService;

@RestController
@RequestMapping("/admin/usuarios")
@RequirePermission(Permissao.USUARIO_GERENCIAR)
public class AdminUsuarioController {

    private final AdminUsuarioService service;

    public AdminUsuarioController(AdminUsuarioService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> criar(
            @RequestBody Map<String, Object> body,
            Authentication auth,
            HttpServletRequest request) {

        Object email = body.get("email");

        if (email == null ||
                !email.toString().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "E-mail inválido.");
        }

        return service.criar(
                body, auth.getName(), request.getRemoteAddr());
    }

    @GetMapping
    public Map<String, Object> listar(
            @RequestParam(required = false) String nome,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) Long perfilId,
            @RequestParam(required = false) Boolean ativo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return service.listar(nome, email, perfilId, ativo, page, size);
    }

    @PutMapping("/{id}/permissoes")
    public Map<String, Object> alterarPermissoes(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            Authentication auth,
            HttpServletRequest request) {

        return service.alterarPermissao(
                id, body, auth.getName(), request.getRemoteAddr());
    }

    @PatchMapping("/{id}/status")
    public Map<String, Object> alterarStatus(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            Authentication auth,
            HttpServletRequest request) {

        if (!(body.get("ativo") instanceof Boolean ativo)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Informe o campo booleano ativo.");
        }

        return service.alterarStatus(
                id, ativo, auth.getName(), request.getRemoteAddr());
    }

    @PostMapping("/{id}/redefinir-senha")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void redefinirSenha(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            Authentication auth,
            HttpServletRequest request) {

        Object senha = body.get("novaSenha");

        if (senha == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Informe novaSenha.");
        }

        service.redefinirSenha(
                id, senha.toString(), auth.getName(),
                request.getRemoteAddr());
    }
}
