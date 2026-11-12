-- V33: US13 Tarefa 18 — auditoria aceita evento sem usuário cadastrado
--
-- Mudança: auditoria.usuario_id deixa de ser obrigatório.
-- O ACESSO_NEGADO_403 grava toda tentativa barrada, e quem tenta pode ser um dos usuários de
-- memória do SecurityConfig, que não têm linha na tabela usuario. Nesses casos o login fica
-- no campo detalhes. Quem está no banco continua gravado com o id, e a FK segue valendo.

ALTER TABLE auditoria
    ALTER COLUMN usuario_id DROP NOT NULL;
