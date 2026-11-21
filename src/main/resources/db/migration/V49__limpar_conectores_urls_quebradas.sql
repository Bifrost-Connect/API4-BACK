-- V49: Limpeza de conectores com URLs descontinuadas/quebradas (ex: IBAMA PAMGIA 404)

DELETE FROM conector_fonte_dados
 WHERE url_base ILIKE '%pamgia.ibama.gov.br/dados/adm_embargos_ibama_a.zip%';
