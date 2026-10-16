-- V20: resultados_calculos (depende de processo, arquivo_processado)
-- Tabela de Cache Analítico para armazenamento de sobreposições geoespaciais (Etapa 4 do Pipeline)

CREATE TABLE resultados_calculos (
    id                      BIGSERIAL PRIMARY KEY,
    processo_id             BIGINT NOT NULL REFERENCES processo(id) ON DELETE RESTRICT,
    arquivo_processado_id   BIGINT NOT NULL REFERENCES arquivo_processado(id) ON DELETE RESTRICT,
    
    -- Utilizando VARCHAR para suportar identificadores do CAR ou UUIDs gerados
    codigo_imovel           VARCHAR(100) NOT NULL,
    codigo_alerta_app       VARCHAR(100) NOT NULL,
    
    -- NUMERIC é o padrão PostgreSQL para alta precisão decimal
    hectares_sobrepostos    NUMERIC(12, 4) NOT NULL,
    data_calculo            TIMESTAMP NOT NULL DEFAULT now(),
    
    -- Unique Constraint para evitar duplicidade de processamento analítico no mesmo arquivo
    CONSTRAINT uk_imovel_alerta_arquivo 
        UNIQUE (codigo_imovel, codigo_alerta_app, arquivo_processado_id)
);


CREATE INDEX idx_res_calc_processo ON resultados_calculos(processo_id);
CREATE INDEX idx_res_calc_imovel ON resultados_calculos(codigo_imovel);
CREATE INDEX idx_res_calc_alerta ON resultados_calculos(codigo_alerta_app);

-- Comentário da tabela seguindo o padrão de documentação do banco
COMMENT ON TABLE resultados_calculos IS 
    'Cache relacional analítico. Armazena o resultado em hectares do cruzamento espacial (ST_Intersects/ST_Area) entre malhas para evitar reprocessamento na geração do dashboard.';

-- Concedendo permissão imediata de SELECT para a role do auditor (conforme regra da V19)
GRANT SELECT ON resultados_calculos TO auditor_ambiental;