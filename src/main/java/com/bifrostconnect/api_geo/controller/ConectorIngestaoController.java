package com.bifrostconnect.api_geo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.bifrostconnect.api_geo.dto.AirflowIngestaoStatusResponse;
import com.bifrostconnect.api_geo.dto.ConectorFonteDadosRequest;
import com.bifrostconnect.api_geo.dto.ConectorFonteDadosResponse;
import com.bifrostconnect.api_geo.dto.ExecutarConectorRequest;
import com.bifrostconnect.api_geo.security.Permissao;
import com.bifrostconnect.api_geo.security.RequirePermission;
import com.bifrostconnect.api_geo.security.RotaPublica;
import com.bifrostconnect.api_geo.security.UsuarioSessaoService;
import com.bifrostconnect.api_geo.security.UsuarioSessaoService.EstadoUsuario;
import com.bifrostconnect.api_geo.service.ConectorIngestaoService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/conectores")
public class ConectorIngestaoController {

    private final ConectorIngestaoService service;
    private final UsuarioSessaoService usuarioSessaoService;
    private final String airflowInternalToken;

    public ConectorIngestaoController(
            ConectorIngestaoService service,
            UsuarioSessaoService usuarioSessaoService,
            @Value("${airflow.internal-token:geodatahub-airflow-internal-token}") String airflowInternalToken) {
        this.service = service;
        this.usuarioSessaoService = usuarioSessaoService;
        this.airflowInternalToken = airflowInternalToken;
    }

    @RequirePermission(Permissao.CARGA_CONSULTAR)
    @GetMapping("/templates")
    public ResponseEntity<List<Map<String, Object>>> listarTemplates() {
        return ResponseEntity.ok(service.listarTemplatesPublicos());
    }

    @RequirePermission(Permissao.CARGA_CONSULTAR)
    @GetMapping
    public ResponseEntity<List<ConectorFonteDadosResponse>> listarConectores() {
        return ResponseEntity.ok(service.listarConectores());
    }

    @RequirePermission(Permissao.CARGA_CONSULTAR)
    @GetMapping("/execucoes")
    public ResponseEntity<List<AirflowIngestaoStatusResponse>> listarExecucoes(
            @RequestParam(required = false) Long conectorId,
            @RequestParam(required = false) String destino,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(service.listarExecucoes(conectorId, destino, status, limit));
    }

    @RequirePermission(Permissao.CARGA_CONSULTAR)
    @GetMapping("/{id}")
    public ResponseEntity<ConectorFonteDadosResponse> buscarPorId(@PathVariable Long id) {
        return ResponseEntity.ok(service.buscarConectorPorId(id));
    }

    @RequirePermission(Permissao.CARGA_ENVIAR)
    @PostMapping
    public ResponseEntity<ConectorFonteDadosResponse> criar(
            @Valid @RequestBody ConectorFonteDadosRequest req,
            Authentication auth) {
        ConectorFonteDadosResponse criado = service.criarConector(req, resolverUsuarioId(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(criado);
    }

    @RequirePermission(Permissao.CARGA_ENVIAR)
    @PutMapping("/{id}")
    public ResponseEntity<ConectorFonteDadosResponse> atualizar(
            @PathVariable Long id,
            @Valid @RequestBody ConectorFonteDadosRequest req) {
        return ResponseEntity.ok(service.atualizarConector(id, req));
    }

    @RequirePermission(Permissao.CARGA_ENVIAR)
    @PatchMapping("/{id}/ativo")
    public ResponseEntity<ConectorFonteDadosResponse> alternarAtivo(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        if (body == null || !(body.get("ativo") instanceof Boolean ativo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o campo booleano 'ativo'.");
        }
        return ResponseEntity.ok(service.alternarAtivo(id, ativo));
    }

    @RequirePermission(Permissao.CARGA_ENVIAR)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        service.excluirConector(id);
        return ResponseEntity.noContent().build();
    }

    @RequirePermission(Permissao.CARGA_ENVIAR)
    @PostMapping("/{id}/testar")
    public ResponseEntity<Map<String, Object>> testarConexao(@PathVariable Long id) {
        return ResponseEntity.ok(service.testarConexao(id));
    }

    @RequirePermission(Permissao.CARGA_ENVIAR)
    @PostMapping("/{id}/executar")
    public ResponseEntity<AirflowIngestaoStatusResponse> executar(
            @PathVariable Long id,
            @RequestBody(required = false) ExecutarConectorRequest req,
            Authentication auth) {
        AirflowIngestaoStatusResponse execucao = service.executarConector(id, req, resolverUsuarioId(auth));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(execucao);
    }

    @RotaPublica
    @PostMapping("/interno/processos/{processoId}/pos-processar")
    public ResponseEntity<Map<String, Object>> posProcessarInterno(
            @PathVariable Long processoId,
            @RequestHeader(value = "X-Airflow-Token", required = false) String airflowToken) {
        if (!tokenAirflowValido(airflowToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("erro", "Token interno Airflow ausente ou inválido."));
        }
        return ResponseEntity.ok(service.executarPosProcessamentoCarga(processoId, null));
    }

    private boolean tokenAirflowValido(String airflowToken) {
        if (airflowToken == null || airflowToken.isBlank()
                || airflowInternalToken == null || airflowInternalToken.isBlank()) {
            return false;
        }
        byte[] esperado = airflowInternalToken.getBytes(StandardCharsets.UTF_8);
        byte[] recebido = airflowToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(esperado, recebido);
    }

    private Long resolverUsuarioId(Authentication auth) {
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return null;
        }
        return usuarioSessaoService.buscarPorLogin(auth.getName())
                .map(EstadoUsuario::id)
                .orElse(null);
    }
}
