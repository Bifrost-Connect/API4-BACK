-- V33: US10 — cache das comparações temporais entre safras (Tarefa 21)
--
-- "id_carga_a / id_carga_b" = processo_a_id / processo_b_id (carga = processo).
-- Só compara versões publicadas (versao_fechada = TRUE). Como a versão fechada é
-- imutável (V22), o resultado em cache nunca fica velho.

CREATE TABLE cache_comparacao_safras (
    id                      BIGSERIAL PRIMARY KEY,
    processo_a_id           BIGINT NOT NULL REFERENCES processo(id) ON DELETE CASCADE,
    processo_b_id           BIGINT NOT NULL REFERENCES processo(id) ON DELETE CASCADE,
    codigo_regiao           VARCHAR(50) NOT NULL,
    metricas_evolucao_json  JSONB NOT NULL,
    geojson_delta           JSONB NOT NULL,
    data_processamento      TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT uq_cache_comparacao_safras UNIQUE (processo_a_id, processo_b_id, codigo_regiao),
    CONSTRAINT ck_cache_comparacao_cargas_distintas CHECK (processo_a_id <> processo_b_id),
    CONSTRAINT ck_cache_comparacao_metricas_objeto CHECK (jsonb_typeof(metricas_evolucao_json) = 'object'),
    CONSTRAINT ck_cache_comparacao_delta_objeto CHECK (jsonb_typeof(geojson_delta) = 'object')
);

-- A UNIQUE já atende buscas por (A), (A, B) e (A, B, região).
-- Este índice atende buscas e limpeza pela safra B.
CREATE INDEX idx_cache_comparacao_processo_b ON cache_comparacao_safras (processo_b_id);

COMMENT ON TABLE cache_comparacao_safras IS
    'Cache de comparações temporais entre duas versões publicadas (A = anterior, B = nova) por região. Chave: (processo_a_id, processo_b_id, codigo_regiao).';
COMMENT ON COLUMN cache_comparacao_safras.codigo_regiao IS
    'Código da região do recorte (ex.: código IBGE do município).';
COMMENT ON COLUMN cache_comparacao_safras.metricas_evolucao_json IS
    'Métricas da comparação (áreas de ganho, perda e permanência em hectares, e variação).';
COMMENT ON COLUMN cache_comparacao_safras.geojson_delta IS
    'FeatureCollection com os polígonos de ganho, perda e permanência. Sem mudança territorial, FeatureCollection com lista vazia (nunca NULL).';

-- Só aceita cargas com versão fechada (publicadas).
CREATE OR REPLACE FUNCTION fn_valida_cache_comparacao_publicadas()
RETURNS TRIGGER AS $$
DECLARE
    v_abertas BIGINT;
BEGIN
    SELECT COUNT(*) INTO v_abertas
    FROM processo
    WHERE id IN (NEW.processo_a_id, NEW.processo_b_id)
      AND NOT versao_fechada;

    IF v_abertas > 0 THEN
        RAISE EXCEPTION
            'Comparação inválida: processos % e % precisam ter versão fechada (publicada).',
            NEW.processo_a_id, NEW.processo_b_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_valida_cache_comparacao_publicadas
    BEFORE INSERT OR UPDATE OF processo_a_id, processo_b_id ON cache_comparacao_safras
    FOR EACH ROW
    EXECUTE FUNCTION fn_valida_cache_comparacao_publicadas();