-- V31: US13 — o mínimo de banco que o motor RBAC do back precisa (Tarefas 14 a 17)
--
-- Mudanças:
--  1) perfis GESTOR e INTEGRADOR_EXTERNO, que faltavam pros 6 papéis oficiais
--  2) usuario.versao_sessao: todo token JWT carrega esse número; quando o Administrador
--     inativa alguém ou troca o perfil, o número sobe e os tokens antigos morrem na hora
--  3) usuario.excecao_sod_autorizada + justificativa_sod: exceção de acúmulo de função
--     (Operador aprovando/publicando) só com justificativa registrada
--  4) usuário Gestor de demonstração, já que com a SoD o Administrador não aprova mais nada
--
-- A modelagem completa (tabelas permissao / perfil_permissao, seed oficial, constraints)
-- é da US13-BANCO (Tarefas 21 a 26). Enquanto ela não chega, a matriz de permissões fica
-- no back (MatrizPermissoes.java).

INSERT INTO perfil (nome, descricao) VALUES
    ('GESTOR', 'Aprova etapas das cargas e publica versões'),
    ('INTEGRADOR_EXTERNO', 'Consome dados publicados via integração')
ON CONFLICT (nome) DO NOTHING;

ALTER TABLE usuario
    ADD COLUMN IF NOT EXISTS versao_sessao          INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS excecao_sod_autorizada BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS justificativa_sod      TEXT;

COMMENT ON COLUMN usuario.versao_sessao IS
    'Incrementada a cada inativação/troca de perfil; token JWT com versão diferente é recusado (US13 Tarefa 16).';
COMMENT ON COLUMN usuario.excecao_sod_autorizada IS
    'Exceção de Segregação de Funções: libera o Operador para aprovar/publicar, sempre com alerta SoD na auditoria (US13 Tarefa 15).';

-- Gestor de demonstração (mesma senha provisória dos outros usuários de demo da V28)
INSERT INTO usuario (nome, email, senha_hash, perfil_id, ativo, data_criacao)
SELECT 'Gestor de Publicação', 'gestor@bifrost.com', 'Troca123', p.id, TRUE, now()
  FROM perfil p
 WHERE p.nome = 'GESTOR'
ON CONFLICT (email) DO NOTHING;
