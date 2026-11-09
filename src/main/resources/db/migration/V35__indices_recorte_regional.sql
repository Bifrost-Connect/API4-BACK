-- V35: US10 — índices para o recorte regional das comparações (Tarefa 22)
--
-- Atendem as consultas das funções da V34:
--   * fn_geom_safra_regiao: feicao_carga por arquivo_processado_id + interseção com a região;
--   * fn_comparar_safras_poligonos: camada_referencia por código do município.
-- Não existe coluna codigo_municipio nas feições publicadas: o recorte é feito pela geometria
-- da região (malha municipal). O lado "carga" do B-Tree já existe em vw_dados_publicados_mapa
-- (idx_dpm_processo) e na UNIQUE da cache_comparacao_safras.

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- GIST combinado (arquivo + geometria): o filtro por arquivo e o recorte espacial
-- saem de um único índice.
CREATE INDEX idx_feicao_carga_arquivo_geom
    ON feicao_carga USING GIST (arquivo_processado_id, geom);

-- GIST combinado (fonte + geometria) na camada de referência.
CREATE INDEX idx_camada_referencia_fonte_geom
    ON camada_referencia USING GIST (fonte, geom);

-- B-Tree no código do município (malha municipal). Usa a mesma função da V34; se ela for
-- alterada para outra chave de atributo, recriar este índice (REINDEX).
CREATE INDEX idx_camada_referencia_codigo_municipio
    ON camada_referencia (fn_codigo_municipio(atributos));