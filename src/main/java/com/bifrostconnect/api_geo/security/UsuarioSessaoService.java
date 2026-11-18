package com.bifrostconnect.api_geo.security;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

// US13: estado ATUAL do usuário direto do banco (ativo, perfil, versão de sessão, exceção SoD e
// permissões customizadas). O token só diz quem é; o que ele pode fazer agora sempre vem daqui
@Service
public class UsuarioSessaoService {

    public record EstadoUsuario(
            Long id,
            String nome,
            String email,
            String senhaHash,
            String perfil,
            boolean ativo,
            int versaoSessao,
            boolean excecaoSod,
            List<String> permissoesCustomizadas) {

        public EstadoUsuario(
                Long id,
                String nome,
                String email,
                String senhaHash,
                String perfil,
                boolean ativo,
                int versaoSessao,
                boolean excecaoSod) {
            this(id, nome, email, senhaHash, perfil, ativo, versaoSessao, excecaoSod, null);
        }
    }

    private static final TypeReference<List<String>> LISTA_STRINGS = new TypeReference<>() {
    };

    private static final Map<String, Permissao> PERMISSOES_POR_NOME = criarIndicePermissoes();
    private static final Map<String, Set<Permissao>> MAPA_SUBPERMISSOES = criarMapaSubpermissoes();

    private static final String SELECT_ESTADO = """
            SELECT u.id, u.nome, u.email, u.senha_hash, p.nome AS perfil, u.ativo,
                   u.versao_sessao, u.excecao_sod_autorizada,
                   u.permissoes_customizadas::text AS permissoes_customizadas
              FROM usuario u
              JOIN perfil p ON p.id = u.perfil_id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Autowired
    public UsuarioSessaoService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public UsuarioSessaoService(JdbcTemplate jdbc) {
        this(jdbc, new ObjectMapper());
    }

    public Optional<EstadoUsuario> buscarPorId(Long id) {
        return primeiro(jdbc.query(SELECT_ESTADO + " WHERE u.id = ?", (rs, i) -> mapear(rs), id));
    }

    // aceita o e-mail ou só a parte antes do @ (o front ainda loga com "admin", "operador"...)
    public Optional<EstadoUsuario> buscarPorLogin(String login) {
        if (login == null || login.isBlank()) {
            return Optional.empty();
        }
        String identificador = login.trim();
        return primeiro(jdbc.query(SELECT_ESTADO + """
                 WHERE LOWER(u.email) = LOWER(?)
                    OR LOWER(SPLIT_PART(u.email, '@', 1)) = LOWER(?)
                 ORDER BY CASE WHEN LOWER(u.email) = LOWER(?) THEN 0 ELSE 1 END, u.id
                 LIMIT 1
                """, (rs, i) -> mapear(rs), identificador, identificador, identificador));
    }

    /**
     * Resolve o conjunto efetivo de permissões do usuário considerando customizações e restrição
     * estrita de somente-leitura para o perfil AUDITOR.
     */
    public Set<Permissao> permissoesEfetivas(EstadoUsuario usuario) {
        if (usuario == null) {
            return Set.of();
        }
        return resolverPermissoes(usuario.perfil(), usuario.permissoesCustomizadas());
    }

    /**
     * Resolve o conjunto efetivo de permissões para um perfil e uma lista opcional de códigos
     * customizados (nomes do enum Permissao ou chaves MODULO:SUBPERMISSAO).
     */
    public Set<Permissao> resolverPermissoes(String perfil, List<String> permissoesCustomizadas) {
        if (permissoesCustomizadas == null) {
            return MatrizPermissoes.permissoesDo(perfil);
        }

        EnumSet<Permissao> efetivas = EnumSet.noneOf(Permissao.class);
        for (String entrada : permissoesCustomizadas) {
            if (entrada == null || entrada.isBlank()) {
                continue;
            }
            String codigo = entrada.trim().toUpperCase(Locale.ROOT);
            Permissao direta = PERMISSOES_POR_NOME.get(codigo);
            if (direta != null) {
                efetivas.add(direta);
                continue;
            }
            Set<Permissao> mapeadas = MAPA_SUBPERMISSOES.get(codigo);
            if (mapeadas != null) {
                efetivas.addAll(mapeadas);
            }
        }

        if ("AUDITOR".equalsIgnoreCase(perfil)) {
            efetivas.retainAll(MatrizPermissoes.permissoesDo("AUDITOR"));
        }

        return Collections.unmodifiableSet(efetivas);
    }

    /**
     * Desserializa o JSONB de permissões customizadas preservando NULL quando não houver override.
     */
    public static List<String> deserializarPermissoes(ObjectMapper mapper, String json) {
        if (json == null) {
            return null;
        }
        try {
            List<String> lista = mapper.readValue(json, LISTA_STRINGS);
            if (lista == null) {
                return null;
            }
            return lista.stream().filter(Objects::nonNull).toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao desserializar permissoes_customizadas do usuário.", e);
        }
    }

    private EstadoUsuario mapear(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EstadoUsuario(
                rs.getLong("id"),
                rs.getString("nome"),
                rs.getString("email"),
                rs.getString("senha_hash"),
                rs.getString("perfil"),
                rs.getBoolean("ativo"),
                rs.getInt("versao_sessao"),
                rs.getBoolean("excecao_sod_autorizada"),
                deserializarPermissoes(objectMapper, rs.getString("permissoes_customizadas")));
    }

    private Optional<EstadoUsuario> primeiro(List<EstadoUsuario> lista) {
        return lista.isEmpty() ? Optional.empty() : Optional.of(lista.get(0));
    }

    private static Map<String, Permissao> criarIndicePermissoes() {
        Map<String, Permissao> indice = new java.util.HashMap<>();
        for (Permissao permissao : Permissao.values()) {
            indice.put(permissao.name(), permissao);
        }
        return Map.copyOf(indice);
    }

    private static Map<String, Set<Permissao>> criarMapaSubpermissoes() {
        return Map.ofEntries(
                Map.entry("INGESTAO:UPLOAD", EnumSet.of(Permissao.CARGA_ENVIAR)),
                Map.entry("CARGAS:UPLOAD", EnumSet.of(Permissao.CARGA_ENVIAR)),
                Map.entry("INGESTAO:CANCELAR", EnumSet.of(Permissao.CARGA_CANCELAR)),
                Map.entry("INGESTAO:VISUALIZAR_PROCESSAMENTO", EnumSet.of(Permissao.CARGA_CONSULTAR)),
                Map.entry("CARGAS:VISUALIZAR", EnumSet.of(Permissao.CARGA_CONSULTAR)),
                Map.entry("INGESTAO:REPROCESSAR", EnumSet.of(Permissao.CARGA_ATRIBUIR)),
                Map.entry("BASES:EDITAR_METADADOS", EnumSet.of(Permissao.CARGA_ATRIBUIR)),
                Map.entry("BASES:BAIXAR_ARQUIVO", EnumSet.of(Permissao.ARQUIVO_BAIXAR)),
                Map.entry("QUARENTENA:VISUALIZAR", EnumSet.of(Permissao.QUARENTENA_CONSULTAR)),
                Map.entry("QUARENTENA:CORRIGIR", EnumSet.of(Permissao.QUARENTENA_EXPORTAR)),
                Map.entry("QUARENTENA:REPROCESSAR", EnumSet.of(Permissao.QUARENTENA_EXPORTAR)),
                Map.entry("QUARENTENA:DESCARTAR", EnumSet.of(Permissao.QUARENTENA_EXPORTAR)),
                Map.entry("QUARENTENA:ENVIAR", EnumSet.of(Permissao.NOTIFICACAO_ENVIAR)),
                Map.entry("MAPA:EDITAR", EnumSet.of(Permissao.GEOMETRIA_EDITAR)),
                Map.entry("ANALISE_TERRITORIAL:SALVAR_RESULTADO", EnumSet.of(Permissao.GEOMETRIA_EDITAR)),
                Map.entry("ANALISE_TERRITORIAL:EXECUTAR", EnumSet.of(Permissao.ANALISE_EXECUTAR)),
                Map.entry("ANALISE_TERRITORIAL:CALCULAR_SOBREPOSICAO", EnumSet.of(Permissao.ANALISE_EXECUTAR)),
                Map.entry("INDICADORES:CALCULAR", EnumSet.of(Permissao.ANALISE_EXECUTAR)),
                Map.entry("MAPA:COMPARAR_SAFRAS", EnumSet.of(Permissao.COMPARAR_SAFRAS)),
                Map.entry("BASES:HOMOLOGAR", EnumSet.of(Permissao.CARGA_APROVAR)),
                Map.entry("INDICADORES:HOMOLOGAR", EnumSet.of(Permissao.CARGA_APROVAR)),
                Map.entry("INDICADORES:BLOQUEAR_VERSAO", EnumSet.of(Permissao.VERSAO_PUBLICAR)),
                Map.entry("INDICADORES:DESBLOQUEAR_VERSAO", EnumSet.of(Permissao.VERSAO_PUBLICAR)),
                Map.entry("EXPORTACAO:CSV", EnumSet.of(Permissao.RELATORIO_EXPORTAR)),
                Map.entry("EXPORTACAO:GEOJSON", EnumSet.of(Permissao.RELATORIO_EXPORTAR)),
                Map.entry("EXPORTACAO:HISTORICO", EnumSet.of(Permissao.EXPORTACAO_HISTORICO_CONSULTAR)),
                Map.entry("BASES:CONSULTAR_CATALOGO", EnumSet.of(Permissao.CATALOGO_CONSULTAR, Permissao.PUBLICACAO_CONSULTAR)),
                Map.entry("MAPA:VISUALIZAR", EnumSet.of(Permissao.MAPA_CONSULTAR)),
                Map.entry("MAPA:VISUALIZAR_CAMADAS", EnumSet.of(Permissao.MAPA_CONSULTAR)),
                Map.entry("MAPA:VISUALIZAR_SOBREPOSICOES", EnumSet.of(Permissao.MAPA_CONSULTAR)),
                Map.entry("AUDITORIA:RASTREAR_ORIGEM", EnumSet.of(Permissao.LINHAGEM_CONSULTAR)),
                Map.entry("AUDITORIA:VISUALIZAR_VERSOES", EnumSet.of(Permissao.LINHAGEM_CONSULTAR)),
                Map.entry("AUDITORIA:VISUALIZAR_HASH", EnumSet.of(Permissao.LINHAGEM_CONSULTAR)),
                Map.entry("AUDITORIA:VISUALIZAR_PROCESSAMENTOS", EnumSet.of(Permissao.LINHAGEM_CONSULTAR)),
                Map.entry("ANALISE_TERRITORIAL:CONSULTAR_RESULTADO", EnumSet.of(Permissao.DOMINIO_CONSULTAR, Permissao.PUBLICACAO_CONSULTAR)),
                Map.entry("INDICADORES:VISUALIZAR", EnumSet.of(Permissao.DOMINIO_CONSULTAR, Permissao.PUBLICACAO_CONSULTAR)),
                Map.entry("USUARIOS:VISUALIZAR", EnumSet.of(Permissao.USUARIO_GERENCIAR)),
                Map.entry("USUARIOS:GERENCIAR", EnumSet.of(Permissao.USUARIO_GERENCIAR)),
                Map.entry("USUARIOS:CRIAR", EnumSet.of(Permissao.USUARIO_GERENCIAR)),
                Map.entry("USUARIOS:EDITAR", EnumSet.of(Permissao.USUARIO_GERENCIAR)),
                Map.entry("USUARIOS:DESATIVAR", EnumSet.of(Permissao.USUARIO_GERENCIAR)),
                Map.entry("USUARIOS:ATRIBUIR_ROLE", EnumSet.of(Permissao.USUARIO_GERENCIAR)),
                Map.entry("USUARIOS:ATRIBUIR_PERMISSAO", EnumSet.of(Permissao.USUARIO_GERENCIAR)),
                Map.entry("AUDITORIA:VISUALIZAR_LOGS", EnumSet.of(Permissao.GOVERNANCA_CONSULTAR)),
                Map.entry("AUDITORIA:VISUALIZAR_ALTERACOES", EnumSet.of(Permissao.GOVERNANCA_CONSULTAR)),
                Map.entry("ROLES:VISUALIZAR", EnumSet.of(Permissao.GOVERNANCA_CONSULTAR)));
    }
}
