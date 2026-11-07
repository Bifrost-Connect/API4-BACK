-- V32: US09 — acesso do Analista ao catálogo
--
-- "Geometrias_Tratamento" = feicao_carga. "Log_Validacao_Quarentena" = log_validacao.
-- A view roda com os direitos do dono (root), então o Analista lê o catálogo
-- sem precisar de nenhum acesso às tabelas de base.
-- Sem senha aqui (definir fora do Git: ALTER ROLE analista_mapa PASSWORD '...').

GRANT SELECT ON vw_catalogo_bases_operacional TO analista_mapa;

-- Reforça (a V23 já fez REVOKE ALL em todas as tabelas): nenhum acesso direto
-- às geometrias tratadas nem ao log de quarentena.
REVOKE ALL ON feicao_carga   FROM analista_mapa;
REVOKE ALL ON log_validacao  FROM analista_mapa;