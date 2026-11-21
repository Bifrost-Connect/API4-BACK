-- V48: Cadastro genérico de conectores e fontes externas de ingestão (Airflow / GeoServer WFS / APIs REST / Drive)

CREATE TABLE conector_fonte_dados (
    id                      BIGSERIAL PRIMARY KEY,
    nome                    VARCHAR(120) NOT NULL,
    descricao               TEXT,

    tipo_protocolo          VARCHAR(30)  NOT NULL,
    url_base                VARCHAR(1000) NOT NULL,

    destino                 VARCHAR(20)  NOT NULL,
    fonte_referencia        VARCHAR(60),
    nome_camada_exibicao    VARCHAR(150),
    orgao_id                BIGINT REFERENCES orgao(id),
    conjunto_id             BIGINT REFERENCES conjunto(id),

    formato_dados           VARCHAR(30)  NOT NULL DEFAULT 'SHAPE-ZIP',
    epsg_origem             VARCHAR(10)  NOT NULL DEFAULT '4674',
    coluna_identificador    VARCHAR(120) NOT NULL DEFAULT 'cod_imovel,ogc_fid',
    parametros_json         JSONB        NOT NULL DEFAULT '{}'::jsonb,

    tipo_autenticacao       VARCHAR(30)  NOT NULL DEFAULT 'NONE',
    nome_credencial         VARCHAR(80),
    prefixo_credencial      VARCHAR(24),
    credencial_cifrada      TEXT,

    periodicidade           VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
    expressao_cron          VARCHAR(60),
    ativo                   BOOLEAN      NOT NULL DEFAULT TRUE,

    ultima_execucao_em      TIMESTAMP,
    proxima_execucao_em     TIMESTAMP,
    ultimo_status           VARCHAR(20),

    criado_por_id           BIGINT REFERENCES usuario(id),
    data_criacao            TIMESTAMP    NOT NULL DEFAULT now(),
    data_atualizacao        TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT uq_conector_fonte_nome UNIQUE (nome),
    CONSTRAINT ck_conector_protocolo
        CHECK (tipo_protocolo IN ('OGC_WFS', 'HTTP_DIRECT', 'GOOGLE_DRIVE', 'ARCGIS_REST')),
    CONSTRAINT ck_conector_destino
        CHECK (destino IN ('CARGA', 'REFERENCIA')),
    CONSTRAINT ck_conector_referencia_fonte
        CHECK (destino <> 'REFERENCIA' OR (fonte_referencia IS NOT NULL AND btrim(fonte_referencia) <> '')),
    CONSTRAINT ck_conector_auth
        CHECK (tipo_autenticacao IN ('NONE', 'API_KEY_HEADER', 'API_KEY_QUERY', 'BEARER_TOKEN', 'BASIC_AUTH', 'SERVICE_ACCOUNT_JSON')),
    CONSTRAINT ck_conector_periodicidade
        CHECK (periodicidade IN ('MANUAL', 'HORARIA', 'DIARIA', 'SEMANAL', 'MENSAL', 'CRON')),
    CONSTRAINT ck_conector_ultimo_status
        CHECK (ultimo_status IS NULL OR ultimo_status IN ('EM_ANDAMENTO', 'CARREGADO', 'FALHOU'))
);

CREATE INDEX idx_conector_fonte_ativo_prox
    ON conector_fonte_dados (ativo, proxima_execucao_em)
    WHERE ativo = TRUE AND periodicidade <> 'MANUAL';

ALTER TABLE ingestao_drive_controle
    ADD COLUMN IF NOT EXISTS conector_id BIGINT REFERENCES conector_fonte_dados(id) ON DELETE SET NULL;

ALTER TABLE ingestao_drive_controle
    ALTER COLUMN drive_caminho TYPE VARCHAR(1000),
    ALTER COLUMN fonte TYPE VARCHAR(60),
    ALTER COLUMN md5_drive DROP NOT NULL;

ALTER TABLE ingestao_drive_controle
    DROP CONSTRAINT IF EXISTS ck_ingestao_drive_md5;

ALTER TABLE ingestao_drive_controle
    ADD CONSTRAINT ck_ingestao_drive_md5
        CHECK (md5_drive IS NULL OR md5_drive ~ '^[0-9a-f]{32}$');

CREATE INDEX IF NOT EXISTS idx_ingestao_drive_conector
    ON ingestao_drive_controle (conector_id, data_inicio DESC);

COMMENT ON TABLE conector_fonte_dados IS
    'Conectores externos configuráveis (WFS, API REST/HTTP, ArcGIS, Google Drive) com parâmetros, chave de API cifrada e agendamento periódico.';

-- Conectores públicos iniciais pré-configurados (idempotentes)
INSERT INTO conector_fonte_dados (
    nome, descricao, tipo_protocolo, url_base, destino, fonte_referencia,
    nome_camada_exibicao, orgao_id, conjunto_id, formato_dados, epsg_origem,
    coluna_identificador, parametros_json, tipo_autenticacao, periodicidade, ativo
)
SELECT
    'SICAR Nacional - Imóveis Rurais (GeoServer WFS)',
    'Consulta direta ao GeoServer oficial do SICAR por UF, município, código CAR e situação.',
    'OGC_WFS',
    'https://geoserver.car.gov.br/geoserver/sicar/wfs',
    'CARGA',
    NULL,
    'Imóveis Rurais SICAR (WFS)',
    (SELECT id FROM orgao WHERE sigla = 'SICAR' LIMIT 1),
    (SELECT id FROM conjunto WHERE nome = 'Imóveis rurais' LIMIT 1),
    'SHAPE-ZIP',
    '4674',
    'cod_imovel,cod_imovel_,cod_car',
    '{"version": "1.0.0", "typeName": "sicar:sicar_imoveis_pr", "uf": "PR", "maxFeatures": 5000}'::jsonb,
    'NONE',
    'MANUAL',
    TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM conector_fonte_dados WHERE nome = 'SICAR Nacional - Imóveis Rurais (GeoServer WFS)'
);

INSERT INTO conector_fonte_dados (
    nome, descricao, tipo_protocolo, url_base, destino, fonte_referencia,
    nome_camada_exibicao, orgao_id, conjunto_id, formato_dados, epsg_origem,
    coluna_identificador, parametros_json, tipo_autenticacao, periodicidade, ativo
)
SELECT
    'INPE TerraBrasilis - PRODES Cerrado (WFS)',
    'Polígonos oficiais de desmatamento anual PRODES Cerrado publicados pelo INPE TerraBrasilis.',
    'OGC_WFS',
    'https://terrabrasilis.dpi.inpe.br/geoserver/ows',
    'REFERENCIA',
    'INPE_DESMATAMENTO_CERRADO',
    'Desmatamento Anual Cerrado PRODES (INPE)',
    NULL,
    (SELECT id FROM conjunto WHERE nome = 'Queimadas e Desmatamento' LIMIT 1),
    'SHAPE-ZIP',
    '4674',
    'uid,uuid,ogc_fid',
    '{"version": "1.0.0", "typeName": "prodes-cerrado-nb:accumulated_deforestation_2000", "maxFeatures": 5000}'::jsonb,
    'NONE',
    'MANUAL',
    TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM conector_fonte_dados WHERE nome = 'INPE TerraBrasilis - PRODES Cerrado (WFS)'
);

INSERT INTO conector_fonte_dados (
    nome, descricao, tipo_protocolo, url_base, destino, fonte_referencia,
    nome_camada_exibicao, orgao_id, conjunto_id, formato_dados, epsg_origem,
    coluna_identificador, parametros_json, tipo_autenticacao, periodicidade, ativo
)
SELECT
    'FUNAI - Terras Indígenas Poligonais (WFS)',
    'Limites oficiais de Terras Indígenas diretamente do GeoServer público da FUNAI.',
    'OGC_WFS',
    'https://geoserver.funai.gov.br/geoserver/ows',
    'REFERENCIA',
    'FUNAI_TERRA_INDIGENA',
    'Terras Indígenas (FUNAI)',
    (SELECT id FROM orgao WHERE sigla = 'FUNAI' LIMIT 1),
    (SELECT id FROM conjunto WHERE nome = 'Terras indígenas' LIMIT 1),
    'SHAPE-ZIP',
    '4674',
    'terrai_cod,ogc_fid',
    '{"version": "1.0.0", "typeName": "Funai:tis_poligonais", "maxFeatures": 5000}'::jsonb,
    'NONE',
    'MANUAL',
    TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM conector_fonte_dados WHERE nome = 'FUNAI - Terras Indígenas Poligonais (WFS)'
);

INSERT INTO conector_fonte_dados (
    nome, descricao, tipo_protocolo, url_base, destino, fonte_referencia,
    nome_camada_exibicao, orgao_id, conjunto_id, formato_dados, epsg_origem,
    coluna_identificador, parametros_json, tipo_autenticacao, periodicidade, ativo
)
SELECT
    'IBGE Geoserviços - Biomas 1:250.000 (WFS)',
    'Limites oficiais dos Biomas Brasileiros publicados via OGC WFS pelo IBGE.',
    'OGC_WFS',
    'https://geoservicos.ibge.gov.br/geoserver/ows',
    'REFERENCIA',
    'IBGE_BIOMA_250MIL',
    'Biomas 1:250.000 (IBGE)',
    (SELECT id FROM orgao WHERE sigla = 'IBGE' LIMIT 1),
    (SELECT id FROM conjunto WHERE nome = 'Limites de biomas' LIMIT 1),
    'SHAPE-ZIP',
    '4674',
    'cd_bioma,ogc_fid',
    '{"version": "1.0.0", "typeName": "CGMAT:qg_2025_240_bioma", "maxFeatures": 5000}'::jsonb,
    'NONE',
    'MANUAL',
    TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM conector_fonte_dados WHERE nome = 'IBGE Geoserviços - Biomas 1:250.000 (WFS)'
);
