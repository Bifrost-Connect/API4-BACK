package com.bifrostconnect.api_geo.service;

import com.bifrostconnect.api_geo.exception.BaseIndisponivelException;
import com.bifrostconnect.api_geo.exception.RecursoNaoEncontradoException;
import com.bifrostconnect.api_geo.exception.RequisicaoInvalidaException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CatalogoBasesService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public static final int TAMANHO_PADRAO = 20;
    public static final int TAMANHO_MAXIMO = 100;

    private static final String FROM_CATALOGO = " FROM vw_catalogo_bases_operacional v ";

    // ordenação vem do front, então só aceita o que está aqui (nada de concatenar texto livre no SQL)
    private static final Map<String, String> ORDENACOES = Map.of(
            "data_publicacao", "v.data_publicacao",
            "data_criacao", "v.data_criacao",
            "ano_safra", "v.ano_safra",
            "conjunto", "v.conjunto",
            "orgao", "v.orgao",
            "situacao", "v.status_operacional",
            "id", "v.id");

    private static final Set<String> CAMPOS_RESUMO_ADMINISTRATIVO = Set.of(
            "id", "conjunto_id", "conjunto", "ano_safra", "orgao_id", "orgao", "orgao_sigla",
            "etapa_atual", "situacao", "status_operacional", "pronta_para_consulta");

    public record FiltroCatalogo(
            String busca,
            Long conjuntoId,
            String ano,
            Long orgaoId,
            String classificacao,
            String situacao,
            boolean apenasDisponiveis) {
    }

    public Map<String, Object> listarBases(FiltroCatalogo filtro, int page, int size, String sort, String direcao) {
        validarPaginacao(page, size);
        String ordem = montarOrdenacao(sort, direcao);

        List<Object> params = new ArrayList<>();
        String where = montarWhere(filtro, params);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*)" + FROM_CATALOGO + where, Long.class, params.toArray());

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add(size);
        paramsPagina.add((long) page * size);

        List<Map<String, Object>> linhas = jdbcTemplate.queryForList(
                "SELECT v.*, " + SUBQUERY_TOTAL_FEICOES + FROM_CATALOGO + where
                        + " ORDER BY " + ordem + " LIMIT ? OFFSET ?",
                paramsPagina.toArray());

        List<Map<String, Object>> itens = linhas.stream().map(this::sanitizar).toList();
        return pagina(itens, page, size, total == null ? 0 : total);
    }

    // ficha resumida de uma base (o que a listagem mostra pra ela). 404 só se o ID não existir
    public Map<String, Object> buscarBase(Long idBase) {
        return sanitizar(buscarLinhaCatalogo(idBase));
    }

    public Map<String, Object> buscarDetalhes(Long idBase, int page, int size) {
        validarPaginacao(page, size);
        Map<String, Object> base = buscarLinhaCatalogo(idBase);

        if (!Boolean.TRUE.equals(base.get("pronta_para_consulta"))) {
            throw new BaseIndisponivelException(
                    "A base " + idBase + " ainda não foi publicada e não está liberada para consulta.",
                    (String) base.get("etapa_atual"),
                    (String) base.get("situacao"));
        }

        Map<String, Object> detalhes = new LinkedHashMap<>();
        detalhes.put("ficha_tecnica", base);
        detalhes.put("resumo_areas", resumoAreas(idBase));
        detalhes.put("amostra_registros", amostraRegistros(idBase, page, size));
        return detalhes;
    }

    public Map<String, Object> resumo() {
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + FROM_CATALOGO, Long.class);

        List<Map<String, Object>> porStatus = jdbcTemplate.queryForList(
                "SELECT v.status_operacional, COUNT(*) AS total" + FROM_CATALOGO
                        + " GROUP BY v.status_operacional ORDER BY v.status_operacional");

        List<Map<String, Object>> porConjunto = jdbcTemplate.queryForList(
                "SELECT v.conjunto_id, v.conjunto, COUNT(*) AS total,"
                        + " COUNT(*) FILTER (WHERE v.pronta_para_consulta) AS disponiveis,"
                        + " COUNT(*) FILTER (WHERE v.safra_vigente) AS vigentes"
                        + FROM_CATALOGO
                        + " GROUP BY v.conjunto_id, v.conjunto ORDER BY v.conjunto");

        Map<String, Object> resumo = new LinkedHashMap<>();
        resumo.put("total_bases", total == null ? 0 : total);
        resumo.put("por_status", porStatus);
        resumo.put("por_conjunto", porConjunto);
        return resumo;
    }

    public Map<String, Object> opcoesFiltros() {
        Map<String, Object> opcoes = new LinkedHashMap<>();
        opcoes.put("orgaos", jdbcTemplate.queryForList(
                "SELECT id, nome, sigla FROM orgao WHERE ativo ORDER BY nome"));
        opcoes.put("conjuntos", jdbcTemplate.queryForList(
                "SELECT id, nome FROM conjunto WHERE ativo ORDER BY nome"));
        opcoes.put("anos", jdbcTemplate.queryForList(
                "SELECT DISTINCT ano_safra FROM processo WHERE ano_safra IS NOT NULL ORDER BY ano_safra DESC",
                String.class));
        opcoes.put("classificacoes", jdbcTemplate.queryForList(
                "SELECT DISTINCT classificacao_acesso FROM processo"
                        + " WHERE classificacao_acesso IS NOT NULL ORDER BY classificacao_acesso",
                String.class));
        opcoes.put("situacoes", jdbcTemplate.queryForList(
                "SELECT DISTINCT status_operacional" + FROM_CATALOGO + "ORDER BY status_operacional",
                String.class));
        return opcoes;
    }

    // conta feições só da cópia vigente (V26), e só quando a base está pronta: pra base em
    // andamento esse número nem é mostrado
    private static final String SUBQUERY_TOTAL_FEICOES = """
            CASE WHEN v.pronta_para_consulta THEN
                (SELECT COUNT(*) FROM feicao_carga fc
                   JOIN vw_copia_vigente cv ON cv.arquivo_processado_id = fc.arquivo_processado_id
                  WHERE cv.processo_id = v.id)
            END AS total_feicoes
            """;

    private String montarWhere(FiltroCatalogo filtro, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");

        if (filtro.busca() != null && !filtro.busca().isBlank()) {
            String termo = "%" + escaparLike(filtro.busca().trim()) + "%";
            where.append(" AND (v.conjunto ILIKE ? OR v.orgao ILIKE ? OR v.orgao_sigla ILIKE ?"
                    + " OR v.ano_safra ILIKE ? OR v.cobertura_espacial ILIKE ? OR v.versao_oficial ILIKE ?)");
            for (int i = 0; i < 6; i++) {
                params.add(termo);
            }
        }
        if (filtro.conjuntoId() != null) {
            where.append(" AND v.conjunto_id = ?");
            params.add(filtro.conjuntoId());
        }
        if (filtro.ano() != null && !filtro.ano().isBlank()) {
            where.append(" AND v.ano_safra = ?");
            params.add(filtro.ano().trim());
        }
        if (filtro.orgaoId() != null) {
            where.append(" AND v.orgao_id = ?");
            params.add(filtro.orgaoId());
        }
        if (filtro.classificacao() != null && !filtro.classificacao().isBlank()) {
            where.append(" AND v.classificacao_acesso ILIKE ?");
            params.add(filtro.classificacao().trim());
        }
        if (filtro.situacao() != null && !filtro.situacao().isBlank()) {
            // aceita tanto o status do catálogo (DISPONIVEL, EM_QUARENTENA...) quanto a situação crua
            where.append(" AND (v.status_operacional = UPPER(?) OR v.situacao = UPPER(?))");
            params.add(filtro.situacao().trim());
            params.add(filtro.situacao().trim());
        }
        if (filtro.apenasDisponiveis()) {
            where.append(" AND v.pronta_para_consulta");
        }
        return where.toString();
    }

    private String montarOrdenacao(String sort, String direcao) {
        String campo = sort == null || sort.isBlank() ? "data_publicacao" : sort.trim().toLowerCase();
        String coluna = ORDENACOES.get(campo);
        if (coluna == null) {
            throw new RequisicaoInvalidaException(
                    "Ordenação inválida: " + sort + ". Use um destes: " + ORDENACOES.keySet());
        }

        String dir = direcao == null || direcao.isBlank() ? "desc" : direcao.trim().toLowerCase();
        if (!dir.equals("asc") && !dir.equals("desc")) {
            throw new RequisicaoInvalidaException("Direção inválida: " + direcao + ". Use asc ou desc.");
        }

        // id no final deixa a ordem estável entre as páginas quando tem empate
        return coluna + " " + dir + " NULLS LAST, v.id " + dir;
    }

    private void validarPaginacao(int page, int size) {
        if (page < 0 || size < 1 || size > TAMANHO_MAXIMO) {
            throw new RequisicaoInvalidaException(
                    "Paginação inválida. Use page >= 0 e size entre 1 e " + TAMANHO_MAXIMO + ".");
        }
    }

    private Map<String, Object> buscarLinhaCatalogo(Long idBase) {
        List<Map<String, Object>> resultado = jdbcTemplate.queryForList(
                "SELECT v.*, " + SUBQUERY_TOTAL_FEICOES + FROM_CATALOGO + " WHERE v.id = ?", idBase);

        if (resultado.isEmpty()) {
            throw new RecursoNaoEncontradoException("Base " + idBase + " não encontrada no catálogo.");
        }
        return resultado.get(0);
    }

    private Map<String, Object> sanitizar(Map<String, Object> linha) {
        if (Boolean.TRUE.equals(linha.get("pronta_para_consulta"))) {
            return linha;
        }
        Map<String, Object> resumo = new LinkedHashMap<>();
        linha.forEach((campo, valor) -> {
            if (CAMPOS_RESUMO_ADMINISTRATIVO.contains(campo)) {
                resumo.put(campo, valor);
            }
        });
        resumo.put("pronta_para_consulta", false);
        return resumo;
    }

    private Map<String, Object> resumoAreas(Long idBase) {
        Map<String, Object> areas = new LinkedHashMap<>(jdbcTemplate.queryForMap("""
                SELECT COUNT(fc.id)                                              AS total_feicoes,
                       COALESCE(ROUND((SUM(ST_Area(fc.geom::geography)) / 10000)::numeric, 4), 0) AS area_total_ha
                  FROM vw_copia_vigente cv
                  JOIN feicao_carga fc ON fc.arquivo_processado_id = cv.arquivo_processado_id
                 WHERE cv.processo_id = ?
                """, idBase));

        List<Map<String, Object>> porCamada = jdbcTemplate.queryForList("""
                SELECT rc.fonte_camada,
                       SUM(rc.hectares_sobrepostos)       AS hectares_sobrepostos,
                       COUNT(DISTINCT rc.codigo_imovel)   AS qtd_imoveis
                  FROM resultados_calculos rc
                  JOIN vw_copia_vigente cv ON cv.arquivo_processado_id = rc.arquivo_processado_id
                 WHERE cv.processo_id = ?
                 GROUP BY rc.fonte_camada
                 ORDER BY rc.fonte_camada
                """, idBase);

        areas.put("hectares_sobrepostos_total", porCamada.stream()
                .map(c -> (Number) c.get("hectares_sobrepostos"))
                .mapToDouble(n -> n == null ? 0 : n.doubleValue())
                .sum());
        areas.put("por_camada", porCamada);
        return areas;
    }

    // amostra dos atributos (sem geometria, que é do mapa da US08)
    private Map<String, Object> amostraRegistros(Long idBase, int page, int size) {
        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM feicao_carga fc
                  JOIN vw_copia_vigente cv ON cv.arquivo_processado_id = fc.arquivo_processado_id
                 WHERE cv.processo_id = ?
                """, Long.class, idBase);

        List<Map<String, Object>> registros = jdbcTemplate.queryForList("""
                SELECT fc.id AS feicao_id, fc.codigo_imovel, fc.atributos::text AS atributos
                  FROM feicao_carga fc
                  JOIN vw_copia_vigente cv ON cv.arquivo_processado_id = fc.arquivo_processado_id
                 WHERE cv.processo_id = ?
                 ORDER BY fc.id
                 LIMIT ? OFFSET ?
                """, idBase, size, (long) page * size);

        // jsonb chega como texto; devolve como objeto pra tela não precisar fazer parse
        registros.forEach(r -> r.put("atributos", lerJson((String) r.get("atributos"))));

        return pagina(registros, page, size, total == null ? 0 : total);
    }

    private Map<String, Object> pagina(List<Map<String, Object>> itens, int page, int size, long total) {
        Map<String, Object> pagina = new LinkedHashMap<>();
        pagina.put("items", itens);
        pagina.put("page", page);
        pagina.put("size", size);
        pagina.put("total_items", total);
        pagina.put("total_pages", (int) Math.ceil(total / (double) size));
        return pagina;
    }

    private Object lerJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    private String escaparLike(String termo) {
        return termo.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
