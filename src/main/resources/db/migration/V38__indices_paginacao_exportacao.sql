-- V38: US11 — índices para leitura em lotes por cursor / paginação keyset (Tarefa 20)
--
-- id_poligono = feicao_carga.id | id_calculo = resultados_calculos.id
-- As PKs já têm índice, mas a exportação filtra por arquivo e pagina pelo id:
--   WHERE arquivo_processado_id = :arquivo AND id > :ultimo ORDER BY id LIMIT :lote
-- Com (arquivo_processado_id, id) o Postgres lê o lote direto em ordem, sem ordenar
-- e sem recomeçar do zero a cada página (diferente de OFFSET).

CREATE INDEX idx_feicao_carga_arquivo_id
    ON feicao_carga (arquivo_processado_id, id);

CREATE INDEX idx_res_calc_arquivo_id
    ON resultados_calculos (arquivo_processado_id, id);