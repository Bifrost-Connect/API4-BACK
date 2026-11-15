-- V42: US13 — seed oficial de perfis e permissões (Tarefa 22)
--
-- ESPELHA o back-end: o código da permissão é o nome da constante do enum
-- com.bifrostconnect.api_geo.security.Permissao, e os vínculos são os de MatrizPermissoes.
-- Se um dos dois mudar no Java, esta tabela precisa mudar junto (nova migration).
--
-- Só toca em tabelas criadas na V41 (vazias) e insere dados. Não renomeia perfil existente
-- e não altera usuário. Idempotente nos INSERTs (ON CONFLICT DO NOTHING).
--
-- A exceção de SoD (usuario.excecao_sod_autorizada) continua no código: ela libera
-- GEOMETRIA_EDITAR, CARGA_APROVAR e VERSAO_PUBLICAR ao OPERADOR e não é linha de perfil_permissao.

-- ==========================================
-- 0) Ajuste da V41: os CHECKs de formato exigiam "MODULO.SUBPERMISSAO", mas os códigos
--    reais são os nomes do enum (ex.: CARGA_ENVIAR). A tabela está vazia, então é seguro.
-- ==========================================
ALTER TABLE permissao DROP CONSTRAINT IF EXISTS ck_permissao_codigo_formato;
ALTER TABLE permissao DROP CONSTRAINT IF EXISTS ck_permissao_codigo_do_modulo;

ALTER TABLE permissao
    ADD CONSTRAINT ck_permissao_codigo_formato
        CHECK (codigo_permissao ~ '^[A-Z][A-Z_]*$');

COMMENT ON TABLE permissao IS
    'Catálogo de permissões granulares do RBAC. O código é o nome da constante do enum Permissao no back (ex.: CARGA_ENVIAR).';
COMMENT ON COLUMN permissao.codigo_permissao IS
    'Identificador único da permissão, em MAIÚSCULAS, igual ao nome da constante do enum Permissao (ex.: VERSAO_PUBLICAR).';
COMMENT ON COLUMN permissao.modulo IS
    'Módulo da permissão, igual ao do enum: CARGAS, QUARENTENA, TERRITORIAL, PUBLICACAO, CONSUMO ou ADMINISTRACAO.';

-- ==========================================
-- 1) Perfis que faltam (ADMINISTRADOR, OPERADOR, AUDITOR e ANALISTA já existem na V1)
-- ==========================================
INSERT INTO perfil (nome, descricao) VALUES
    ('GESTOR',             'Governanca, homologacao, publicacao e exportacao'),
    ('INTEGRADOR_EXTERNO', 'Sistema parceiro que consome a API externa por chave de API')
ON CONFLICT (nome) DO NOTHING;

-- Auditor é somente leitura (MatrizPermissoes: sem mutação, sem download).
UPDATE perfil SET somente_leitura_estrito = TRUE WHERE nome = 'AUDITOR';

-- ==========================================
-- 2) Permissões (20), iguais ao enum Permissao
-- ==========================================
INSERT INTO permissao (codigo_permissao, modulo, descricao) VALUES
    -- cargas
    ('CARGA_ENVIAR',         'CARGAS',        'Subir carga, cadastrar metadados, processar e reprocessar'),
    ('CARGA_CANCELAR',       'CARGAS',        'Cancelar / excluir uma carga'),
    ('CARGA_CONSULTAR',      'CARGAS',        'Acompanhar cargas, logs e cálculos'),
    ('CARGA_ATRIBUIR',       'CARGAS',        'Alocar auditor/editor responsável pela carga'),
    ('ARQUIVO_BAIXAR',       'CARGAS',        'Baixar arquivo original da carga'),
    -- quarentena
    ('QUARENTENA_CONSULTAR', 'QUARENTENA',    'Ver dados e ocorrências da quarentena'),
    ('QUARENTENA_EXPORTAR',  'QUARENTENA',    'Exportar relatório de rejeições'),
    ('NOTIFICACAO_ENVIAR',   'QUARENTENA',    'Notificar operador/auditor sobre a carga'),
    -- dados territoriais
    ('GEOMETRIA_EDITAR',     'TERRITORIAL',   'Editar geometrias e importar camadas de referência'),
    ('ANALISE_EXECUTAR',     'TERRITORIAL',   'Rodar cruzamentos territoriais'),
    -- gates e publicação
    ('CARGA_APROVAR',        'PUBLICACAO',    'Aprovar/reprovar etapas da carga'),
    ('VERSAO_PUBLICAR',      'PUBLICACAO',    'Fechar e publicar versão oficial'),
    ('PUBLICACAO_CONSULTAR', 'PUBLICACAO',    'Revisar pacote de publicação e histórico de decisões'),
    ('RELATORIO_EXPORTAR',   'PUBLICACAO',    'Exportar versão publicada'),
    -- consumo
    ('CATALOGO_CONSULTAR',   'CONSUMO',       'Consultar catálogo de bases'),
    ('MAPA_CONSULTAR',       'CONSUMO',       'Consultar mapa de dados publicados'),
    ('LINHAGEM_CONSULTAR',   'CONSUMO',       'Consultar linhagem/rastreabilidade'),
    ('DOMINIO_CONSULTAR',    'CONSUMO',       'Listas de domínio (órgãos, conjuntos, situações)'),
    -- administração
    ('USUARIO_GERENCIAR',    'ADMINISTRACAO', 'Cadastrar, inativar e trocar perfil de usuários'),
    ('GOVERNANCA_CONSULTAR', 'ADMINISTRACAO', 'Matriz de permissões e logs de segurança')
ON CONFLICT (codigo_permissao) DO NOTHING;

-- ==========================================
-- 3) Vínculos (54), iguais a MatrizPermissoes. CONSUMO = CATALOGO, MAPA, LINHAGEM, DOMINIO.
-- ==========================================
INSERT INTO perfil_permissao (perfil_id, permissao_id)
SELECT p.id, pm.id
FROM (VALUES
    -- ADMINISTRADOR (8): CONSUMO + administração
    ('ADMINISTRADOR', 'CATALOGO_CONSULTAR'),
    ('ADMINISTRADOR', 'MAPA_CONSULTAR'),
    ('ADMINISTRADOR', 'LINHAGEM_CONSULTAR'),
    ('ADMINISTRADOR', 'DOMINIO_CONSULTAR'),
    ('ADMINISTRADOR', 'USUARIO_GERENCIAR'),
    ('ADMINISTRADOR', 'GOVERNANCA_CONSULTAR'),
    ('ADMINISTRADOR', 'CARGA_CONSULTAR'),
    ('ADMINISTRADOR', 'PUBLICACAO_CONSULTAR'),

    -- OPERADOR (13)
    ('OPERADOR', 'CATALOGO_CONSULTAR'),
    ('OPERADOR', 'MAPA_CONSULTAR'),
    ('OPERADOR', 'LINHAGEM_CONSULTAR'),
    ('OPERADOR', 'DOMINIO_CONSULTAR'),
    ('OPERADOR', 'CARGA_ENVIAR'),
    ('OPERADOR', 'CARGA_CANCELAR'),
    ('OPERADOR', 'CARGA_CONSULTAR'),
    ('OPERADOR', 'ARQUIVO_BAIXAR'),
    ('OPERADOR', 'QUARENTENA_CONSULTAR'),
    ('OPERADOR', 'QUARENTENA_EXPORTAR'),
    ('OPERADOR', 'NOTIFICACAO_ENVIAR'),
    ('OPERADOR', 'ANALISE_EXECUTAR'),
    ('OPERADOR', 'PUBLICACAO_CONSULTAR'),

    -- AUDITOR (7): somente leitura
    ('AUDITOR', 'CATALOGO_CONSULTAR'),
    ('AUDITOR', 'MAPA_CONSULTAR'),
    ('AUDITOR', 'LINHAGEM_CONSULTAR'),
    ('AUDITOR', 'DOMINIO_CONSULTAR'),
    ('AUDITOR', 'CARGA_CONSULTAR'),
    ('AUDITOR', 'QUARENTENA_CONSULTAR'),
    ('AUDITOR', 'PUBLICACAO_CONSULTAR'),

    -- GESTOR (17)
    ('GESTOR', 'CATALOGO_CONSULTAR'),
    ('GESTOR', 'MAPA_CONSULTAR'),
    ('GESTOR', 'LINHAGEM_CONSULTAR'),
    ('GESTOR', 'DOMINIO_CONSULTAR'),
    ('GESTOR', 'CARGA_CONSULTAR'),
    ('GESTOR', 'CARGA_CANCELAR'),
    ('GESTOR', 'CARGA_ATRIBUIR'),
    ('GESTOR', 'CARGA_APROVAR'),
    ('GESTOR', 'VERSAO_PUBLICAR'),
    ('GESTOR', 'ARQUIVO_BAIXAR'),
    ('GESTOR', 'QUARENTENA_CONSULTAR'),
    ('GESTOR', 'QUARENTENA_EXPORTAR'),
    ('GESTOR', 'NOTIFICACAO_ENVIAR'),
    ('GESTOR', 'GEOMETRIA_EDITAR'),
    ('GESTOR', 'ANALISE_EXECUTAR'),
    ('GESTOR', 'PUBLICACAO_CONSULTAR'),
    ('GESTOR', 'RELATORIO_EXPORTAR'),

    -- ANALISTA (6)
    ('ANALISTA', 'CATALOGO_CONSULTAR'),
    ('ANALISTA', 'MAPA_CONSULTAR'),
    ('ANALISTA', 'LINHAGEM_CONSULTAR'),
    ('ANALISTA', 'DOMINIO_CONSULTAR'),
    ('ANALISTA', 'ANALISE_EXECUTAR'),
    ('ANALISTA', 'RELATORIO_EXPORTAR'),

    -- INTEGRADOR_EXTERNO (3)
    ('INTEGRADOR_EXTERNO', 'CATALOGO_CONSULTAR'),
    ('INTEGRADOR_EXTERNO', 'MAPA_CONSULTAR'),
    ('INTEGRADOR_EXTERNO', 'RELATORIO_EXPORTAR')
) AS v(perfil_nome, codigo)
JOIN perfil    p  ON p.nome = v.perfil_nome
JOIN permissao pm ON pm.codigo_permissao = v.codigo
ON CONFLICT (perfil_id, permissao_id) DO NOTHING;

-- ==========================================
-- 4) Conferência: um nome errado faria o JOIN descartar a linha em silêncio.
-- Se algo não bater, a migration inteira volta.
-- Administrador inicial: já criado pela V28 (admin@bifrost.com). Aqui só se verifica,
-- sem gravar senha nem alterar usuário.
-- ==========================================
DO $$
DECLARE
    v_perfis      INTEGER;
    v_permissoes  INTEGER;
    v_vinculos    INTEGER;
    v_admins      INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_perfis
      FROM perfil
     WHERE nome IN ('ADMINISTRADOR', 'OPERADOR', 'AUDITOR', 'GESTOR', 'ANALISTA', 'INTEGRADOR_EXTERNO');
    IF v_perfis <> 6 THEN
        RAISE EXCEPTION 'Seed RBAC: esperados 6 perfis oficiais, encontrados %.', v_perfis;
    END IF;

    SELECT COUNT(*) INTO v_permissoes FROM permissao;
    IF v_permissoes <> 20 THEN
        RAISE EXCEPTION 'Seed RBAC: esperadas 20 permissoes, encontradas %.', v_permissoes;
    END IF;

    SELECT COUNT(*) INTO v_vinculos FROM perfil_permissao;
    IF v_vinculos <> 54 THEN
        RAISE EXCEPTION 'Seed RBAC: esperados 54 vinculos perfil x permissao, encontrados %.', v_vinculos;
    END IF;

    SELECT COUNT(*) INTO v_admins
      FROM usuario u
      JOIN perfil p ON p.id = u.perfil_id
     WHERE p.nome = 'ADMINISTRADOR' AND u.ativo;
    IF v_admins < 1 THEN
        RAISE EXCEPTION 'Seed RBAC: nenhum usuario ADMINISTRADOR ativo encontrado.';
    END IF;
END;
$$;
