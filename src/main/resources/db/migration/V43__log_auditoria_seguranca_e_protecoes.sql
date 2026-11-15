-- V43: US13 — log de auditoria de segurança imutável + proibição de exclusão física de usuário
-- (Tarefas 23 e 24)
--
-- Migration SOMENTE ADITIVA: cria uma tabela nova e adiciona triggers. Não altera nenhuma
-- coluna nem constraint existente (a trava em usuario é uma trigger, não mexe nas FKs
-- das outras tabelas).
--
-- Convenções do projeto: PK id BIGSERIAL, FKs BIGINT, sem prefixo id_, tabelas no singular.
--   Log_Auditoria_Seguranca -> log_auditoria_seguranca | id_log_seg -> id
--   id_usuario_ator -> usuario_ator_id | id_usuario_alvo -> usuario_alvo_id
-- Padrão de imutabilidade igual ao da V36 (log_exportacoes_relatorios): UPDATE, DELETE e TRUNCATE.

-- ==========================================
-- TAREFA 23: tabela imutável de auditoria de segurança
-- ==========================================
CREATE TABLE log_auditoria_seguranca (
    id                    BIGSERIAL PRIMARY KEY,
    usuario_ator_id       BIGINT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
    usuario_alvo_id       BIGINT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
    evento                VARCHAR(60) NOT NULL,
    detalhes_anteriores   JSONB,
    detalhes_novos        JSONB,
    justificativa         TEXT,
    rota_tentada          VARCHAR(255),
    ip_origem             VARCHAR(45),
    data_evento           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_log_aud_seg_evento CHECK (btrim(evento) <> '')
);

-- Apoio às FKs e às consultas da tela de governança (por usuário e por tipo de evento).
CREATE INDEX idx_log_aud_seg_ator   ON log_auditoria_seguranca (usuario_ator_id, data_evento);
CREATE INDEX idx_log_aud_seg_alvo   ON log_auditoria_seguranca (usuario_alvo_id, data_evento);
CREATE INDEX idx_log_aud_seg_evento ON log_auditoria_seguranca (evento, data_evento);

COMMENT ON TABLE log_auditoria_seguranca IS
    'Trilha imutável de eventos de segurança (troca de perfil, exceção de SoD, acesso negado etc.). Não permite UPDATE, DELETE nem TRUNCATE.';
COMMENT ON COLUMN log_auditoria_seguranca.usuario_ator_id IS
    'Quem executou ou tentou a ação. NULL quando não há usuário autenticado (ex.: tentativa anônima).';
COMMENT ON COLUMN log_auditoria_seguranca.usuario_alvo_id IS
    'Usuário afetado pela ação, quando houver (ex.: usuário que teve o perfil trocado).';
COMMENT ON COLUMN log_auditoria_seguranca.evento IS
    'Tipo do evento (ex.: ALERTA_SOD, TROCA_PERFIL, ACESSO_NEGADO).';
COMMENT ON COLUMN log_auditoria_seguranca.detalhes_anteriores IS
    'Estado antes da alteração, em JSON.';
COMMENT ON COLUMN log_auditoria_seguranca.detalhes_novos IS
    'Estado depois da alteração, em JSON.';
COMMENT ON COLUMN log_auditoria_seguranca.rota_tentada IS
    'Rota da API envolvida no evento (ex.: tentativa de acesso negado).';
COMMENT ON COLUMN log_auditoria_seguranca.ip_origem IS
    'IPv4 ou IPv6 (até 45 caracteres).';

-- ==========================================
-- TAREFA 24 (parte 1): trigger de imutabilidade do log
-- ==========================================
CREATE OR REPLACE FUNCTION fn_bloquear_alteracao_log_auditoria_seguranca()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'Log de auditoria de segurança é imutável: TRUNCATE não permitido.';
    END IF;

    RAISE EXCEPTION 'Log de auditoria de segurança é imutável: % do registro % não permitido.',
        TG_OP, OLD.id;
    RETURN NULL; -- nunca alcançado, mas exigido pela assinatura da função
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_bloquear_alteracao_log_auditoria_seguranca
    BEFORE UPDATE OR DELETE ON log_auditoria_seguranca
    FOR EACH ROW
    EXECUTE FUNCTION fn_bloquear_alteracao_log_auditoria_seguranca();

CREATE TRIGGER trg_bloquear_truncate_log_auditoria_seguranca
    BEFORE TRUNCATE ON log_auditoria_seguranca
    FOR EACH STATEMENT
    EXECUTE FUNCTION fn_bloquear_alteracao_log_auditoria_seguranca();

-- ==========================================
-- TAREFA 24 (parte 2): proibição de exclusão física de usuário
-- Bloqueia QUALQUER DELETE em usuario (só inativação lógica: ativo = FALSE). Fica numa trigger,
-- e não em mudança de FK, para não alterar constraints de tabelas que já estão em uso.
-- Se um dia for preciso permitir apagar usuário sem nenhum histórico, é só ajustar esta função.
-- ==========================================
CREATE OR REPLACE FUNCTION fn_bloquear_delete_usuario()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'Usuário % não pode ser excluído fisicamente. Para desativar, use a inativação lógica (ativo = FALSE).',
        OLD.id;
    RETURN NULL; -- nunca alcançado, mas exigido pela assinatura da função
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_bloquear_delete_usuario
    BEFORE DELETE ON usuario
    FOR EACH ROW
    EXECUTE FUNCTION fn_bloquear_delete_usuario();
