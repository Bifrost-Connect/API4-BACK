package com.bifrostconnect.api_geo.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ComparacaoService {

    private static final int SRID_GEOGRAFICO = 4326;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ComparacaoService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Tarefa 12:
     * Lista as safras publicadas de um conjunto que possuem
     * interseção espacial com a geometria de referência informada.
     *
     * O parâmetro conjunto aceita o ID ou o nome do conjunto.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarSafrasElegiveis(
            String conjunto,
            Long idRegiao) {

        if (conjunto == null || conjunto.isBlank()) {
            throw erro(
                    HttpStatus.BAD_REQUEST,
                    "O parâmetro 'conjunto' é obrigatório.");
        }

        if (idRegiao == null) {
            throw erro(
                    HttpStatus.BAD_REQUEST,
                    "O parâmetro 'regiao' é obrigatório.");
        }

        validarRegiao(idRegiao);

        String sql = """
            SELECT
                p.ano_safra AS safra,
                p.conjunto_id AS conjunto_id,
                c.nome AS conjunto,
                COUNT(DISTINCT p.id) AS quantidade_cargas,
                MAX(p.data_publicacao) AS ultima_publicacao
            FROM processo p
            JOIN conjunto c
                ON c.id = p.conjunto_id
            JOIN vw_copia_vigente cv
                ON cv.processo_id = p.id
            JOIN feicao_carga fc
                ON fc.arquivo_processado_id = cv.arquivo_processado_id
            JOIN camada_referencia regiao
                ON regiao.id = ?
            WHERE p.versao_fechada = TRUE
              AND (
                    CAST(p.conjunto_id AS TEXT) = ?
                    OR LOWER(c.nome) = LOWER(?)
              )
              AND fc.geom && regiao.geom
              AND ST_Intersects(fc.geom, regiao.geom)
            GROUP BY
                p.ano_safra,
                p.conjunto_id,
                c.nome
            ORDER BY p.ano_safra DESC NULLS LAST
            """;

        return jdbcTemplate.queryForList(
                sql,
                idRegiao,
                conjunto.trim(),
                conjunto.trim());
    }

    /**
     * Tarefa 13:
     * Retorna duas FeatureCollections GeoJSON recortadas pela
     * mesma região ou bounding box.
     *
     * Os IDs de carga correspondem aos IDs de processo.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> compararCamadas(
            Long idCargaA,
            Long idCargaB,
            Long idRegiao,
            String bbox) {

        if (idCargaA == null || idCargaB == null) {
            throw erro(
                    HttpStatus.BAD_REQUEST,
                    "Informe id_carga_a e id_carga_b.");
        }

        if (idCargaA.equals(idCargaB)) {
            throw erro(
                    HttpStatus.BAD_REQUEST,
                    "As duas cargas devem ser diferentes.");
        }

        boolean temRegiao = idRegiao != null;
        boolean temBbox = bbox != null && !bbox.isBlank();

        if (temRegiao == temBbox) {
            throw erro(
                    HttpStatus.BAD_REQUEST,
                    "Informe exatamente um parâmetro: id_regiao ou bbox.");
        }

        validarCargaPublicada(idCargaA);
        validarCargaPublicada(idCargaB);

        String cteJanela;
        List<Object> parametros = new ArrayList<>();

        Map<String, Object> janelaResposta = new LinkedHashMap<>();

        if (temRegiao) {
            validarRegiao(idRegiao);

            cteJanela = """
                WITH janela AS (
                    SELECT geom
                    FROM camada_referencia
                    WHERE id = ?
                )
                """;

            parametros.add(idRegiao);

            janelaResposta.put("tipo", "regiao");
            janelaResposta.put("idRegiao", idRegiao);

        } else {
            double[] coordenadas = interpretarBbox(bbox);

            cteJanela = """
                WITH janela AS (
                    SELECT ST_Transform(
                        ST_MakeEnvelope(?, ?, ?, ?, 4326),
                        4674
                    ) AS geom
                )
                """;

            for (double coordenada : coordenadas) {
                parametros.add(coordenada);
            }

            janelaResposta.put("tipo", "bbox");
            janelaResposta.put("bbox", bbox.trim());
            janelaResposta.put("sridEntrada", SRID_GEOGRAFICO);
        }

        parametros.add(idCargaA);
        parametros.add(idCargaB);

        String sql = cteJanela + """
            , cargas AS (
                SELECT
                    p.id AS carga_id,
                    p.ano_safra,
                    p.conjunto_id,
                    c.nome AS conjunto,
                    o.nome AS orgao,
                    p.versao_oficial,
                    cv.arquivo_processado_id
                FROM processo p
                JOIN vw_copia_vigente cv
                    ON cv.processo_id = p.id
                JOIN conjunto c
                    ON c.id = p.conjunto_id
                LEFT JOIN orgao o
                    ON o.id = p.orgao_id
                WHERE p.id IN (?, ?)
                  AND p.versao_fechada = TRUE
            ),
            recortadas AS (
                SELECT
                    cargas.carga_id,
                    fc.id AS feicao_id,
                    fc.codigo_imovel,
                    cargas.ano_safra,
                    cargas.conjunto_id,
                    cargas.conjunto,
                    cargas.orgao,
                    cargas.versao_oficial,
                    ST_Intersection(
                        fc.geom,
                        janela.geom
                    ) AS geom_recortada
                FROM cargas
                JOIN feicao_carga fc
                    ON fc.arquivo_processado_id =
                       cargas.arquivo_processado_id
                CROSS JOIN janela
                WHERE fc.geom && janela.geom
                  AND ST_Intersects(fc.geom, janela.geom)
            )
            SELECT
                carga_id,
                json_build_object(
                    'type', 'Feature',
                    'geometry',
                    ST_AsGeoJSON(
                        ST_Transform(geom_recortada, 4326),
                        6
                    )::json,
                    'properties',
                    json_build_object(
                        'id', feicao_id,
                        'idCarga', carga_id,
                        'codigoImovel', codigo_imovel,
                        'anoSafra', ano_safra,
                        'conjuntoId', conjunto_id,
                        'conjunto', conjunto,
                        'orgao', orgao,
                        'versaoOficial', versao_oficial
                    )
                )::text AS feature_json
            FROM recortadas
            WHERE NOT ST_IsEmpty(geom_recortada)
            ORDER BY carga_id, feicao_id
            """;

        List<FeatureLinha> linhas = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new FeatureLinha(
                        rs.getLong("carga_id"),
                        rs.getString("feature_json")),
                parametros.toArray());

        List<Map<String, Object>> featuresA = new ArrayList<>();
        List<Map<String, Object>> featuresB = new ArrayList<>();

        for (FeatureLinha linha : linhas) {
            Map<String, Object> feature;

            try {
                feature = objectMapper.readValue(
                        linha.featureJson(),
                        new TypeReference<Map<String, Object>>() {
                        });

            } catch (Exception e) {
                throw new IllegalStateException(
                        "Não foi possível interpretar uma feição GeoJSON.",
                        e);
            }

            if (linha.cargaId().equals(idCargaA)) {
                featuresA.add(feature);

            } else if (linha.cargaId().equals(idCargaB)) {
                featuresB.add(feature);
            }
        }

        Map<String, Object> resposta = new LinkedHashMap<>();

        resposta.put("idCargaA", idCargaA);
        resposta.put("idCargaB", idCargaB);
        resposta.put("janela", janelaResposta);
        resposta.put("cargaA", featureCollection(featuresA));
        resposta.put("cargaB", featureCollection(featuresB));

        return resposta;
    }

    private void validarRegiao(Long idRegiao) {
        Long quantidade = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM camada_referencia
                WHERE id = ?
                """,
                Long.class,
                idRegiao);

        if (quantidade == null || quantidade == 0) {
            throw erro(
                    HttpStatus.NOT_FOUND,
                    "Não foi encontrada geometria de referência para o id_regiao informado.");
        }
    }

    private void validarCargaPublicada(Long idCarga) {
        Long existe = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM processo
                WHERE id = ?
                """,
                Long.class,
                idCarga);

        if (existe == null || existe == 0) {
            throw erro(
                    HttpStatus.NOT_FOUND,
                    "A carga " + idCarga + " não foi encontrada.");
        }

        Long publicada = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM processo p
                JOIN vw_copia_vigente cv
                    ON cv.processo_id = p.id
                WHERE p.id = ?
                  AND p.versao_fechada = TRUE
                """,
                Long.class,
                idCarga);

        if (publicada == null || publicada == 0) {
            throw erro(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "A carga " + idCarga
                            + " não possui versão publicada elegível para comparação.");
        }
    }

    private double[] interpretarBbox(String bbox) {
        String[] partes = bbox.split(",");

        if (partes.length != 4) {
            throw erro(
                    HttpStatus.BAD_REQUEST,
                    "O bbox deve seguir o formato minLon,minLat,maxLon,maxLat.");
        }

        double[] valores = new double[4];

        try {
            for (int i = 0; i < partes.length; i++) {
                valores[i] = Double.parseDouble(partes[i].trim());

                if (!Double.isFinite(valores[i])) {
                    throw new NumberFormatException();
                }
            }

        } catch (NumberFormatException e) {
            throw erro(
                    HttpStatus.BAD_REQUEST,
                    "As quatro coordenadas do bbox devem ser números válidos.");
        }

        double minLon = valores[0];
        double minLat = valores[1];
        double maxLon = valores[2];
        double maxLat = valores[3];

        if (minLon < -180
                || maxLon > 180
                || minLat < -90
                || maxLat > 90
                || minLon >= maxLon
                || minLat >= maxLat) {

            throw erro(
                    HttpStatus.BAD_REQUEST,
                    "O bbox possui limites geográficos inválidos ou fora de ordem.");
        }

        return valores;
    }

    private Map<String, Object> featureCollection(
            List<Map<String, Object>> features) {

        Map<String, Object> collection = new LinkedHashMap<>();

        collection.put("type", "FeatureCollection");
        collection.put("features", features);

        return collection;
    }

    private ResponseStatusException erro(
            HttpStatus status,
            String mensagem) {

        return new ResponseStatusException(status, mensagem);
    }

    private record FeatureLinha(
            Long cargaId,
            String featureJson) {
    }
}