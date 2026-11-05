package com.bifrostconnect.api_geo.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.bifrostconnect.api_geo.security.UsuarioSessaoService.EstadoUsuario;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

// US13 - Tarefa 16: JWT HS256 com a versao_sessao do usuário dentro ("ver").
// Feito na mão com HmacSHA256 do próprio Java pra não puxar biblioteca nova só por isso
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();
    private static final String HEADER = B64.encodeToString(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] segredo;
    private final long expiracaoSegundos;
    private final ObjectMapper objectMapper;

    public record TokenEmitido(String token, Instant expiraEm) {
    }

    // o que sai de um token válido
    public record ClaimsToken(Long usuarioId, String email, String perfil, int versaoSessao) {
    }

    public JwtService(
            @Value("${seguranca.jwt.segredo:}") String segredo,
            @Value("${seguranca.jwt.expiracao-minutos:480}") long expiracaoMinutos,
            ObjectMapper objectMapper) {

        if (segredo == null || segredo.isBlank()) {
            // sem segredo configurado gera um aleatório: funciona, mas todo restart derruba os tokens
            byte[] aleatorio = new byte[32];
            new SecureRandom().nextBytes(aleatorio);
            this.segredo = aleatorio;
            log.warn("JWT_SECRET não configurado: usando segredo aleatório (tokens caem a cada restart).");
        } else {
            this.segredo = segredo.getBytes(StandardCharsets.UTF_8);
        }
        this.expiracaoSegundos = expiracaoMinutos * 60;
        this.objectMapper = objectMapper;
    }

    public TokenEmitido gerar(EstadoUsuario usuario) {
        Instant agora = Instant.now();
        Instant expira = agora.plusSeconds(expiracaoSegundos);

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", usuario.email());
        claims.put("uid", usuario.id());
        claims.put("perfil", usuario.perfil());
        claims.put("ver", usuario.versaoSessao());
        claims.put("iat", agora.getEpochSecond());
        claims.put("exp", expira.getEpochSecond());

        try {
            String payload = B64.encodeToString(objectMapper.writeValueAsBytes(claims));
            String conteudo = HEADER + "." + payload;
            return new TokenEmitido(conteudo + "." + assinar(conteudo), expira);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao gerar token JWT.", e);
        }
    }

    // assinatura, formato e expiração. Ativo/versão de sessão quem confere é o filtro, contra o banco
    public ClaimsToken validar(String token) {
        String[] partes = token == null ? new String[0] : token.split("\\.");
        if (partes.length != 3 || !HEADER.equals(partes[0])) {
            throw new TokenInvalidoException("Token malformado.");
        }

        byte[] esperada = assinar(partes[0] + "." + partes[1]).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(esperada, partes[2].getBytes(StandardCharsets.US_ASCII))) {
            throw new TokenInvalidoException("Assinatura do token inválida.");
        }

        Map<String, Object> claims;
        try {
            claims = objectMapper.readValue(B64_DEC.decode(partes[1]), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new TokenInvalidoException("Token malformado.");
        }

        Number exp = (Number) claims.get("exp");
        if (exp == null || Instant.now().getEpochSecond() >= exp.longValue()) {
            throw new TokenInvalidoException("Token expirado.");
        }

        Number uid = (Number) claims.get("uid");
        Number ver = (Number) claims.get("ver");
        if (uid == null || ver == null) {
            throw new TokenInvalidoException("Token sem identificação do usuário.");
        }
        return new ClaimsToken(uid.longValue(), (String) claims.get("sub"), (String) claims.get("perfil"), ver.intValue());
    }

    private String assinar(String conteudo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo, "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(conteudo.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao assinar token JWT.", e);
        }
    }

    public static class TokenInvalidoException extends RuntimeException {
        public TokenInvalidoException(String message) {
            super(message);
        }
    }
}
