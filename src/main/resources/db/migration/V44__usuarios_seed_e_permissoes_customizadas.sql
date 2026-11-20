-- V44: permissões customizadas por usuário (JSONB) e seed canônico dos 5 usuários de demonstração (operador@georural.com.br, auditor@georural.com.br, analista@georural.com.br, admin@georural.com.br, gestor@georural.com.br)

ALTER TABLE usuario
    ADD COLUMN IF NOT EXISTS permissoes_customizadas JSONB NULL;

COMMENT ON COLUMN usuario.permissoes_customizadas IS
    'Lista JSON de permissões efetivas/customizadas do usuário (nomes de Permissao ou MODULO:SUBPERMISSAO). Quando NULL, herda o padrão do perfil.';

-- Corrige senhas provisórias herdadas da V15
UPDATE usuario
   SET senha_hash = 'Troca123'
 WHERE senha_hash = 'senha_hash_provisoria';

-- Garante os 5 usuários canônicos de demonstração ativos com senha Troca123
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

-- Limpeza idempotente do perfil legado INTEGRADOR_EXTERNO (unificação nos 5 perfis oficiais)
UPDATE usuario
   SET perfil_id = (SELECT id FROM perfil WHERE nome = 'GESTOR')
 WHERE perfil_id = (SELECT id FROM perfil WHERE nome = 'INTEGRADOR_EXTERNO')
   AND email <> 'integrador@georural.com.br';

DELETE FROM usuario u
 WHERE u.email = 'integrador@georural.com.br'
   AND NOT EXISTS (SELECT 1 FROM auditoria a WHERE a.usuario_id = u.id)
   AND NOT EXISTS (SELECT 1 FROM chaves_api c WHERE c.usuario_id = u.id OR c.usuario_revogacao_id = u.id)
   AND NOT EXISTS (SELECT 1 FROM processo p WHERE p.operador_id = u.id OR p.gestor_publicacao_id = u.id)
   AND NOT EXISTS (SELECT 1 FROM arquivo_original ao WHERE ao.usuario_upload_id = u.id)
   AND NOT EXISTS (SELECT 1 FROM log_exportacoes_relatorios ler WHERE ler.usuario_id = u.id)
   AND NOT EXISTS (SELECT 1 FROM notificacao n WHERE n.usuario_id = u.id)
   AND NOT EXISTS (SELECT 1 FROM ocorrencia_validacao ov WHERE ov.auditor_id = u.id)
   AND NOT EXISTS (SELECT 1 FROM publicacao pub WHERE pub.usuario_responsavel_id = u.id)
   AND NOT EXISTS (SELECT 1 FROM historico_aprovacoes_gestor hag WHERE hag.gestor_id = u.id);

UPDATE usuario
   SET ativo = FALSE,
       perfil_id = (SELECT id FROM perfil WHERE nome = 'GESTOR')
 WHERE email = 'integrador@georural.com.br';

DELETE FROM perfil WHERE nome = 'INTEGRADOR_EXTERNO';
