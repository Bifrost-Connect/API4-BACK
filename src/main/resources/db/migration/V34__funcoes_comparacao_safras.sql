-- V34: US10 — funções espaciais de comparação temporal entre safras (Tarefas 20 e 23)
--
-- Compara duas versões publicadas (A = anterior, B = nova) dentro de uma região e devolve
-- GANHO (B - A), PERDA (A - B) e PERMANENCIA (A ∩ B).
--  * Carga = processo. As geometrias vêm da cópia vigente (vw_copia_vigente) de cada carga.
--  * A sobreposição é feita na projeção de cálculo (fn_srid_calculo), não em graus (SRID 4674);
--    por isso as áreas saem em hectares e a soma das partes fecha com a área total.
--  * Cada safra é dissolvida (ST_UnaryUnion) antes da comparação: imóveis que se sobrepõem
--    dentro da mesma safra não contam a área duas vezes.
--  * Tarefa 23: toda saída passa por ST_CollectionExtract(..., 3) e COALESCE(..., 0), então
--    comparação sem mudança territorial devolve geometria vazia do tipo certo e área 0, nunca NULL.

-- Projeção usada nos cálculos de área (SIRGAS 2000 / Brazil Polyconic).
-- Se o back calcular hectares em outra projeção, mudar só aqui.
CREATE OR REPLACE FUNCTION fn_srid_calculo()
RETURNS INTEGER AS $$
    SELECT 5880;
$$ LANGUAGE sql IMMUTABLE;

-- Código da região (município) a partir dos atributos da camada de referência.
-- Tenta as chaves usuais da malha municipal do IBGE. Se a malha carregada usar outra
-- chave, ajustar só esta função (e recriar o índice por expressão da T22, se já existir).
CREATE OR REPLACE FUNCTION fn_codigo_municipio(p_atributos JSONB)
RETURNS VARCHAR AS $$
    SELECT COALESCE(
        p_atributos ->> 'CD_MUN',
        p_atributos ->> 'cd_mun',
        p_atributos ->> 'CD_GEOCMU',
        p_atributos ->> 'cd_geocmu',
        p_atributos ->> 'codigo_municipio',
        p_atributos ->> 'geocodigo',
        p_atributos ->> 'GEOCODIGO'
    )::VARCHAR;
$$ LANGUAGE sql IMMUTABLE;

-- Fica só com os polígonos (tipo 3), como MultiPolygon. NULL vira MultiPolygon vazio.
CREATE OR REPLACE FUNCTION fn_extrai_poligonos(p_geom geometry)
RETURNS geometry AS $$
    SELECT ST_Multi(
               ST_CollectionExtract(
                   COALESCE(p_geom, ST_GeomFromText('MULTIPOLYGON EMPTY', fn_srid_calculo())),
                   3));
$$ LANGUAGE sql IMMUTABLE;

-- Área da carga dentro da região: feições da cópia vigente que tocam a região, dissolvidas
-- e recortadas. Entrada: região em SRID 4674. Saída: MultiPolygon na projeção de cálculo.
CREATE OR REPLACE FUNCTION fn_geom_safra_regiao(p_processo_id BIGINT, p_regiao_4674 geometry)
RETURNS geometry AS $$
    SELECT fn_extrai_poligonos(
               ST_Intersection(u.g, ST_Transform(p_regiao_4674, fn_srid_calculo())))
    FROM (
        SELECT ST_UnaryUnion(
                   ST_Collect(
                       ST_CollectionExtract(
                           ST_MakeValid(ST_Transform(fc.geom, fn_srid_calculo())), 3))) AS g
        FROM vw_copia_vigente cv
        JOIN feicao_carga fc ON fc.arquivo_processado_id = cv.arquivo_processado_id
        WHERE cv.processo_id = p_processo_id
          AND fc.geom && p_regiao_4674
          AND ST_Intersects(fc.geom, p_regiao_4674)
    ) u;
$$ LANGUAGE sql STABLE;

-- Tarefa 20: polígonos de ganho, perda e permanência entre duas versões, numa região.
-- Sempre devolve 3 linhas (GANHO, PERDA, PERMANENCIA). geom em SRID 4674; area_ha em hectares.
CREATE OR REPLACE FUNCTION fn_comparar_safras_poligonos(
    p_processo_a    BIGINT,
    p_processo_b    BIGINT,
    p_codigo_regiao VARCHAR
)
RETURNS TABLE (tipo VARCHAR, geom geometry, area_ha DOUBLE PRECISION) AS $$
DECLARE
    v_regiao_4674 geometry;
    v_a           geometry;
    v_b           geometry;
BEGIN
    SELECT ST_UnaryUnion(ST_Collect(ST_CollectionExtract(ST_MakeValid(cr.geom), 3)))
      INTO v_regiao_4674
      FROM camada_referencia cr
     WHERE fn_codigo_municipio(cr.atributos) = p_codigo_regiao;

    IF v_regiao_4674 IS NULL OR ST_IsEmpty(v_regiao_4674) THEN
        RAISE EXCEPTION 'Região % não encontrada na camada de referência.', p_codigo_regiao;
    END IF;

    v_a := fn_geom_safra_regiao(p_processo_a, v_regiao_4674);
    v_b := fn_geom_safra_regiao(p_processo_b, v_regiao_4674);

    RETURN QUERY
    SELECT x.t::VARCHAR,
           ST_Transform(x.g, 4674),
           COALESCE(ST_Area(x.g), 0)::DOUBLE PRECISION / 10000.0
    FROM (VALUES
        ('GANHO',       fn_extrai_poligonos(ST_Difference(v_b, v_a))),
        ('PERDA',       fn_extrai_poligonos(ST_Difference(v_a, v_b))),
        ('PERMANENCIA', fn_extrai_poligonos(ST_Intersection(v_a, v_b)))
    ) AS x(t, g);
END;
$$ LANGUAGE plpgsql STABLE;

-- Comparação com cache (T21): devolve a linha de cache_comparacao_safras. Se já existe,
-- retorna na hora; se não (ou p_forcar = TRUE), calcula, grava e retorna.
-- Só aceita versões fechadas (a trigger da V33 barra o resto).
CREATE OR REPLACE FUNCTION fn_comparar_safras(
    p_processo_a    BIGINT,
    p_processo_b    BIGINT,
    p_codigo_regiao VARCHAR,
    p_forcar        BOOLEAN DEFAULT FALSE
)
RETURNS cache_comparacao_safras AS $$
DECLARE
    v_row      cache_comparacao_safras;
    r          RECORD;
    v_ganho    DOUBLE PRECISION := 0;
    v_perda    DOUBLE PRECISION := 0;
    v_perm     DOUBLE PRECISION := 0;
    v_area_a   DOUBLE PRECISION;
    v_area_b   DOUBLE PRECISION;
    v_features JSONB := '[]'::jsonb;
    v_metricas JSONB;
BEGIN
    IF NOT p_forcar THEN
        SELECT * INTO v_row
          FROM cache_comparacao_safras c
         WHERE c.processo_a_id = p_processo_a
           AND c.processo_b_id = p_processo_b
           AND c.codigo_regiao = p_codigo_regiao;
        IF FOUND THEN
            RETURN v_row;
        END IF;
    END IF;

    FOR r IN SELECT * FROM fn_comparar_safras_poligonos(p_processo_a, p_processo_b, p_codigo_regiao) LOOP
        IF r.tipo = 'GANHO' THEN
            v_ganho := COALESCE(r.area_ha, 0);
        ELSIF r.tipo = 'PERDA' THEN
            v_perda := COALESCE(r.area_ha, 0);
        ELSE
            v_perm := COALESCE(r.area_ha, 0);
        END IF;

        IF NOT ST_IsEmpty(r.geom) THEN
            v_features := v_features || jsonb_build_array(jsonb_build_object(
                'type', 'Feature',
                'properties', jsonb_build_object('tipo', r.tipo, 'area_ha', ROUND(r.area_ha::NUMERIC, 4)),
                'geometry', ST_AsGeoJSON(r.geom)::jsonb));
        END IF;
    END LOOP;

    -- A = o que se perdeu + o que permaneceu; B = o que se ganhou + o que permaneceu.
    v_area_a := v_perda + v_perm;
    v_area_b := v_ganho + v_perm;

    v_metricas := jsonb_build_object(
        'regiao',               p_codigo_regiao,
        'srid_calculo',         fn_srid_calculo(),
        'area_a_ha',            ROUND(v_area_a::NUMERIC, 4),
        'area_b_ha',            ROUND(v_area_b::NUMERIC, 4),
        'ganho_ha',             ROUND(v_ganho::NUMERIC, 4),
        'perda_ha',             ROUND(v_perda::NUMERIC, 4),
        'permanencia_ha',       ROUND(v_perm::NUMERIC, 4),
        'variacao_ha',          ROUND((v_area_b - v_area_a)::NUMERIC, 4),
        -- sem área na safra A a variação percentual não existe: fica 0 para nunca devolver NULL
        'variacao_percentual',  CASE WHEN v_area_a > 0
                                     THEN ROUND(((v_area_b - v_area_a) / v_area_a * 100)::NUMERIC, 2)
                                     ELSE 0 END
    );

    INSERT INTO cache_comparacao_safras
        (processo_a_id, processo_b_id, codigo_regiao, metricas_evolucao_json, geojson_delta)
    VALUES
        (p_processo_a, p_processo_b, p_codigo_regiao, v_metricas,
         jsonb_build_object('type', 'FeatureCollection', 'features', v_features))
    ON CONFLICT (processo_a_id, processo_b_id, codigo_regiao) DO UPDATE
        SET metricas_evolucao_json = EXCLUDED.metricas_evolucao_json,
            geojson_delta          = EXCLUDED.geojson_delta,
            data_processamento     = now()
    RETURNING * INTO v_row;

    RETURN v_row;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION fn_comparar_safras_poligonos(BIGINT, BIGINT, VARCHAR) IS
    'Ganho (B-A), perda (A-B) e permanência (A∩B) entre duas cargas numa região. 3 linhas sempre; geom em 4674, area_ha em hectares.';
COMMENT ON FUNCTION fn_comparar_safras(BIGINT, BIGINT, VARCHAR, BOOLEAN) IS
    'Comparação com cache: lê de cache_comparacao_safras ou calcula, grava e devolve. p_forcar = TRUE recalcula.';