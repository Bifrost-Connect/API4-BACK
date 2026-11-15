-- V39: US12 — chaves de API para consumo externo (Tarefas 19, 21 e 22)
--
-- Convenções do projeto: PK id BIGSERIAL, FKs BIGINT, sem prefixo id_, domínio em MAIÚSCULAS
-- sem acento. Por isso: id_chave -> id | id_usuario -> usuario_id |
-- id_usuario_revogacao -> usuario_revogacao_id | status 'Ativa/Revogada/Expirada' ->
-- 'ATIVA'/'REVOGADA'/'EXPIRADA'.
-- A chave em texto NUNCA é gravada: só o SHA-256 em hexadecimal minúsculo (64 caracteres)
-- e o prefixo, que serve para o parceiro identificar a chave sem expô-la.

CREATE TABLE chaves_api (
    id                      BIGSERIAL PRIMARY KEY,
    usuario_id              BIGINT NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
    nome_parceiro           VARCHAR(120) NOT NULL,
    prefixo_chave           VARCHAR(16) NOT NULL,
    hash_chave_sha256       CHAR(64) NOT NULL,
    escopos                 JSONB NOT NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'ATIVA',
    data_criacao            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data_expiracao          TIMESTAMP NULL,
    data_revogacao          TIMESTAMP NULL,
    usuario_revogacao_id    BIGINT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
    ultimo_uso_em           TIMESTAMP NULL,

    -- Tarefa 21: a UNIQUE já cria o índice B-Tree único do hash (não precisa de outro igual).
    CONSTRAINT uq_chaves_api_hash UNIQUE (hash_chave_sha256),

    -- Tarefa 22
    CONSTRAINT ck_chaves_api_status
        CHECK (status IN ('ATIVA', 'REVOGADA', 'EXPIRADA')),
    CONSTRAINT ck_chaves_api_revogada_com_data
        CHECK (status <> 'REVOGADA' OR data_revogacao IS NOT NULL),

    CONSTRAINT ck_chaves_api_hash_formato
        CHECK (hash_chave_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_chaves_api_escopos_tipo
        CHECK (jsonb_typeof(escopos) IN ('array', 'object'))
);

-- Tarefa 21: autenticação só olha chaves ativas.
CREATE INDEX idx_chaves_ativas ON chaves_api (hash_chave_sha256) WHERE status = 'ATIVA';

-- Apoio à FK e à listagem de chaves por usuário.
CREATE INDEX idx_chaves_api_usuario ON chaves_api (usuario_id);

COMMENT ON TABLE chaves_api IS
    'Chaves de API de parceiros externos. Guarda só o hash SHA-256; a chave em texto é mostrada uma única vez na criação.';
COMMENT ON COLUMN chaves_api.prefixo_chave IS
    'Início da chave, para identificação (nunca basta para autenticar).';
COMMENT ON COLUMN chaves_api.hash_chave_sha256 IS
    'SHA-256 da chave em hexadecimal minúsculo (64 caracteres).';
COMMENT ON COLUMN chaves_api.escopos IS
    'Permissões da chave (lista ou objeto JSON).';
COMMENT ON COLUMN chaves_api.status IS
    'ATIVA, REVOGADA ou EXPIRADA. REVOGADA exige data_revogacao.';
COMMENT ON COLUMN chaves_api.ultimo_uso_em IS
    'Atualizado pelo back a cada autenticação bem-sucedida.';