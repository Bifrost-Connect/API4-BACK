-- V36: US11 — log de exportações de relatórios (Tarefas 18 e 21)
--
-- Convenções do projeto: PK id BIGSERIAL, FKs BIGINT, sem prefixo id_.
--   id_exportacao -> id | id_usuario -> usuario_id | id_carga -> processo_id (carga = processo)
-- Log de auditoria: quem exportou, de qual carga, em que formato e com quais filtros.
-- Gravar uma vez ao terminar a exportação (total_registros_exportados já conhecido).

CREATE TABLE log_exportacoes_relatorios (
    id                          BIGSERIAL PRIMARY KEY,
    usuario_id                  BIGINT NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
    processo_id                 BIGINT NOT NULL REFERENCES processo(id) ON DELETE RESTRICT,
    formato                     VARCHAR(15) NOT NULL,
    filtros_utilizados          JSONB,
    total_registros_exportados  INTEGER,
    ip_solicitante              VARCHAR(45),
    data_hora_exportacao        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_log_exportacoes_formato CHECK (formato IN ('CSV', 'GEOJSON')),
    CONSTRAINT ck_log_exportacoes_total   CHECK (total_registros_exportados IS NULL
                                                 OR total_registros_exportados >= 0)
);

CREATE INDEX idx_log_exportacoes_usuario  ON log_exportacoes_relatorios (usuario_id, data_hora_exportacao);
CREATE INDEX idx_log_exportacoes_processo ON log_exportacoes_relatorios (processo_id);

COMMENT ON TABLE log_exportacoes_relatorios IS
    'Auditoria das exportações oficiais (CSV/GEOJSON). Não permite DELETE nem TRUNCATE.';
COMMENT ON COLUMN log_exportacoes_relatorios.processo_id IS
    'Carga exportada (processo com versão fechada).';
COMMENT ON COLUMN log_exportacoes_relatorios.filtros_utilizados IS
    'Filtros aplicados na exportação, como o back recebeu.';
COMMENT ON COLUMN log_exportacoes_relatorios.ip_solicitante IS
    'IPv4 ou IPv6 (até 45 caracteres).';

-- Bloqueio de DELETE (e TRUNCATE) no log de auditoria.
CREATE OR REPLACE FUNCTION fn_bloquear_delete_log_exportacoes()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'Log de exportações é de auditoria: TRUNCATE não permitido.';
    END IF;

    RAISE EXCEPTION 'Log de exportações é de auditoria: DELETE do registro % não permitido.', OLD.id;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_bloquear_delete_log_exportacoes
    BEFORE DELETE ON log_exportacoes_relatorios
    FOR EACH ROW
    EXECUTE FUNCTION fn_bloquear_delete_log_exportacoes();

CREATE TRIGGER trg_bloquear_truncate_log_exportacoes
    BEFORE TRUNCATE ON log_exportacoes_relatorios
    FOR EACH STATEMENT
    EXECUTE FUNCTION fn_bloquear_delete_log_exportacoes();