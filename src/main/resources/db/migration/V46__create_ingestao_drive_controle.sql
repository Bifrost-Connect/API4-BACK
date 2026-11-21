-- V46: controle da ingestao automatica a partir do Drive (Airflow) + schema de staging
--
-- O Airflow lista a pasta GEOESPACIAIS do Drive e compara o md5Checksum de cada arquivo com
-- esta tabela para decidir o que precisa ser (re)carregado. O md5 do Drive so serve para
-- evitar rebaixar o arquivo; a barreira final contra duplicidade continua sendo o
-- hash_sha256 UNIQUE de arquivo_original (V4).
--
-- O schema stg guarda as tabelas temporarias do ogr2ogr (uma por arquivo, apagada ao final).

CREATE SCHEMA IF NOT EXISTS stg;

COMMENT ON SCHEMA stg IS
    'Staging das cargas automaticas (Airflow/ogr2ogr). Tabelas temporarias, apagadas ao fim de cada arquivo.';

CREATE TABLE ingestao_drive_controle (
    id                   BIGSERIAL PRIMARY KEY,

    -- Identificacao do arquivo no Drive (nome sozinho nao serve: AREA_IMOVEL.zip existe em 2 pastas)
    drive_file_id        VARCHAR(100) NOT NULL,
    drive_caminho        VARCHAR(500) NOT NULL,
    nome_arquivo         VARCHAR(255) NOT NULL,

    destino              VARCHAR(20)  NOT NULL,
    fonte                VARCHAR(50),

    md5_drive            VARCHAR(32)  NOT NULL,
    hash_sha256          VARCHAR(64),
    tamanho_bytes        BIGINT,

    status               VARCHAR(20)  NOT NULL,
    processo_id          BIGINT REFERENCES processo(id),
    linhas_carregadas    BIGINT,
    linhas_descartadas   BIGINT,
    mensagem             TEXT,
    dag_run_id           VARCHAR(250),

    data_inicio          TIMESTAMP NOT NULL DEFAULT now(),
    data_fim             TIMESTAMP,

    CONSTRAINT ck_ingestao_drive_destino
        CHECK (destino IN ('REFERENCIA', 'CARGA')),
    CONSTRAINT ck_ingestao_drive_status
        CHECK (status IN ('EM_ANDAMENTO', 'CARREGADO', 'FALHOU', 'BASELINE')),
    CONSTRAINT ck_ingestao_drive_referencia_fonte
        CHECK (destino <> 'REFERENCIA' OR fonte IS NOT NULL),
    CONSTRAINT ck_ingestao_drive_md5
        CHECK (md5_drive ~ '^[0-9a-f]{32}$'),
    CONSTRAINT ck_ingestao_drive_sha256
        CHECK (hash_sha256 IS NULL OR hash_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ingestao_drive_carga_processo
        CHECK (NOT (destino = 'CARGA' AND status = 'CARREGADO') OR processo_id IS NOT NULL),
    CONSTRAINT ck_ingestao_drive_datas
        CHECK (data_fim IS NULL OR data_fim >= data_inicio)
);

-- Uma versao (md5) de cada arquivo so pode estar "carregada" uma vez.
-- Tentativas FALHOU e EM_ANDAMENTO podem se repetir (retry).
CREATE UNIQUE INDEX uq_ingestao_drive_arquivo_md5
    ON ingestao_drive_controle (drive_file_id, md5_drive)
    WHERE status IN ('CARREGADO', 'BASELINE');

CREATE INDEX idx_ingestao_drive_arquivo ON ingestao_drive_controle (drive_file_id, data_inicio DESC);
CREATE INDEX idx_ingestao_drive_run     ON ingestao_drive_controle (dag_run_id);
CREATE INDEX idx_ingestao_drive_processo ON ingestao_drive_controle (processo_id);

COMMENT ON TABLE ingestao_drive_controle IS
    'Historico das cargas automaticas feitas pelo Airflow a partir do Drive. Uma linha por tentativa.';
COMMENT ON COLUMN ingestao_drive_controle.status IS
    'EM_ANDAMENTO | CARREGADO | FALHOU | BASELINE (arquivo ja carregado a mao antes do Airflow; registrado sem recarregar).';
COMMENT ON COLUMN ingestao_drive_controle.destino IS
    'REFERENCIA = camada_referencia (carga direta); CARGA = processo/arquivo_original/feicao_carga.';
