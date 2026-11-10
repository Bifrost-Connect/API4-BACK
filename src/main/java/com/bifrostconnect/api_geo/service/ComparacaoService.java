package com.bifrostconnect.api_geo.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bifrostconnect.api_geo.dto.EvolucaoResponseDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ComparacaoService {

    private static final int SRID_GEOGRAFICO = 4326;

    // feições das duas cargas que caem dentro da janela, pela cópia vigente.
    // Usado na contagem de vértices e na checagem de sobreposição
    private static final String FEICOES_NA_JANELA = """
            FROM vw_copia_vigente cv
            JOIN feicao_carga fc
                ON fc.arquivo_processado_id = cv.arquivo_processado_id
            CROSS JOIN janela
            WHERE cv.processo_id IN (?, ?)
              AND fc.geom && janela.geom
              AND ST_Intersects(fc.geom, janela.geom)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    // Tarefa 18: acima de limiteSimplificacao vértices a geometria volta simplificada,
    // acima de limiteMaximo nem tenta (413). Tolerância em graus do SIRGAS 2000 (~11 m)
    private final long limiteSimplificacaoVertices;
    private final long limiteMaximoVertices;
    private final double toleranciaSimplificacao;

    public ComparacaoService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            @Value("${comparacao.payload.limite-simplificacao-vertices:50000}") long limiteSimplificacaoVertices,
            @Value("${comparacao.payload.limite-maximo-vertices:500000}") long limiteMaximoVertices,
            @Value("${comparacao.payload.tolerancia-simplificacao:0.0001}") double toleranciaSimplificacao) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.limiteSimplificacaoVertices = limiteSimplificacaoVertices;
        this.limiteMaximoVertices = limiteMaximoVertices;
        this.toleranciaSimplificacao = toleranciaSimplificacao;
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

        validarParDeCargas(idCargaA, idCargaB);

        boolean temRegiao = idRegiao != null;
        boolean temBbox = bbox != null && !bbox.isBlank();

        if (temRegiao == temBbox) {
            throw erro(
                    HttpStatus.BAD_REQUEST,
                    "Informe exatamente um parâmetro: id_regiao ou bbox.");
        }

        Janela janela = montarJanela(idRegiao, bbox, idCargaA, idCargaB);

        validarSobreposicao(janela, idCargaA, idCargaB);
        Double tolerancia = toleranciaParaPayload(janela, idCargaA, idCargaB);

        List<Object> parametros = new ArrayList<>(janela.parametros());
        parametros.add(idCargaA);
        parametros.add(idCargaB);

        String sql = janela.cte() + """
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
                        ST_Transform(""" + simplificar("geom_recortada", tolerancia) + """
            , 4326),
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
            Map<String, Object> feature = lerJson(linha.featureJson());

            if (linha.cargaId().equals(idCargaA)) {
                featuresA.add(feature);

            } else if (linha.cargaId().equals(idCargaB)) {
                featuresB.add(feature);
            }
        }

        Map<String, Object> resposta = new LinkedHashMap<>();

        resposta.put("idCargaA", idCargaA);
        resposta.put("idCargaB", idCargaB);
        resposta.put("janela", janela.resposta());
        resposta.put("simplificada", tolerancia != null);
        resposta.put("cargaA", featureCollection(featuresA));
        resposta.put("cargaB", featureCollection(featuresB));

        return resposta;
    }

    // Tarefa 15/19: evolução entre duas safras na mesma janela.
    // Mesma conta do MotorGeomatematicoService, só que rodando no PostGIS:
    //   aumento (NOVO_ALERTA)    = B - A
    //   redução (REDUCAO_ALERTA) = A - B
    //   mantido (MANTIDO)        = A ∩ B
    // As áreas saem sempre da geometria original (geography, em m²);
    // a simplificação da Tarefa 18 só mexe no GeoJSON devolvido.
    // Sem região nem bbox a janela vira o envelope das duas cargas juntas.
    @Transactional(readOnly = true)
    public EvolucaoResponseDTO calcularEvolucao(
            Long idCargaA,
            Long idCargaB,
            Long idRegiao,
            String bbox) {

        validarParDeCargas(idCargaA, idCargaB);

        if (idRegiao != null && bbox != null && !bbox.isBlank()) {
            throw erro(
                    HttpStatus.BAD_REQUEST,
                    "Informe só um parâmetro: id_regiao ou bbox.");
        }

        Janela janela = montarJanela(idRegiao, bbox, idCargaA, idCargaB);

        validarSobreposicao(janela, idCargaA, idCargaB);
        Double tolerancia = toleranciaParaPayload(janela, idCargaA, idCargaB);

        List<Object> parametros = new ArrayList<>(janela.parametros());
        parametros.add(idCargaA);
        parametros.add(idCargaB);

        String sql = janela.cte() + """
            , recortes AS (
                SELECT
                    cv.processo_id,
                    ST_UnaryUnion(ST_Collect(
                        ST_CollectionExtract(ST_Intersection(fc.geom, janela.geom), 3)
                    )) AS geom
                FROM vw_copia_vigente cv
                JOIN feicao_carga fc
                    ON fc.arquivo_processado_id = cv.arquivo_processado_id
                CROSS JOIN janela
                WHERE cv.processo_id IN (?, ?)
                  AND fc.geom && janela.geom
                  AND ST_Intersects(fc.geom, janela.geom)
                GROUP BY cv.processo_id
            ),
            classes AS (
                SELECT
                    a.geom AS geom_a,
                    b.geom AS geom_b,
                    ST_CollectionExtract(ST_Difference(b.geom, a.geom), 3) AS aumento,
                    ST_CollectionExtract(ST_Difference(a.geom, b.geom), 3) AS reducao,
                    ST_CollectionExtract(ST_Intersection(a.geom, b.geom), 3) AS mantido
                FROM recortes a
                JOIN recortes b
                    ON a.processo_id = ? AND b.processo_id = ?
            )
            SELECT
                ST_Area(geom_a::geography) / 10000 AS area_a,
                ST_Area(geom_b::geography) / 10000 AS area_b,
                ST_Area(aumento::geography) / 10000 AS area_aumento,
                ST_Area(reducao::geography) / 10000 AS area_reducao,
                ST_Area(mantido::geography) / 10000 AS area_mantido,
                (
                    SELECT COALESCE(json_agg(json_build_object(
                        'type', 'Feature',
                        'geometry', ST_AsGeoJSON(ST_Transform(""" + simplificar("c.geom", tolerancia) + """
            , 4326), 6)::json,
                        'properties', json_build_object(
                            'classe', c.classe,
                            'areaHectares', ROUND((ST_Area(c.geom::geography) / 10000)::numeric, 4)
                        )
                    ) ORDER BY c.ordem), '[]'::json)::text
                    FROM (VALUES
                        (1, 'NOVO_ALERTA', aumento),
                        (2, 'REDUCAO_ALERTA', reducao),
                        (3, 'MANTIDO', mantido)
                    ) AS c(ordem, classe, geom)
                    WHERE NOT ST_IsEmpty(c.geom)
                ) AS features_json
            FROM classes
            """;

        parametros.add(idCargaA);
        parametros.add(idCargaB);

        return jdbcTemplate.queryForObject(
                sql,
                (rs, rowNum) -> {
                    double areaA = rs.getDouble("area_a");
                    double areaB = rs.getDouble("area_b");
                    double saldo = areaB - areaA;
                    double percentual = areaA == 0 ? 100.0 : saldo / areaA * 100.0;

                    Map<String, Object> collection = new LinkedHashMap<>();
                    collection.put("type", "FeatureCollection");
                    collection.put("features", lerJsonLista(rs.getString("features_json")));

                    String geoJson;
                    try {
                        geoJson = objectMapper.writeValueAsString(collection);
                    } catch (Exception e) {
                        throw new IllegalStateException("Não foi possível montar o GeoJSON classificado.", e);
                    }

                    return new EvolucaoResponseDTO(
                            arredondar(areaA, 4),
                            arredondar(areaB, 4),
                            arredondar(saldo, 4),
                            arredondar(percentual, 2),
                            arredondar(rs.getDouble("area_aumento"), 4),
                            arredondar(rs.getDouble("area_reducao"), 4),
                            arredondar(rs.getDouble("area_mantido"), 4),
                            tolerancia != null,
                            geoJson);
                },
                parametros.toArray());
    }

    // Tarefa 17: regras que valem pra qualquer comparação de duas cargas.
    // Ordem: mesma carga (400) -> existe (404) -> publicada (403) -> mesmo conjunto (400)
    private void validarParDeCargas(Long idCargaA, Long idCargaB) {
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

        validarCargaPublicada(idCargaA);
        validarCargaPublicada(idCargaB);

        Long conjuntos = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(DISTINCT conjunto_id)
                FROM processo
                WHERE id IN (?, ?)
                """,
                Long.class,
                idCargaA,
                idCargaB);

        if (conjuntos != null && conjuntos > 1) {
            throw erro(
                    HttpStatus.BAD_REQUEST,
                    "As cargas pertencem a conjuntos temáticos diferentes e não podem ser comparadas.");
        }
    }

    private Janela montarJanela(Long idRegiao, String bbox, Long idCargaA, Long idCargaB) {
        Map<String, Object> resposta = new LinkedHashMap<>();

        if (idRegiao != null) {
            validarRegiao(idRegiao);

            resposta.put("tipo", "regiao");
            resposta.put("idRegiao", idRegiao);

            return new Janela(
                    """
                    WITH janela AS (
                        SELECT geom
                        FROM camada_referencia
                        WHERE id = ?
                    )
                    """,
                    List.of(idRegiao),
                    resposta);
        }

        if (bbox != null && !bbox.isBlank()) {
            double[] coordenadas = interpretarBbox(bbox);

            resposta.put("tipo", "bbox");
            resposta.put("bbox", bbox.trim());
            resposta.put("sridEntrada", SRID_GEOGRAFICO);

            return new Janela(
                    """
                    WITH janela AS (
                        SELECT ST_Transform(
                            ST_MakeEnvelope(?, ?, ?, ?, 4326),
                            4674
                        ) AS geom
                    )
                    """,
                    List.of(coordenadas[0], coordenadas[1], coordenadas[2], coordenadas[3]),
                    resposta);
        }

        resposta.put("tipo", "extensao_total");

        return new Janela(
                """
                WITH janela AS (
                    SELECT ST_Envelope(ST_Collect(fc.geom)) AS geom
                    FROM vw_copia_vigente cv
                    JOIN feicao_carga fc
                        ON fc.arquivo_processado_id = cv.arquivo_processado_id
                    WHERE cv.processo_id IN (?, ?)
                )
                """,
                List.of(idCargaA, idCargaB),
                resposta);
    }

    // Tarefa 17: 422 quando as duas safras não se sobrepõem dentro da janela.
    // Compara a cobertura (envelope das feições de cada carga na janela), não os
    // polígonos em si: alerta 100% novo em B ainda é comparação válida
    private void validarSobreposicao(Janela janela, Long idCargaA, Long idCargaB) {
        List<Object> parametros = new ArrayList<>(janela.parametros());
        parametros.add(idCargaA);
        parametros.add(idCargaB);
        parametros.add(idCargaA);
        parametros.add(idCargaB);

        Boolean sobrepoe = jdbcTemplate.queryForObject(
                janela.cte() + """
                , cobertura AS (
                    SELECT cv.processo_id,
                           ST_SetSRID(ST_Extent(fc.geom)::geometry, 4674) AS geom
                """ + FEICOES_NA_JANELA + """
                    GROUP BY cv.processo_id
                )
                SELECT EXISTS (
                    SELECT 1
                    FROM cobertura a
                    JOIN cobertura b
                        ON a.processo_id = ? AND b.processo_id = ?
                    CROSS JOIN janela
                    WHERE ST_Area(ST_Intersection(
                        ST_Intersection(a.geom, b.geom),
                        ST_Envelope(janela.geom)
                    )) > 0
                )
                """,
                Boolean.class,
                parametros.toArray());

        if (!Boolean.TRUE.equals(sobrepoe)) {
            throw erro(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "As safras selecionadas não possuem sobreposição geográfica na região solicitada.");
        }
    }

    // Tarefa 18: conta os vértices que iriam no payload e decide se simplifica
    // (devolve a tolerância), se manda como está (null) ou se recusa com 413
    private Double toleranciaParaPayload(Janela janela, Long idCargaA, Long idCargaB) {
        List<Object> parametros = new ArrayList<>(janela.parametros());
        parametros.add(idCargaA);
        parametros.add(idCargaB);

        Long vertices = jdbcTemplate.queryForObject(
                janela.cte() + "SELECT COALESCE(SUM(ST_NPoints(fc.geom)), 0) " + FEICOES_NA_JANELA,
                Long.class,
                parametros.toArray());

        long total = vertices == null ? 0 : vertices;

        if (total > limiteMaximoVertices) {
            throw erro(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "O recorte selecionado tem " + total + " vértices e passa do limite seguro de "
                            + limiteMaximoVertices + ". Selecione um município ou aproxime o zoom do mapa.");
        }

        return total > limiteSimplificacaoVertices ? toleranciaSimplificacao : null;
    }

    private String simplificar(String expressao, Double tolerancia) {
        if (tolerancia == null) {
            return expressao;
        }

        // toPlainString pra não cair em notação científica (1.0E-4) dentro do SQL
        return "ST_SimplifyPreserveTopology(" + expressao + ", "
                + BigDecimal.valueOf(tolerancia).toPlainString() + ")";
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
        List<Boolean> fechada = jdbcTemplate.queryForList(
                """
                SELECT versao_fechada
                FROM processo
                WHERE id = ?
                """,
                Boolean.class,
                idCarga);

        if (fechada.isEmpty()) {
            throw erro(
                    HttpStatus.NOT_FOUND,
                    "A carga " + idCarga + " não foi encontrada.");
        }

        // Tarefa 17: carga que ainda não foi publicada não entra em comparação nenhuma
        if (!Boolean.TRUE.equals(fechada.get(0))) {
            throw erro(
                    HttpStatus.FORBIDDEN,
                    "A carga " + idCarga + " ainda não foi publicada e não pode ser comparada.");
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

    private Map<String, Object> lerJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Não foi possível interpretar uma feição GeoJSON.",
                    e);
        }
    }

    private List<Object> lerJsonLista(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Não foi possível interpretar as feições GeoJSON.",
                    e);
        }
    }

    private Map<String, Object> featureCollection(
            List<Map<String, Object>> features) {

        Map<String, Object> collection = new LinkedHashMap<>();

        collection.put("type", "FeatureCollection");
        collection.put("features", features);

        return collection;
    }

    private static double arredondar(double valor, int casas) {
        double fator = Math.pow(10, casas);
        return Math.round(valor * fator) / fator;
    }

    private ResponseStatusException erro(
            HttpStatus status,
            String mensagem) {

        return new ResponseStatusException(status, mensagem);
    }

    private record Janela(
            String cte,
            List<Object> parametros,
            Map<String, Object> resposta) {
    }

    private record FeatureLinha(
            Long cargaId,
            String featureJson) {
    }
}
