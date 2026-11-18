-- V44: permissões customizadas por usuário (JSONB) e seed canônico dos 6 usuários de demonstração

ALTER TABLE usuario
    ADD COLUMN IF NOT EXISTS permissoes_customizadas JSONB NULL;

COMMENT ON COLUMN usuario.permissoes_customizadas IS
    'Lista JSON de permissões efetivas/customizadas do usuário (nomes de Permissao ou MODULO:SUBPERMISSAO). Quando NULL, herda o padrão do perfil.';

-- Corrige senhas provisórias herdadas da V15
UPDATE usuario
   SET senha_hash = 'Troca123'
 WHERE senha_hash = 'senha_hash_provisoria';

-- Garante os 6 usuários canônicos de demonstração ativos com senha Troca123
INSERT INTO usuario (nome, email, senha_hash, perfil_id, ativo, data_criacao)
SELECT 'Operador GeoRural', 'operador@georural.com.br', 'Troca123', p.id, TRUE, now()
  FROM perfil p
 WHERE p.nome = 'OPERADOR'
ON CONFLICT (email) DO UPDATE
    SET nome = EXCLUDED.nome,
        senha_hash = EXCLUDED.senha_hash,
        perfil_id = EXCLUDED.perfil_id,
        ativo = TRUE;

INSERT INTO usuario (nome, email, senha_hash, perfil_id, ativo, data_criacao)
SELECT 'Auditor Ambiental', 'auditor@georural.com.br', 'Troca123', p.id, TRUE, now()
  FROM perfil p
 WHERE p.nome = 'AUDITOR'
ON CONFLICT (email) DO UPDATE
    SET nome = EXCLUDED.nome,
        senha_hash = EXCLUDED.senha_hash,
        perfil_id = EXCLUDED.perfil_id,
        ativo = TRUE;

INSERT INTO usuario (nome, email, senha_hash, perfil_id, ativo, data_criacao)
SELECT 'Analista Territorial', 'analista@georural.com.br', 'Troca123', p.id, TRUE, now()
  FROM perfil p
 WHERE p.nome = 'ANALISTA'
ON CONFLICT (email) DO UPDATE
    SET nome = EXCLUDED.nome,
        senha_hash = EXCLUDED.senha_hash,
        perfil_id = EXCLUDED.perfil_id,
        ativo = TRUE;

INSERT INTO usuario (nome, email, senha_hash, perfil_id, ativo, data_criacao)
SELECT 'Administrador GeoRural', 'admin@georural.com.br', 'Troca123', p.id, TRUE, now()
  FROM perfil p
 WHERE p.nome = 'ADMINISTRADOR'
ON CONFLICT (email) DO UPDATE
    SET nome = EXCLUDED.nome,
        senha_hash = EXCLUDED.senha_hash,
        perfil_id = EXCLUDED.perfil_id,
        ativo = TRUE;

INSERT INTO usuario (nome, email, senha_hash, perfil_id, ativo, data_criacao)
SELECT 'Gestor de Publicação', 'gestor@georural.com.br', 'Troca123', p.id, TRUE, now()
  FROM perfil p
 WHERE p.nome = 'GESTOR'
ON CONFLICT (email) DO UPDATE
    SET nome = EXCLUDED.nome,
        senha_hash = EXCLUDED.senha_hash,
        perfil_id = EXCLUDED.perfil_id,
        ativo = TRUE;

INSERT INTO usuario (nome, email, senha_hash, perfil_id, ativo, data_criacao)
SELECT 'Integrador Externo', 'integrador@georural.com.br', 'Troca123', p.id, TRUE, now()
  FROM perfil p
 WHERE p.nome = 'INTEGRADOR_EXTERNO'
ON CONFLICT (email) DO UPDATE
    SET nome = EXCLUDED.nome,
        senha_hash = EXCLUDED.senha_hash,
        perfil_id = EXCLUDED.perfil_id,
        ativo = TRUE;
