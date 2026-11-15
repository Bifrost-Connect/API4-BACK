-- V31: US09 — índices de busca textual e filtros compostos do catálogo
--
-- "Nome da base" = conjunto.nome. "Órgão de origem" = orgao.nome (e orgao.sigla,
-- que a view do catálogo também expõe). "Situação" = processo.situacao_atual_id.

-- ==========================================
-- Busca textual (ILIKE '%termo%') com trigramas
-- ==========================================
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_conjunto_nome_trgm ON conjunto USING GIN (nome gin_trgm_ops);
CREATE INDEX idx_orgao_nome_trgm    ON orgao    USING GIN (nome gin_trgm_ops);
CREATE INDEX idx_orgao_sigla_trgm   ON orgao    USING GIN (sigla gin_trgm_ops);

-- ==========================================
-- Filtro composto do catálogo (B-Tree) em processo
-- Complementa idx_processo_dashboard (conjunto_id, situacao_atual_id, data_criacao).
-- ==========================================
CREATE INDEX idx_processo_catalogo_filtros
    ON processo (conjunto_id, ano_safra, situacao_atual_id, versao_fechada);