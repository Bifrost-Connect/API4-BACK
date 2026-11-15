-- V30: US09 — catálogo operacional de bases + vigência de safra
-- Tarefas 20 e 21.
--
-- "Carga" = processo. Nomes de domínio reais: etapa 'PUBLICACAO', situacao 'CONCLUIDA'.
-- Não existe coluna de "região": o grupo de vigência é (conjunto_id, orgao_id).
-- Se o time decidir outro grupo, mudar só a trigger (WHERE), o índice uq_processo_safra_vigente
-- e o backfill desta migration.

-- ==========================================
-- TAREFA 21 (parte 1): flag de vigência em processo
-- ==========================================
ALTER TABLE processo
    ADD COLUMN safra_vigente BOOLEAN NOT NULL DEFAULT FALSE;

-- Só versão fechada (publicada) pode ser vigente.
ALTER TABLE processo
    ADD CONSTRAINT ck_processo_safra_vigente_fechada CHECK (NOT safra_vigente OR versao_fechada);

COMMENT ON COLUMN processo.safra_vigente IS
    'TRUE na versão fechada mais atual do grupo (conjunto_id, orgao_id). Mantida pela trigger trg_atualiza_safra_vigente; as versões anteriores continuam fechadas, só perdem a flag.';

-- ==========================================
-- A V22 trava QUALQUER update em processo fechado, inclusive o que tira a flag
-- da versão anterior. Recria a função da V22 (mesmo corpo) com uma única exceção:
-- em processo fechado só é aceito UPDATE que mude APENAS safra_vigente.
-- Qualquer outra coluna alterada continua abortando.
-- ==========================================
CREATE OR REPLACE FUNCTION fn_bloqueia_versao_fechada_processo()
RETURNS TRIGGER AS $$
DECLARE
    v_ultima_decisao VARCHAR(20);
BEGIN
    IF OLD.versao_fechada THEN
        IF TG_OP = 'UPDATE' THEN
            IF NEW.safra_vigente IS DISTINCT FROM OLD.safra_vigente
               AND (to_jsonb(NEW) - 'safra_vigente') = (to_jsonb(OLD) - 'safra_vigente') THEN
                RETURN NEW;
            END IF;
        END IF;

        RAISE EXCEPTION
            'Versão fechada: o processo % é imutável. Operação % abortada em processo.',
            OLD.id, TG_OP;
    END IF;

    IF TG_OP = 'UPDATE' AND NEW.versao_fechada THEN
        SELECT h.decisao INTO v_ultima_decisao
        FROM historico_aprovacoes_gestor h
        WHERE h.processo_id = OLD.id
          AND h.etapa_avaliada = 'PUBLICACAO'
        ORDER BY h.data_decisao DESC, h.id DESC
        LIMIT 1;

        IF v_ultima_decisao IS DISTINCT FROM 'APROVADO' THEN
            RAISE EXCEPTION
                'Não é possível fechar a versão do processo %: falta aprovação do gestor na etapa PUBLICACAO.',
                OLD.id;
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ==========================================
-- Backfill: cargas já fechadas. A mais recente de cada grupo vira vigente.
-- (roda depois da função acima, que permite mudar só a flag)
-- ==========================================
UPDATE processo p
   SET safra_vigente = TRUE
  FROM (
        SELECT DISTINCT ON (conjunto_id, orgao_id) id
        FROM processo
        WHERE versao_fechada
        ORDER BY conjunto_id, orgao_id, data_publicacao DESC, id DESC
       ) v
 WHERE p.id = v.id;

-- Garante no banco: no máximo uma vigente por grupo.
CREATE UNIQUE INDEX uq_processo_safra_vigente
    ON processo (conjunto_id, orgao_id)
    WHERE safra_vigente;

-- ==========================================
-- TAREFA 21 (parte 2): trigger de vigência no fechamento
-- Ao fechar a versão (FALSE -> TRUE), a carga vira vigente e a vigente anterior
-- do mesmo grupo perde a flag. Nada além da flag é alterado nas anteriores.
-- O lock serializa dois fechamentos simultâneos do mesmo grupo.
-- ==========================================
CREATE OR REPLACE FUNCTION fn_atualiza_safra_vigente()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(
        hashtextextended('safra_vigente:' || NEW.conjunto_id || ':' || NEW.orgao_id, 0));

    UPDATE processo
       SET safra_vigente = FALSE
     WHERE conjunto_id = NEW.conjunto_id
       AND orgao_id    = NEW.orgao_id
       AND id <> NEW.id
       AND safra_vigente;

    NEW.safra_vigente := TRUE;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_atualiza_safra_vigente
    BEFORE UPDATE OF versao_fechada ON processo
    FOR EACH ROW
    WHEN (NEW.versao_fechada AND NOT OLD.versao_fechada)
    EXECUTE FUNCTION fn_atualiza_safra_vigente();

-- ==========================================
-- TAREFA 20: view unificada do catálogo operacional
-- Uma linha por carga (processo), com metadados e controle de versão.
-- pronta_para_consulta = PUBLICACAO + CONCLUIDA + versao_fechada.
-- ==========================================
CREATE OR REPLACE VIEW vw_catalogo_bases_operacional AS
SELECT p.id                          AS processo_id,
       p.conjunto_id,
       c.nome                        AS conjunto,
       p.orgao_id,
       o.nome                        AS orgao,
       o.sigla                       AS orgao_sigla,
       p.ano_safra,
       p.competencia,
       p.formato,
       p.classificacao_acesso,
       p.cobertura_espacial,
       p.cobertura_temporal_inicio,
       p.cobertura_temporal_fim,
       p.epsg_origem,
       p.epsg_detectado,
       p.epsg_destino,
       e.nome                        AS etapa_atual,
       s.nome                        AS situacao_atual,
       p.versao_oficial,
       p.versao_fechada,
       p.data_publicacao,
       p.data_criacao,
       COALESCE(e.nome = 'PUBLICACAO'
                AND s.nome = 'CONCLUIDA'
                AND p.versao_fechada, FALSE) AS pronta_para_consulta,
       p.safra_vigente
FROM processo p
JOIN conjunto c       ON c.id = p.conjunto_id
JOIN orgao o          ON o.id = p.orgao_id
LEFT JOIN etapa e     ON e.id = p.etapa_atual_id
LEFT JOIN situacao s  ON s.id = p.situacao_atual_id;

COMMENT ON VIEW vw_catalogo_bases_operacional IS
    'Catálogo operacional (US09): uma linha por carga, com metadados, etapa/situação e controle de versão. pronta_para_consulta = etapa PUBLICACAO + situação CONCLUIDA + versão fechada; safra_vigente = versão oficial mais atual do grupo (conjunto, órgão).';
COMMENT ON COLUMN vw_catalogo_bases_operacional.pronta_para_consulta IS
    'TRUE somente se etapa_atual = PUBLICACAO, situacao_atual = CONCLUIDA e versao_fechada = TRUE.';
COMMENT ON COLUMN vw_catalogo_bases_operacional.safra_vigente IS
    'TRUE na versão fechada mais atual do grupo (conjunto, órgão); versões fechadas anteriores ficam FALSE e seguem consultáveis como histórico.';