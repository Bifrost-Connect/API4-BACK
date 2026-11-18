package com.bifrostconnect.api_geo.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ApiKeyAuthService {

    private static final int LIMITE_REQUISICOES_POR_MINUTO = 60;
    private static final long TTL_CACHE_MS = 60_000L; // 1 min (invalidado antes se revogar)

    public record ContextoApiKey(
            Long chaveId,
            Long usuarioId,
            String nomeParceiro,
            Set<String> escopos,
            Instant dataExpiracao) {

        public boolean possuiEscopo(String escopoExigido) {
            if (escopos.contains("*") || escopos.contains("ADMIN") || escopos.contains("LEITURA_TOTAL")) {
                return true;
            }
            return escopos.contains(escopoExigido.toUpperCase());
        }
    }

    private record EntradaCache(ContextoApiKey contexto, long expiraCacheEmMs) {}

    private static class JanelaRateLimit {
        volatile long inicioJanelaMs;
        final AtomicInteger contador = new AtomicInteger(0);

        JanelaRateLimit(long agoraMs) {
            this.inicioJanelaMs = agoraMs;
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    // Tarefa 15: Cache em memória indexado pelo hash + índice reverso por ID para revogação instantânea
    private final ConcurrentHashMap<String, EntradaCache> cachePorHash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> hashPorChaveId = new ConcurrentHashMap<>();

    // Tarefa 14: Controle de 60 req/min por chave
    private final ConcurrentHashMap<Long, JanelaRateLimit> rateLimitPorChave = new ConcurrentHashMap<>();

    public ApiKeyAuthService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<ContextoApiKey> autenticar(String chaveBruta) {
        if (chaveBruta == null || chaveBruta.isBlank()) {
            return Optional.empty();
        }

        String hash = gerarSha256Hex(chaveBruta.trim());
        long agoraMs = System.currentTimeMillis();

        EntradaCache emCache = cachePorHash.get(hash);
        if (emCache != null && emCache.expiraCacheEmMs() > agoraMs) {
            ContextoApiKey ctx = emCache.contexto();
            if (ctx.dataExpiracao() != null && ctx.dataExpiracao().isBefore(Instant.now())) {
                invalidarCachePorId(ctx.chaveId());
                return Optional.empty();
            }
            return Optional.of(ctx);
        }

        // Consulta no banco validando as 4 condições da Tarefa 12
        List<ContextoApiKey> encontrados = jdbc.query("""
                SELECT c.id, c.usuario_id, c.nome_parceiro, c.escopos::text AS escopos_json, c.data_expiracao
                  FROM chaves_api c
                  JOIN usuario u ON u.id = c.usuario_id
                 WHERE c.hash_chave_sha256 = ?
                   AND c.status = 'ATIVA'
                   AND (c.data_expiracao IS NULL OR c.data_expiracao > CURRENT_TIMESTAMP)
                   AND u.ativo = TRUE
                 LIMIT 1
                """,
                (rs, rowNum) -> {
                    Timestamp exp = rs.getTimestamp("data_expiracao");
                    return new ContextoApiKey(
                            rs.getLong("id"),
                            rs.getLong("usuario_id"),
                            rs.getString("nome_parceiro"),
                            extrairEscopos(rs.getString("escopos_json")),
                            exp != null ? exp.toInstant() : null);
                },
                hash);

        if (encontrados.isEmpty()) {
            cachePorHash.remove(hash);
            return Optional.empty();
        }

        ContextoApiKey contexto = encontrados.get(0);
        cachePorHash.put(hash, new EntradaCache(contexto, agoraMs + TTL_CACHE_MS));
        hashPorChaveId.put(contexto.chaveId(), hash);

        return Optional.of(contexto);
    }

    // Tarefa 14: Máximo de 60 chamadas por minuto por API Key
    public boolean permitirRequisicao(Long chaveId) {
        long agoraMs = System.currentTimeMillis();
        JanelaRateLimit janela = rateLimitPorChave.computeIfAbsent(chaveId, id -> new JanelaRateLimit(agoraMs));

        synchronized (janela) {
            if (agoraMs - janela.inicioJanelaMs >= 60_000L) {
                janela.inicioJanelaMs = agoraMs;
                janela.contador.set(0);
            }
            return janela.contador.incrementAndGet() <= LIMITE_REQUISICOES_POR_MINUTO;
        }
    }

    // Tarefa 14: Atualiza ultimo_uso_em e registra log de acesso
    public void registrarUsoEAuditoria(ContextoApiKey ctx, String metodoHttp, String uri, String ip) {
        jdbc.update("UPDATE chaves_api SET ultimo_uso_em = CURRENT_TIMESTAMP WHERE id = ?", ctx.chaveId());

        jdbc.update("""
                INSERT INTO auditoria (usuario_id, processo_id, acao, descricao, detalhes, data_hora, ip)
                VALUES (?, NULL, 'CONSUMO_API_EXTERNA', ?, ?, CURRENT_TIMESTAMP, ?)
                """,
                ctx.usuarioId(),
                metodoHttp + " " + uri,
                "chaveId=" + ctx.chaveId() + "; parceiro=" + ctx.nomeParceiro(),
                ip);
    }

    // Tarefa 15: Chamado no momento da revogação para derrubar o cache instantaneamente
    public void invalidarCachePorId(Long chaveId) {
        if (chaveId == null) {
            return;
        }
        String hash = hashPorChaveId.remove(chaveId);
        if (hash != null) {
            cachePorHash.remove(hash);
        }
        rateLimitPorChave.remove(chaveId);
    }

    private String gerarSha256Hex(String valor) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(valor.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes); // Sempre minúsculo (64 chars)
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algoritmo SHA-256 indisponível", e);
        }
    }

    private Set<String> extrairEscopos(String json) {
        Set<String> resultado = new HashSet<>();
        if (json == null || json.isBlank()) {
            return resultado;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isArray()) {
                node.forEach(item -> resultado.add(item.asText().trim().toUpperCase()));
            } else if (node.isObject()) {
                node.fields().forEachRemaining(entry -> {
                    if (entry.getValue().asBoolean(false)) {
                        resultado.add(entry.getKey().trim().toUpperCase());
                    }
                });
            }
        } catch (Exception ignored) {
        }
        return resultado;
    }
}