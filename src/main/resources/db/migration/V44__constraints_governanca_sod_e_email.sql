-- V44: US13 — constraints de governança de SoD e unicidade de e-mail (Tarefa 25)
-- ==========================================
-- Conferência prévia: se houver e-mails que diferem só por maiúsculas, falha com mensagem
-- clara (em vez do erro genérico de criação de índice).
-- ==========================================
DO $$
DECLARE
    v_email VARCHAR;
BEGIN
    SELECT LOWER(email) INTO v_email
      FROM usuario
     GROUP BY LOWER(email)
    HAVING COUNT(*) > 1
     LIMIT 1;

    IF v_email IS NOT NULL THEN
        RAISE EXCEPTION
            'Não é possível criar a unicidade de e-mail: existem usuários com o e-mail % repetido (ignorando maiúsculas). Corrija os dados antes de aplicar a V44.',
            v_email;
    END IF;
END;
$$;

-- ==========================================
-- 1) E-mail único ignorando maiúsculas/minúsculas
-- ==========================================
CREATE UNIQUE INDEX uq_usuario_email_lower ON usuario (LOWER(email));

COMMENT ON INDEX uq_usuario_email_lower IS
    'Impede cadastro duplicado de e-mail que difere só por maiúsculas/minúsculas (ex.: Ana@x.com e ana@x.com).';

-- ==========================================
-- 2) Exceção de SoD exige justificativa documentada (mínimo de 10 caracteres úteis)
-- ==========================================
ALTER TABLE usuario
    ADD CONSTRAINT ck_usuario_sod_justificativa CHECK (
        excecao_sod_autorizada = FALSE
        OR (justificativa_sod IS NOT NULL AND length(btrim(justificativa_sod)) >= 10)
    );

COMMENT ON CONSTRAINT ck_usuario_sod_justificativa ON usuario IS
    'Exceção de acúmulo de função (SoD) só é aceita com justificativa de pelo menos 10 caracteres (sem contar espaços nas pontas).';
