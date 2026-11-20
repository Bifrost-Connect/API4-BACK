-- V45: cache materializado de localização (centroid + total de feições) das bases publicadas
-- Evita transformar/agregar todas as geometrias de feicao_carga em cada GET /mapa/camadas-legado.

CREATE TABLE IF NOT EXISTS base_localizacao_cache (
    processo_id   BIGINT PRIMARY KEY REFERENCES processo(id) ON DELETE CASCADE,
    latitude      DOUBLE PRECISION NOT NULL DEFAULT -15.7800,
    longitude     DOUBLE PRECISION NOT NULL DEFAULT -47.9300,
    total_feicoes BIGINT NOT NULL DEFAULT 0,
    atualizado_em TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE base_localizacao_cache IS
    'Cache de centroide (EPSG:4326) e contagem de feições da cópia vigente para bases publicadas.';

CREATE INDEX IF NOT EXISTS idx_arquivo_processado_processo_versao
    ON arquivo_processado (processo_id, versao DESC, id DESC);

CREATE OR REPLACE FUNCTION fn_atualizar_base_localizacao_cache(p_processo_id BIGINT)
RETURNS VOID AS $$
DECLARE
    v_lat   DOUBLE PRECISION;
    v_lng   DOUBLE PRECISION;
    v_total BIGINT;
BEGIN
    IF p_processo_id IS NULL THEN
        RETURN;
    END IF;

    SELECT
        COALESCE(
            ST_Y(ST_Centroid(ST_Transform(ST_SetSRID(ST_Extent(fc.geom)::geometry, 4674), 4326))),
            -15.7800
        ),
        COALESCE(
            ST_X(ST_Centroid(ST_Transform(ST_SetSRID(ST_Extent(fc.geom)::geometry, 4674), 4326))),
            -47.9300
        ),
        COUNT(fc.id)
    INTO v_lat, v_lng, v_total
    FROM vw_copia_vigente cv
    JOIN feicao_carga fc ON fc.arquivo_processado_id = cv.arquivo_processado_id
    WHERE cv.processo_id = p_processo_id;

    INSERT INTO base_localizacao_cache (processo_id, latitude, longitude, total_feicoes, atualizado_em)
    VALUES (
        p_processo_id,
        COALESCE(v_lat, -15.7800),
        COALESCE(v_lng, -47.9300),
        COALESCE(v_total, 0),
        now()
    )
    ON CONFLICT (processo_id) DO UPDATE SET
        latitude      = EXCLUDED.latitude,
        longitude     = EXCLUDED.longitude,
        total_feicoes = EXCLUDED.total_feicoes,
        atualizado_em = EXCLUDED.atualizado_em;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION fn_trigger_base_localizacao_cache_processo()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.versao_fechada = TRUE
       OR EXISTS (
           SELECT 1
           FROM etapa e
           JOIN situacao s ON s.id = NEW.situacao_atual_id
           WHERE e.id = NEW.etapa_atual_id
             AND e.nome = 'PUBLICACAO'
             AND s.nome = 'CONCLUIDA'
       ) THEN
        PERFORM fn_atualizar_base_localizacao_cache(NEW.id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS tg_atualiza_base_localizacao_cache_ao_publicar ON processo;
CREATE TRIGGER tg_atualiza_base_localizacao_cache_ao_publicar
    AFTER INSERT OR UPDATE OF versao_fechada, etapa_atual_id, situacao_atual_id ON processo
    FOR EACH ROW
    EXECUTE FUNCTION fn_trigger_base_localizacao_cache_processo();

CREATE OR REPLACE FUNCTION fn_atualizar_base_localizacao_cache_por_arquivos(p_arquivo_ids BIGINT[])
RETURNS VOID AS $$
DECLARE
    v_processo_id BIGINT;
BEGIN
    IF p_arquivo_ids IS NULL OR cardinality(p_arquivo_ids) = 0 THEN
        RETURN;
    END IF;

    FOR v_processo_id IN
        SELECT DISTINCT ap.processo_id
        FROM arquivo_processado ap
        JOIN processo p ON p.id = ap.processo_id
        LEFT JOIN etapa e ON e.id = p.etapa_atual_id
        LEFT JOIN situacao s ON s.id = p.situacao_atual_id
        WHERE ap.id = ANY(p_arquivo_ids)
          AND (p.versao_fechada = TRUE
               OR (e.nome = 'PUBLICACAO' AND s.nome = 'CONCLUIDA'))
    LOOP
        PERFORM fn_atualizar_base_localizacao_cache(v_processo_id);
    END LOOP;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION fn_trigger_base_localizacao_cache_feicao_insert()
RETURNS TRIGGER AS $$
DECLARE
    v_arquivo_ids BIGINT[];
BEGIN
    SELECT array_agg(DISTINCT arquivo_processado_id)
    INTO v_arquivo_ids
    FROM inserted_rows;

    PERFORM fn_atualizar_base_localizacao_cache_por_arquivos(v_arquivo_ids);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION fn_trigger_base_localizacao_cache_feicao_delete()
RETURNS TRIGGER AS $$
DECLARE
    v_arquivo_ids BIGINT[];
BEGIN
    SELECT array_agg(DISTINCT arquivo_processado_id)
    INTO v_arquivo_ids
    FROM deleted_rows;

    PERFORM fn_atualizar_base_localizacao_cache_por_arquivos(v_arquivo_ids);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS tg_atualiza_base_localizacao_cache_feicao_insert ON feicao_carga;
CREATE TRIGGER tg_atualiza_base_localizacao_cache_feicao_insert
    AFTER INSERT ON feicao_carga
    REFERENCING NEW TABLE AS inserted_rows
    FOR EACH STATEMENT
    EXECUTE FUNCTION fn_trigger_base_localizacao_cache_feicao_insert();

DROP TRIGGER IF EXISTS tg_atualiza_base_localizacao_cache_feicao_delete ON feicao_carga;
CREATE TRIGGER tg_atualiza_base_localizacao_cache_feicao_delete
    AFTER DELETE ON feicao_carga
    REFERENCING OLD TABLE AS deleted_rows
    FOR EACH STATEMENT
    EXECUTE FUNCTION fn_trigger_base_localizacao_cache_feicao_delete();

DO $$
DECLARE
    v_processo_id BIGINT;
BEGIN
    FOR v_processo_id IN
        SELECT p.id
        FROM processo p
        LEFT JOIN etapa e ON e.id = p.etapa_atual_id
        LEFT JOIN situacao s ON s.id = p.situacao_atual_id
        WHERE p.versao_fechada = TRUE
           OR (e.nome = 'PUBLICACAO' AND s.nome = 'CONCLUIDA')
    LOOP
        PERFORM fn_atualizar_base_localizacao_cache(v_processo_id);
    END LOOP;
END;
$$;

GRANT SELECT ON base_localizacao_cache TO analista_mapa;
