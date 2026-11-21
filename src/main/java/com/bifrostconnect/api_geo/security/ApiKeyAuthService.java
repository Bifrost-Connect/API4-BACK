package com.bifrostconnect.api_geo.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ApiKeyAuthService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthService.class);

    public static final int LIMITE_REQUISICOES_POR_MINUTO = 60;
    private static final long JANELA_RATE_LIMIT_MS = 60_000L;
    private static final int TAMANHO_PREFIXO_LOG = 12; // o mesmo que fica em chaves_api.prefixo_chave
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

    // US12 - Tarefa 16: por que a chave foi recusada. Tudo isso é 401; o código diferente é só pro
    // parceiro saber se precisa pedir chave nova (expirada/revogada) ou se mandou errado
    public enum MotivoRecusa {
        AUSENTE("API_KEY_AUSENTE", "Informe a API Key no header X-API-Key."),
        INEXISTENTE("API_KEY_INVALIDA", "API Key inválida."),
        EXPIRADA("API_KEY_EXPIRADA", "API Key expirada. Solicite uma nova chave."),
        REVOGADA("API_KEY_REVOGADA", "API Key revogada. Solicite uma nova chave."),
        USUARIO_INATIVO("API_KEY_USUARIO_INATIVO", "API Key vinculada a um usuário inativo.");

        private final String codigo;
        private final String mensagem;

        MotivoRecusa(String codigo, String mensagem) {
            this.codigo = codigo;
            this.mensagem = mensagem;
        }

        public String getCodigo() {
            return codigo;
        }

        public String getMensagem() {
            return mensagem;
        }
    }

    // ou vem o contexto (chave aceita) ou vem o motivo da recusa, nunca os dois
    public record ResultadoAutenticacao(ContextoApiKey contexto, MotivoRecusa motivo) {

        static ResultadoAutenticacao aceita(ContextoApiKey contexto) {
            return new ResultadoAutenticacao(contexto, null);
        }

        static ResultadoAutenticacao recusada(MotivoRecusa motivo) {
            return new ResultadoAutenticacao(null, motivo);
        }

        public boolean autenticada() {
            return contexto != null;
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
    private final Clock clock;

    // Tarefa 15: Cache em memória indexado pelo hash + índice reverso por ID para revogação instantânea
    private final ConcurrentHashMap<String, EntradaCache> cachePorHash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> hashPorChaveId = new ConcurrentHashMap<>();

    // Tarefa 14: Controle de 60 req/min por chave
    private final ConcurrentHashMap<Long, JanelaRateLimit> rateLimitPorChave = new ConcurrentHashMap<>();

    @Autowired
    public ApiKeyAuthService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(jdbc, objectMapper, Clock.systemDefaultZone());
    }

    // Tarefa 17: os testes passam um relógio próprio pra simular a chave vencendo sem sleep
    ApiKeyAuthService(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Optional<ContextoApiKey> autenticar(String chaveBruta) {
        return Optional.ofNullable(validar(chaveBruta).contexto());
    }

    public ResultadoAutenticacao validar(String chaveBruta) {
        if (chaveBruta == null || chaveBruta.isBlank()) {
            return ResultadoAutenticacao.recusada(MotivoRecusa.AUSENTE);
        }

        String chave = chaveBruta.trim();
        String hash = gerarSha256Hex(chave);
        long agoraMs = clock.millis();

        EntradaCache emCache = cachePorHash.get(hash);
        if (emCache != null && emCache.expiraCacheEmMs() > agoraMs) {
            ContextoApiKey ctx = emCache.contexto();
            // a chave pode vencer enquanto está no cache: confere de novo a cada chamada
            if (ctx.dataExpiracao() != null && !ctx.dataExpiracao().isAfter(clock.instant())) {
                invalidarCachePorId(ctx.chaveId());
                return recusar(MotivoRecusa.EXPIRADA, chave);
            }
            return ResultadoAutenticacao.aceita(ctx);
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
            return recusar(diagnosticarRecusa(hash), chave);
        }

        ContextoApiKey contexto = encontrados.get(0);
        cachePorHash.put(hash, new EntradaCache(contexto, agoraMs + TTL_CACHE_MS));
        hashPorChaveId.put(contexto.chaveId(), hash);

        return ResultadoAutenticacao.aceita(contexto);
    }

    // Tarefa 16: só roda quando a chave já foi recusada, pra dizer o motivo. Continua buscando pelo
    // hash: a chave em texto nunca vai pro banco, nem aqui
    private MotivoRecusa diagnosticarRecusa(String hash) {
        List<MotivoRecusa> motivos = jdbc.query("""
                SELECT c.status,
                       (c.data_expiracao IS NOT NULL AND c.data_expiracao <= CURRENT_TIMESTAMP) AS vencida,
                       u.ativo
                  FROM chaves_api c
                  JOIN usuario u ON u.id = c.usuario_id
                 WHERE c.hash_chave_sha256 = ?
                 LIMIT 1
                """,
                (rs, rowNum) -> {
                    String status = rs.getString("status");
                    if ("REVOGADA".equals(status)) {
                        return MotivoRecusa.REVOGADA;
                    }
                    if ("EXPIRADA".equals(status) || rs.getBoolean("vencida")) {
                        return MotivoRecusa.EXPIRADA;
                    }
                    if (!rs.getBoolean("ativo")) {
                        return MotivoRecusa.USUARIO_INATIVO;
                    }
                    return MotivoRecusa.INEXISTENTE;
                },
                hash);

        return motivos.isEmpty() ? MotivoRecusa.INEXISTENTE : motivos.get(0);
    }

    // no log vai só o começo da chave (o mesmo prefixo que a tela de chaves mostra), nunca ela inteira
    private ResultadoAutenticacao recusar(MotivoRecusa motivo, String chave) {
        log.info("API Key recusada: motivo={}, prefixo={}", motivo, mascarar(chave));
        return ResultadoAutenticacao.recusada(motivo);
    }

    static String mascarar(String chave) {
        if (chave == null || chave.length() <= TAMANHO_PREFIXO_LOG) {
            return "****";
        }
        return chave.substring(0, TAMANHO_PREFIXO_LOG) + "****";
    }

    // Tarefa 14: Máximo de 60 chamadas por minuto por API Key
    public boolean permitirRequisicao(Long chaveId) {
        long agoraMs = clock.millis();
        JanelaRateLimit janela = rateLimitPorChave.computeIfAbsent(chaveId, id -> new JanelaRateLimit(agoraMs));

        synchronized (janela) {
            if (agoraMs - janela.inicioJanelaMs >= JANELA_RATE_LIMIT_MS) {
                janela.inicioJanelaMs = agoraMs;
                janela.contador.set(0);
            }
            return janela.contador.incrementAndGet() <= LIMITE_REQUISICOES_POR_MINUTO;
        }
    }

    // Tarefa 16: vai no Retry-After do 429 (segundos até a janela de 1 minuto virar)
    public long segundosAteLiberar(Long chaveId) {
        JanelaRateLimit janela = rateLimitPorChave.get(chaveId);
        if (janela == null) {
            return 0;
        }
        long restanteMs = JANELA_RATE_LIMIT_MS - (clock.millis() - janela.inicioJanelaMs);
        return Math.max(1, (restanteMs + 999) / 1000);
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

    /**
     * Registra uma chamada da API externa na tabela log_acessos_api_externa (V40).
     */
    public void registrarLogAcessoExterno(Long chaveId, String rota, int statusHttp, String ip) {
        if (chaveId == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO log_acessos_api_externa
                    (chave_api_id, rota_acessada, status_http, ip_origem, data_acesso)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                chaveId,
                truncar(rota, 255),
                statusHttp,
                truncar(ip, 45));
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

    private static String truncar(String valor, int maximo) {
        if (valor == null || valor.length() <= maximo) {
            return valor;
        }
        return valor.substring(0, maximo);
    }
}
