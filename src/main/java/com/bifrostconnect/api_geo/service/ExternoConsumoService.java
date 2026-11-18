package com.bifrostconnect.api_geo.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ExternoConsumoService {

    private static final int TAMANHO_MAXIMO_PAGINA = 100;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ExternoConsumoService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // GET /v1/externo/catalogo
    @Transactional(readOnly = true)
    public Map<String, Object> listarCatalogoPublicado(
            Long conjuntoId,
            String anoSafra,
            int page,
            int size) {

        if (page < 0 || size < 1 || size > TAMANHO_MAXIMO_PAGINA) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Paginação inválida: page >= 0 e size entre 1 e " + TAMANHO_MAXIMO_PAGINA + ".");
        }

        StringBuilder where = new StringBuilder(" WHERE p.versao_fechada = TRUE ");
        List<Object> args = new ArrayList<>();

        if (conjuntoId != null) {
            where.append(" AND p.conjunto_id = ? ");
            args.add(conjuntoId);
        }

        if (anoSafra != null && !anoSafra.isBlank()) {
            where.append(" AND p.ano_safra = ? ");
            args.add(anoSafra.trim());
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM processo p " + where,
                Long.class,
                args.toArray());

        long totalItems = total == null ? 0L : total;

        List<Object> argsPagina = new ArrayList<>(args);
        argsPagina.add(size);
        argsPagina.add((long) page * size);

        var bases = jdbc.queryForList("""
                SELECT p.id,
                       c.id AS conjunto_id,
                       c.nome AS conjunto,
                       o.id AS orgao_id,
                       o.nome AS orgao,
                       p.ano_safra,
                       p.versao_oficial,
                       p.versao_fechada,
                       p.data_publicacao,
                       p.epsg_destino AS epsg
                  FROM processo p
                  JOIN conjunto c ON c.id = p.conjunto_id
                  JOIN orgao o ON o.id = p.orgao_id
                """ + where + " ORDER BY p.data_publicacao DESC, p.id DESC LIMIT ? OFFSET ?",
                argsPagina.toArray());

        Map<String, Object> resposta = new LinkedHashMap<>();
        resposta.put("content", bases);
        resposta.put("page", page);
        resposta.put("size", size);
        resposta.put("totalElements", totalItems);
        resposta.put("totalPages", (int) ((totalItems + size - 1) / size));
        return resposta;
    }

    // GET /v1/externo/bases/{id}/metricas
    @Transactional(readOnly = true)
    public Map<String, Object> buscarMetricasBase(Long processoId) {
        Map<String, Object> metadados = exigirBasePublicada(processoId);

        Map<String, Object> totaisFeicoes = jdbc.queryForMap("""
                SELECT COUNT(fc.id) AS total_feicoes,
                       COALESCE(ROUND((SUM(ST_Area(fc.geom::geography)) / 10000)::numeric, 4), 0) AS area_total_hectares
                  FROM feicao_carga fc
                  JOIN arquivo_processado ap ON ap.id = fc.arquivo_processado_id
                 WHERE ap.processo_id = ?
                   AND ap.id = (
                       SELECT MAX(ap2.id)
                         FROM arquivo_processado ap2
                        WHERE ap2.processo_id = ?
                   )
                """, processoId, processoId);

        var sobreposicoesPorCamada = jdbc.queryForList("""
                SELECT fonte_camada,
                       COUNT(*) AS quantidade_ocorrencias,
                       COALESCE(ROUND(SUM(hectares_sobrepostos)::numeric, 4), 0) AS hectares_sobrepostos
                  FROM resultados_calculos
                 WHERE processo_id = ?
                 GROUP BY fonte_camada
                 ORDER BY fonte_camada
                """, processoId);

        Map<String, Object> resposta = new LinkedHashMap<>(metadados);
        resposta.put("metricas", totaisFeicoes);
        resposta.put("sobreposicoes_por_camada", sobreposicoesPorCamada);
        return resposta;
    }

    // GET /v1/externo/bases/{id}/geojson
    @Transactional(readOnly = true)
    public Map<String, Object> exportarGeoJsonBase(Long processoId) {
        exigirBasePublicada(processoId);

        String geoJsonTexto = jdbc.queryForObject("""
                SELECT jsonb_build_object(
                    'type', 'FeatureCollection',
                    'features', COALESCE(
                        jsonb_agg(
                            jsonb_build_object(
                                'type', 'Feature',
                                'id', fc.id,
                                'geometry', ST_AsGeoJSON(fc.geom)::jsonb,
                                'properties', jsonb_build_object(
                                    'id', fc.id,
                                    'codigo_imovel', fc.codigo_imovel,
                                    'area_hectares', ROUND((ST_Area(fc.geom::geography) / 10000)::numeric, 4)
                                )
                            )
                        ),
                        '[]'::jsonb
                    )
                )::text
                  FROM feicao_carga fc
                  JOIN arquivo_processado ap ON ap.id = fc.arquivo_processado_id
                 WHERE ap.processo_id = ?
                   AND ap.id = (
                       SELECT MAX(ap2.id)
                         FROM arquivo_processado ap2
                        WHERE ap2.processo_id = ?
                   )
                """, String.class, processoId, processoId);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> featureCollection = objectMapper.readValue(geoJsonTexto, Map.class);
            return featureCollection;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar GeoJSON da base publicada.", e);
        }
    }

    private Map<String, Object> exigirBasePublicada(Long processoId) {
        var linhas = jdbc.queryForList("""
                SELECT p.id,
                       c.nome AS conjunto,
                       o.nome AS orgao,
                       p.ano_safra,
                       p.versao_oficial,
                       p.versao_fechada,
                       p.data_publicacao
                  FROM processo p
                  JOIN conjunto c ON c.id = p.conjunto_id
                  JOIN orgao o ON o.id = p.orgao_id
                 WHERE p.id = ?
                """, processoId);

        if (linhas.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Base não encontrada.");
        }

        Map<String, Object> base = linhas.get(0);
        if (!Boolean.TRUE.equals(base.get("versao_fechada"))) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Acesso negado: apenas bases com versão oficial publicada estão disponíveis para consumo externo.");
        }

        return base;
    }
}