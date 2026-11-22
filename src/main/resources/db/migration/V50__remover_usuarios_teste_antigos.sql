-- V50: Remove os usuários antigos de teste (@bifrost.com) criados nas migrations V15, V17, V28 e V41,
-- preservando os 5 usuários canônicos (@georural.com.br da V44) e a conta técnica do Airflow (V47).
--
-- Usuários sem vínculos históricos são excluídos fisicamente. Caso algum possua registros
-- protegidos por triggers de imutabilidade (ex.: arquivo_original, historico_aprovacoes_gestor,
-- log_exportacoes_relatorios), o registro é inativado (ativo = FALSE) para não falhar a migration.

DELETE FROM usuario u
 WHERE u.email IN (
           'vitor.operador@bifrost.com',
           'daniel.auditor@bifrost.com',
           'mock@bifrost.com',
           'admin@bifrost.com',
           'gestor@bifrost.com'
       )
   AND NOT EXISTS (SELECT 1 FROM auditoria a WHERE a.usuario_id = u.id)
   AND NOT EXISTS (SELECT 1 FROM chaves_api c WHERE c.usuario_id = u.id OR c.usuario_revogacao_id = u.id)
   AND NOT EXISTS (SELECT 1 FROM processo p WHERE p.operador_id = u.id OR p.gestor_publicacao_id = u.id)
   AND NOT EXISTS (SELECT 1 FROM arquivo_original ao WHERE ao.usuario_upload_id = u.id)
   AND NOT EXISTS (SELECT 1 FROM log_exportacoes_relatorios ler WHERE ler.usuario_id = u.id)
   AND NOT EXISTS (SELECT 1 FROM notificacao n WHERE n.usuario_id = u.id)
   AND NOT EXISTS (SELECT 1 FROM ocorrencia_validacao ov WHERE ov.auditor_id = u.id)
   AND NOT EXISTS (SELECT 1 FROM publicacao pub WHERE pub.usuario_responsavel_id = u.id)
   AND NOT EXISTS (SELECT 1 FROM historico_aprovacoes_gestor hag WHERE hag.gestor_id = u.id)
   AND NOT EXISTS (SELECT 1 FROM conector_fonte_dados cfd WHERE cfd.criado_por_id = u.id);

UPDATE usuario
   SET ativo = FALSE
 WHERE email IN (
           'vitor.operador@bifrost.com',
           'daniel.auditor@bifrost.com',
           'mock@bifrost.com',
           'admin@bifrost.com',
           'gestor@bifrost.com'
       );
