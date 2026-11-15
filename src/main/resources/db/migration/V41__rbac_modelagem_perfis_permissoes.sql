-- V41: US13 — modelagem RBAC: perfis, permissões e segregação de funções (Tarefa 21)
--
-- Migration SOMENTE ADITIVA: não renomeia, não remove e não altera o tipo de nada que
-- já existe. As colunas novas têm DEFAULT, então INSERTs e entidades atuais continuam
-- funcionando sem mudança.
--
-- Convenções do projeto: PK id BIGSERIAL, FKs BIGINT, sem prefixo id_, tabelas no singular.
--   Perfis -> perfil (V1, reaproveitada) | Usuarios -> usuario (V2, reaproveitada)
--   Permissoes -> permissao | Perfil_Permissoes -> perfil_permissao
--   id_perfil -> perfil_id | id_permissao -> permissao_id | nome_perfil -> perfil.nome
-- Já existiam (V1/V2): perfil.nome UNIQUE, usuario.perfil_id NOT NULL e usuario.ativo DEFAULT TRUE.

-- ==========================================
-- perfil: flag de somente leitura
-- ==========================================
ALTER TABLE perfil
    ADD COLUMN IF NOT EXISTS somente_leitura_estrito BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN perfil.somente_leitura_estrito IS
    'TRUE para perfis que só consultam (ex.: AUDITOR). Perfil com a flag ligada não deve receber permissão de escrita.';

-- ==========================================
-- usuario: sessão e exceção de segregação de funções (SoD)
-- A regra que exige justificativa (CHECK) entra na V44 (Tarefa 25).
-- ==========================================
ALTER TABLE usuario
    ADD COLUMN IF NOT EXISTS versao_sessao          INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS excecao_sod_autorizada BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS justificativa_sod      TEXT NULL;

COMMENT ON COLUMN usuario.versao_sessao IS
    'Incrementada pelo back quando o perfil ou as permissões do usuário mudam, invalidando sessões antigas.';
COMMENT ON COLUMN usuario.excecao_sod_autorizada IS
    'TRUE quando o usuário tem exceção autorizada ao acúmulo de funções (SoD). Exige justificativa_sod (CHECK na V44).';
COMMENT ON COLUMN usuario.justificativa_sod IS
    'Justificativa documentada da exceção de SoD.';

-- ==========================================
-- permissao: catálogo de permissões granulares
-- codigo_permissao = MODULO.SUBPERMISSAO (ex.: BASES.HOMOLOGAR), porque a mesma
-- subpermissão (VISUALIZAR, HOMOLOGAR, REPROCESSAR) se repete em vários módulos.
-- ==========================================
CREATE TABLE permissao (
    id                  BIGSERIAL PRIMARY KEY,
    codigo_permissao    VARCHAR(100) NOT NULL,
    modulo              VARCHAR(50)  NOT NULL,
    descricao           VARCHAR(255),

    CONSTRAINT uq_permissao_codigo UNIQUE (codigo_permissao),
    CONSTRAINT ck_permissao_codigo_formato
        CHECK (codigo_permissao ~ '^[A-Z_]+\.[A-Z_]+$'),
    CONSTRAINT ck_permissao_codigo_do_modulo
        CHECK (codigo_permissao LIKE modulo || '.%')
);

CREATE INDEX idx_permissao_modulo ON permissao (modulo);

COMMENT ON TABLE permissao IS
    'Catálogo de permissões granulares do RBAC. Código no formato MODULO.SUBPERMISSAO (ex.: USUARIOS.CRIAR).';
COMMENT ON COLUMN permissao.codigo_permissao IS
    'Identificador único da permissão, em MAIÚSCULAS: MODULO.SUBPERMISSAO.';
COMMENT ON COLUMN permissao.modulo IS
    'Módulo do sistema ao qual a permissão pertence (ex.: BASES, MAPA, AUDITORIA).';

-- ==========================================
-- perfil_permissao: vínculo N:N entre perfil e permissão
-- ==========================================
CREATE TABLE perfil_permissao (
    perfil_id       BIGINT NOT NULL REFERENCES perfil(id)    ON DELETE RESTRICT,
    permissao_id    BIGINT NOT NULL REFERENCES permissao(id) ON DELETE RESTRICT,

    CONSTRAINT pk_perfil_permissao PRIMARY KEY (perfil_id, permissao_id)
);

-- A PK atende buscas por perfil. Este índice atende "quais perfis têm a permissão X"
-- e a FK de permissao_id.
CREATE INDEX idx_perfil_permissao_permissao ON perfil_permissao (permissao_id);

COMMENT ON TABLE perfil_permissao IS
    'Permissões concedidas a cada perfil (N:N). Perfil sem linhas aqui não tem nenhuma permissão.';
