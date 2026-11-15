-- V37: US11 — view consolidada de exportação oficial (Tarefa 19)
--
-- Granularidade: UMA LINHA POR IMÓVEL (feição) da cópia vigente de cada carga publicada
-- (versao_fechada = TRUE). As sobreposições de resultados_calculos entram agregadas por imóvel:
-- total de hectares, quantidade e o detalhe em JSON. Assim o GeoJSON de cada imóvel aparece uma
-- única vez (sem repetir geometria por sobreposição) e nenhuma linha é perdida nem duplicada
-- pelo JOIN. Imóvel sem sobreposição continua na view, com total 0.
--
--   id_poligono = feicao_id (feicao_carga.id)  | id_calculo = "id_calculo" dentro de sobreposicoes
--   Metadados = colunas de processo (V27)      | Hash = arquivo_original.hash_sha256
--   Geometria arredondada em 6 casas: ST_AsGeoJSON(geom, 6)

CREATE OR REPLACE VIEW vw_relatorio_exportacao_oficial AS
SELECT p.id                                   AS processo_id,
       fc.id                                  AS feicao_id,
       fc.codigo_imovel,
       p.conjunto_id,
       c.nome                                 AS conjunto,
       p.orgao_id,
       o.nome                                 AS orgao,
       o.sigla                                AS orgao_sigla,
       p.ano_safra,
       p.versao_oficial,
       p.data_publicacao,
       p.competencia,
       p.formato,
       p.classificacao_acesso,
       p.cobertura_espacial,
       p.cobertura_temporal_inicio,
       p.cobertura_temporal_fim,
       p.epsg_origem,
       p.epsg_destino,
       ao.id                                  AS arquivo_original_id,
       ao.nome_original                       AS arquivo_original_nome,
       ao.hash_sha256                         AS hash_arquivo_original,
       ap.id                                  AS arquivo_processado_id,
       ap.versao                              AS versao_arquivo_processado,
       ap.hash_sha256                         AS hash_arquivo_processado,
       COALESCE(rc.hectares_sobrepostos_total, 0)::NUMERIC(14, 4) AS hectares_sobrepostos_total,
       COALESCE(rc.qtd_sobreposicoes, 0)::INTEGER                 AS qtd_sobreposicoes,
       COALESCE(rc.sobreposicoes, '[]'::jsonb)                    AS sobreposicoes,
       ST_AsGeoJSON(fc.geom, 6)               AS geometria_geojson
FROM processo p
JOIN vw_copia_vigente cv     ON cv.processo_id = p.id
JOIN arquivo_processado ap   ON ap.id = cv.arquivo_processado_id
JOIN arquivo_original ao     ON ao.id = ap.arquivo_original_id
JOIN feicao_carga fc         ON fc.arquivo_processado_id = ap.id
JOIN conjunto c              ON c.id = p.conjunto_id
JOIN orgao o                 ON o.id = p.orgao_id
LEFT JOIN LATERAL (
    -- Agregação sem GROUP BY: sempre devolve 1 linha por imóvel, então o JOIN não multiplica
    -- nem remove linhas. Roda só para as linhas lidas (paginação por cursor continua barata).
    SELECT SUM(r.hectares_sobrepostos) AS hectares_sobrepostos_total,
           COUNT(*)                    AS qtd_sobreposicoes,
           jsonb_agg(
               jsonb_build_object(
                   'id_calculo',           r.id,
                   'fonte_camada',         r.fonte_camada,
                   'codigo_alerta',        r.codigo_alerta_app,
                   'hectares_sobrepostos', r.hectares_sobrepostos)
               ORDER BY r.id)              AS sobreposicoes
    FROM resultados_calculos r
    WHERE r.arquivo_processado_id = fc.arquivo_processado_id
      AND r.codigo_imovel = fc.codigo_imovel
) rc ON TRUE
WHERE p.versao_fechada;

COMMENT ON VIEW vw_relatorio_exportacao_oficial IS
    'Exportação oficial (US11): uma linha por imóvel da cópia vigente de cada carga publicada, com metadados, hash do arquivo original, sobreposições agregadas e geometria em GeoJSON (6 casas).';
COMMENT ON COLUMN vw_relatorio_exportacao_oficial.feicao_id IS
    'id_poligono: chave de ordenação para paginação keyset (WHERE feicao_id > último ORDER BY feicao_id).';
COMMENT ON COLUMN vw_relatorio_exportacao_oficial.sobreposicoes IS
    'Detalhe de resultados_calculos do imóvel: id_calculo, fonte_camada, codigo_alerta, hectares_sobrepostos.';
COMMENT ON COLUMN vw_relatorio_exportacao_oficial.geometria_geojson IS
    'ST_AsGeoJSON(geom, 6): texto GeoJSON da geometria, com no máximo 6 casas decimais.';