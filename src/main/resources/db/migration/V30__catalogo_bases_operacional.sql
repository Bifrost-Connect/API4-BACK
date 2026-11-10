-- V30: catálogo operacional de bases
--
-- "Base" = processo (carga). A view junta carga + metadados ricos (V27) + controle de
-- versão (V22) e já entrega pronta a regra de quando a base pode ser consultada.

-- ==========================================
-- TAREFA 20 + 21: view unificada do catálogo
--
-- pronta_para_consulta: só TRUE com Publicação + Concluída + versão fechada (mesma trava
-- do mapa da V23 e da blindagem do catálogo).
--
-- safra_vigente: a versão publicada mais recente de cada conjunto/região. É calculada aqui
-- e não gravada no processo de propósito: pela V22 o processo fica imutável depois que a
-- versão fecha, então não daria pra virar a versão antiga pra FALSE quando chega uma nova.
-- Calculando na view, cada nova publicação passa a ser a vigente na hora, e as anteriores
-- continuam fechadas e consultáveis como histórico sem ninguém tocar nelas.
-- ==========================================
CREATE OR REPLACE VIEW vw_catalogo_bases_operacional AS
WITH base AS (
    SELECT p.id,
           p.conjunto_id,
           c.nome                 AS conjunto,
           p.orgao_id,
           o.nome                 AS orgao,
           o.sigla                AS orgao_sigla,
           p.ano_safra,
           p.competencia,
           p.formato,
           p.classificacao_acesso,
           p.cobertura_espacial,
           p.cobertura_temporal_inicio,
           p.cobertura_temporal_fim,
           p.epsg_destino,
           e.nome                 AS etapa_atual,
           s.nome                 AS situacao,
           p.versao_oficial,
           p.versao_fechada,
           p.data_publicacao,
           p.data_criacao,
           (e.nome = 'PUBLICACAO' AND s.nome = 'CONCLUIDA' AND p.versao_fechada) AS pronta_para_consulta
    FROM processo p
    JOIN conjunto c       ON c.id = p.conjunto_id
    JOIN orgao o          ON o.id = p.orgao_id
    LEFT JOIN etapa e     ON e.id = p.etapa_atual_id
    LEFT JOIN situacao s  ON s.id = p.situacao_atual_id
),
vigente AS (
    -- uma por conjunto/região: a última publicada ganha
    SELECT DISTINCT ON (b.conjunto_id, COALESCE(b.cobertura_espacial, ''))
           b.id
    FROM base b
    WHERE b.pronta_para_consulta
    ORDER BY b.conjunto_id, COALESCE(b.cobertura_espacial, ''), b.data_publicacao DESC, b.id DESC
)
SELECT b.*,
       COALESCE(b.pronta_para_consulta, FALSE)                 AS disponivel,
       (v.id IS NOT NULL)                                       AS safra_vigente,
       CASE
           WHEN b.pronta_para_consulta              THEN 'DISPONIVEL'
           WHEN b.situacao = 'EM_VALIDACAO'         THEN 'EM_QUARENTENA'
           WHEN b.situacao IN ('FALHOU', 'FECHADO') THEN 'INDISPONIVEL'
           ELSE 'EM_PROCESSAMENTO'
       END                                                      AS status_operacional
FROM base b
LEFT JOIN vigente v ON v.id = b.id;

COMMENT ON VIEW vw_catalogo_bases_operacional IS
    'Catálogo de bases (cargas) com metadados, controle de versão, pronta_para_consulta (Publicação + Concluída + versão fechada) e safra_vigente (última publicada de cada conjunto/região).';

-- ==========================================
-- TAREFA 21 (Otimização): busca textual + filtros compostos
-- ==========================================
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- a busca do catálogo é ILIKE '%termo%', que só usa índice com trigram
CREATE INDEX IF NOT EXISTS idx_conjunto_nome_trgm ON conjunto USING GIN (nome gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_orgao_nome_trgm    ON orgao    USING GIN (nome gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_orgao_sigla_trgm   ON orgao    USING GIN (sigla gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_processo_catalogo_filtros
    ON processo (conjunto_id, ano_safra, situacao_atual_id, versao_fechada);

-- ordenação padrão do catálogo (mais recentes primeiro)
CREATE INDEX IF NOT EXISTS idx_processo_data_publicacao
    ON processo (data_publicacao DESC NULLS LAST, id DESC);

-- ==========================================
-- TAREFA 23: Analista lê o catálogo só pela view
-- Reaproveita a role somente-leitura da V23. Ela já começou sem acesso a tabela nenhuma;
-- aqui libera a view e reforça o REVOKE nas tabelas brutas de geometria e de quarentena.
-- ==========================================
GRANT SELECT ON vw_catalogo_bases_operacional TO analista_mapa;

REVOKE ALL ON feicao_carga, arquivo_processado, log_validacao, ocorrencia_validacao FROM analista_mapa;
