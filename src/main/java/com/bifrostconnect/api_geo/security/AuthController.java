package com.bifrostconnect.api_geo.security;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bifrostconnect.api_geo.security.JwtService.TokenEmitido;
import com.bifrostconnect.api_geo.security.UsuarioSessaoService.EstadoUsuario;

// US13: login que emite o JWT (com versao_sessao). Rota pública no SecurityConfig
@RestController
@RotaPublica
@RequestMapping("/auth")
public class AuthController {

    private final UsuarioSessaoService usuarioSessaoService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(
            UsuarioSessaoService usuarioSessaoService,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.usuarioSessaoService = usuarioSessaoService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        String login = body.get("email") == null ? null : body.get("email").toString();
        String senha = body.get("senha") == null ? null : body.get("senha").toString();

        // mesma mensagem pra usuário inexistente, senha errada e inativo: não entrega quem existe
        EstadoUsuario usuario = usuarioSessaoService.buscarPorLogin(login)
                .filter(u -> senha != null && passwordEncoder.matches(senha, u.senhaHash()))
                .filter(EstadoUsuario::ativo)
                .orElse(null);

        if (usuario == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("erro", "E-mail ou senha inválidos, ou usuário inativo."));
        }

        TokenEmitido token = jwtService.gerar(usuario);

        Map<String, Object> dadosUsuario = new LinkedHashMap<>();
        dadosUsuario.put("id", usuario.id());
        dadosUsuario.put("nome", usuario.nome());
        dadosUsuario.put("email", usuario.email());
        dadosUsuario.put("perfil", usuario.perfil());
        dadosUsuario.put("permissoes", MatrizPermissoes.permissoesDo(usuario.perfil()));

        Map<String, Object> resposta = new LinkedHashMap<>();
        resposta.put("token", token.token());
        resposta.put("tipo", "Bearer");
        resposta.put("expira_em", token.expiraEm().toString());
        resposta.put("usuario", dadosUsuario);
        return ResponseEntity.ok(resposta);
    }
}
