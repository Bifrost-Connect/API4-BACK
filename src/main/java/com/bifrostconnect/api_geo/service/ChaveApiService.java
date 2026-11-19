package com.bifrostconnect.api_geo.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.bifrostconnect.api_geo.dto.EmitirChaveApiRequest;
import com.bifrostconnect.api_geo.exception.ErroPadronizadoException;
import com.bifrostconnect.api_geo.exception.RecursoNaoEncontradoException;
import com.bifrostconnect.api_geo.exception.RequisicaoInvalidaException;
import com.bifrostconnect.api_geo.security.UsuarioSessaoService;
import com.bifrostconnect.api_geo.security.UsuarioSessaoService.EstadoUsuario;
import com.bifrostconnect.api_geo.service.ChaveApiCriptoService.ChaveGerada;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bifrostconnect.api_geo.security.ApiKeyAuthService;

// Tarefa 11: ciclo de vida das chaves (emitir, listar, revogar) sobre a tabela chaves_api (V39).
// Só grava o hash SHA-256 e o prefixo; a chave em texto aparece apenas na resposta da emissão.
@Service
public class ChaveApiService {

    private static final Set<String> STATUS_VALIDOS = Set.of("ATIVA", "REVOGADA", "EXPIRADA");
    private static final int TAMANHO_MAXIMO_PAGINA = 100;
    private static final String MASCARA = "********";
    private static final String PERFIL_ADMINISTRADOR = "ADMINISTRADOR";

    // status_efetivo: uma chave ATIVA já vencida aparece como EXPIRADA sem alterar nada no banco
    private static final String SELECT_CHAVES = """
            SELECT c.id, c.usuario_id, c.nome_parceiro, c.prefixo_chave, c.escopos::text AS escopos,
                   CASE WHEN c.status = 'ATIVA'
                             AND c.data_expiracao IS NOT NULL
                             AND c.data_expiracao <= now()
                        THEN 'EXPIRADA' ELSE c.status END AS status_efetivo,
                   c.data_criacao, c.data_expiracao, c.data_revogacao,
                   c.usuario_revogacao_id, c.ultimo_uso_em
              FROM chaves_api c
            """;

    private final JdbcTemplate jdbc;
    private final ChaveApiCriptoService cripto;
    private final UsuarioSessaoService usuarioSessaoService;
    private final ApiKeyAuthService apiKeyAuthService;
    private final ObjectMapper objectMapper;

    public ChaveApiService(
            JdbcTemplate jdbc,
            ChaveApiCriptoService cripto,
            UsuarioSessaoService usuarioSessaoService,
            ApiKeyAuthService apiKeyAuthService,
            ObjectMapper objectMapper) {

        this.jdbc = jdbc;
        this.cripto = cripto;
        this.usuarioSessaoService = usuarioSessaoService;
        this.apiKeyAuthService = apiKeyAuthService;
        this.objectMapper = objectMapper;
    }

    // POST /integracoes/chaves
    @Transactional
    public Map<String, Object> emitir(EmitirChaveApiRequest request, String login) {

        Long usuarioId = resolverUsuarioId(login);

        Set<String> escopos = new LinkedHashSet<>();
        for (String escopo : request.escopos()) {
            escopos.add(escopo.trim());
        }

        ChaveGerada gerada = cripto.gerar();

        Long id = jdbc.queryForObject("""
                INSERT INTO chaves_api
                    (usuario_id, nome_parceiro, prefixo_chave, hash_chave_sha256,
                     escopos, status, data_criacao, data_expiracao)
                VALUES (?, ?, ?, ?, ?::jsonb, 'ATIVA', now(), ?)
                RETURNING id
                """,
                new Object[] {
                        usuarioId,
                        request.nomeParceiro().trim(),
                        gerada.prefixoVisivel(),
                        gerada.hashSha256(),
                        paraJson(new ArrayList<>(escopos)),
                        request.dataExpiracao() == null ? null : Timestamp.valueOf(request.dataExpiracao())
                },
                new int[] {Types.BIGINT, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.TIMESTAMP},
                Long.class);

        Map<String, Object> resposta = buscarMetadados(id);

        // a única vez em que a chave em texto sai do sistema: nada disso fica guardado
        resposta.put("chave", gerada.chaveAberta());
        resposta.put("aviso", "Guarde esta chave agora. Ela não será exibida novamente.");

        return resposta;
    }

    // GET /integracoes/chaves (só metadados e prefixo mascarado, nunca o hash nem a chave)
    // Tarefa 16: o Administrador vê todas; o integrador só as que ele emitiu
    @Transactional(readOnly = true)
    public Map<String, Object> listar(String status, int page, int size, String login) {

        if (page < 0 || size < 1 || size > TAMANHO_MAXIMO_PAGINA) {
            throw new RequisicaoInvalidaException(
                    "Paginação inválida: page >= 0 e size entre 1 e " + TAMANHO_MAXIMO_PAGINA + ".");
        }

        EstadoUsuario usuario = resolverUsuario(login);

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();

        if (!ehAdministrador(usuario)) {
            where.append(" AND x.usuario_id = ?");
            args.add(usuario.id());
        }

        if (status != null && !status.isBlank()) {
            String statusNormalizado = status.trim().toUpperCase();

            if (!STATUS_VALIDOS.contains(statusNormalizado)) {
                throw new RequisicaoInvalidaException("Status inválido. Use ATIVA, REVOGADA ou EXPIRADA.");
            }

            where.append(" AND x.status_efetivo = ?");
            args.add(statusNormalizado);
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM (" + SELECT_CHAVES + ") x" + where,
                Long.class,
                args.toArray());

        long totalElementos = total == null ? 0L : total;

        List<Object> argsPagina = new ArrayList<>(args);
        argsPagina.add(size);
        argsPagina.add((long) page * size);

        List<Map<String, Object>> content = jdbc.query(
                "SELECT * FROM (" + SELECT_CHAVES + ") x" + where
                        + " ORDER BY x.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> mapear(rs),
                argsPagina.toArray());

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("content", content);
        resultado.put("page", page);
        resultado.put("size", size);
        resultado.put("totalElements", totalElementos);
        resultado.put("totalPages", (int) ((totalElementos + size - 1) / size));

        return resultado;
    }

    // PATCH /integracoes/chaves/{id}/revogar
    // Efeito imediato: o status vira REVOGADA na própria transação (quem autentica só aceita ATIVA).
    // Se a chave já estava revogada, nada muda e a data original da revogação é preservada.
    // Tarefa 16: integrador revogando chave de outro usuário -> 403 (o Administrador pode revogar qualquer uma)
    @Transactional
    public Map<String, Object> revogar(Long id, String login) {

        EstadoUsuario usuario = resolverUsuario(login);
        Long usuarioId = usuario.id();

        // FOR UPDATE: segura a linha até o fim, pra ninguém trocar o dono/status no meio
        List<Long> donos = jdbc.queryForList(
                "SELECT usuario_id FROM chaves_api WHERE id = ? FOR UPDATE", Long.class, id);

        if (donos.isEmpty()) {
            throw new RecursoNaoEncontradoException("Chave de API não encontrada: " + id);
        }

        if (!ehAdministrador(usuario) && !donos.get(0).equals(usuarioId)) {
            throw new ErroPadronizadoException(
                    HttpStatus.FORBIDDEN,
                    "CHAVE_DE_OUTRO_USUARIO",
                    "Você só pode revogar as chaves de API emitidas por você.");
        }

        int alteradas = jdbc.update("""
                UPDATE chaves_api
                   SET status = 'REVOGADA',
                       data_revogacao = now(),
                       usuario_revogacao_id = ?
                 WHERE id = ?
                   AND status <> 'REVOGADA'
                """, usuarioId, id);

        // Tarefa 15: invalida o cache em memória imediatamente, sem delay
        apiKeyAuthService.invalidarCachePorId(id);

        Map<String, Object> resposta = buscarMetadados(id);
        resposta.put("revogada_agora", alteradas > 0);

        return resposta;
    }

    // ------------------------------------------------------------------
    // Auxiliares
    // ------------------------------------------------------------------

    private Long resolverUsuarioId(String login) {
        return resolverUsuario(login).id();
    }

    private EstadoUsuario resolverUsuario(String login) {
        return usuarioSessaoService.buscarPorLogin(login)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Usuário autenticado não encontrado na base de usuários."));
    }

    private static boolean ehAdministrador(EstadoUsuario usuario) {
        return PERFIL_ADMINISTRADOR.equalsIgnoreCase(usuario.perfil());
    }

    private Map<String, Object> buscarMetadados(Long id) {
        List<Map<String, Object>> lista = jdbc.query(
                SELECT_CHAVES + " WHERE c.id = ?",
                (rs, i) -> mapear(rs),
                id);

        if (lista.isEmpty()) {
            throw new RecursoNaoEncontradoException("Chave de API não encontrada: " + id);
        }

        return lista.get(0);
    }

    private Map<String, Object> mapear(ResultSet rs) throws SQLException {
        Map<String, Object> chave = new LinkedHashMap<>();

        // nomes das colunas de chaves_api, como o AdminUsuarioService já devolve as colunas de usuario
        chave.put("id", rs.getLong("id"));
        chave.put("nome_parceiro", rs.getString("nome_parceiro"));
        chave.put("prefixo_mascarado", rs.getString("prefixo_chave") + MASCARA);
        chave.put("escopos", lerEscopos(rs.getString("escopos")));
        chave.put("status", rs.getString("status_efetivo"));
        chave.put("data_criacao", paraLocalDateTime(rs.getTimestamp("data_criacao")));
        chave.put("data_expiracao", paraLocalDateTime(rs.getTimestamp("data_expiracao")));
        chave.put("data_revogacao", paraLocalDateTime(rs.getTimestamp("data_revogacao")));
        chave.put("ultimo_uso_em", paraLocalDateTime(rs.getTimestamp("ultimo_uso_em")));
        chave.put("usuario_id", rs.getLong("usuario_id"));

        long revogadoPor = rs.getLong("usuario_revogacao_id");
        chave.put("usuario_revogacao_id", rs.wasNull() ? null : revogadoPor);

        return chave;
    }

    private String paraJson(Object valor) {
        try {
            return objectMapper.writeValueAsString(valor);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Não foi possível converter os escopos para JSON.", e);
        }
    }

    private Object lerEscopos(String json) {
        if (json == null) {
            return List.of();
        }

        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Escopos da chave com JSON inválido.", e);
        }
    }

    private static LocalDateTime paraLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}