-- V47: usuario de servico do Airflow (perfil OPERADOR)
--
-- E o operador_id gravado nos processos criados pela carga automatica do CAR.
-- senha_hash e NOT NULL, mas esse usuario nunca faz login: o valor abaixo e gerado no proprio
-- banco na hora da migration, nao e guardado em lugar nenhum e nao e uma senha utilizavel
-- (prefixo '!' + hash aleatorio). Nenhuma senha fica no Git.

INSERT INTO usuario (nome, email, senha_hash, perfil_id, ativo, data_criacao)
SELECT 'Airflow (ingestao automatica)',
       'airflow@georural.com.br',
       '!' || md5(random()::text || clock_timestamp()::text),
       p.id,
       TRUE,
       now()
  FROM perfil p
 WHERE p.nome = 'OPERADOR'
ON CONFLICT (email) DO NOTHING;
