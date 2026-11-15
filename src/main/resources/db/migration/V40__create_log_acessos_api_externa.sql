-- V40: US12 — auditoria de consumo da API externa (Tarefas 20 e 22)
--
-- id_log -> id | id_chave -> chave_api_id (FK para chaves_api).
-- A trigger fica aqui (e não na V39) porque depende desta tabela.

CREATE TABLE log_acessos_api_externa (
    id              BIGSERIAL PRIMARY KEY,
    chave_api_id    BIGINT NOT NULL REFERENCES chaves_api(id) ON DELETE RESTRICT,
    rota_acessada   VARCHAR(255),
    status_http     INTEGER,
    ip_origem       VARCHAR(45),
    data_acesso     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_log_acessos_status_http
        CHECK (status_http IS NULL OR status_http BETWEEN 100 AND 599)
);

-- Atende consultas por chave e a verificação da trigger abaixo (sem varrer o log inteiro).
CREATE INDEX idx_log_acessos_chave ON log_acessos_api_externa (chave_api_id, data_acesso);

COMMENT ON TABLE log_acessos_api_externa IS
    'Auditoria de cada chamada à API externa (rota, status HTTP, IP). Preserva o histórico mesmo com a chave revogada.';
COMMENT ON COLUMN log_acessos_api_externa.ip_origem IS
    'IPv4 ou IPv6 (até 45 caracteres).';

-- Tarefa 22: chave com histórico de uso não pode ser excluída fisicamente.
-- Para desativar, usar status REVOGADA (com data_revogacao).
CREATE OR REPLACE FUNCTION fn_bloquear_delete_chave_com_log()
RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM log_acessos_api_externa l WHERE l.chave_api_id = OLD.id) THEN
        RAISE EXCEPTION
            'Chave de API % possui acessos registrados: exclusão física bloqueada. Revogue a chave (status REVOGADA).',
            OLD.id;
    END IF;

    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_bloquear_delete_chave_com_log
    BEFORE DELETE ON chaves_api
    FOR EACH ROW
    EXECUTE FUNCTION fn_bloquear_delete_chave_com_log();