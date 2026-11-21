"""Configuracao do pipeline: variaveis de ambiente + mapa arquivo do Drive -> destino no banco."""
from __future__ import annotations

import os
import re
from dataclasses import dataclass

TZ = "America/Sao_Paulo"

# --- ambiente (definido no .env / docker-compose) ---------------------------------------
DRIVE_ROOT_FOLDER_ID = os.environ.get("DRIVE_ROOT_FOLDER_ID", "")
DRIVE_CONN_ID = os.environ.get("DRIVE_CONN_ID", "google_drive_sa")
GEO_CONN_ID = os.environ.get("GEO_CONN_ID", "postgres_geo")
DISCORD_CONN_ID = os.environ.get("DISCORD_CONN_ID", "discord_webhook")

DATA_DIR = os.environ.get("DATA_DIR_CONTAINER", "/opt/airflow/data")

STORAGE_BACKEND = os.environ.get("STORAGE_BACKEND", "local")  # local | oci
OCI_BUCKET = os.environ.get("OCI_BUCKET", "")
OCI_NAMESPACE = os.environ.get("OCI_NAMESPACE", "")
OCI_AUTH = os.environ.get("OCI_AUTH", "instance_principal")  # instance_principal | config_file
OCI_CONFIG_FILE = os.environ.get("OCI_CONFIG_FILE", "~/.oci/config")
COFRE_LOCAL_DIR = os.environ.get("COFRE_LOCAL_DIR", "/opt/airflow/data/cofre")

SICAR_WFS_URL = os.environ.get("SICAR_WFS_URL", "https://geoserver.car.gov.br/geoserver/sicar/wfs")
BACKEND_API_URL = os.environ.get("BACKEND_API_URL", "http://host.docker.internal:8080/api")
AIRFLOW_INTERNAL_TOKEN = os.environ.get("AIRFLOW_INTERNAL_TOKEN", "geodatahub-airflow-internal-token")

UFS_BR = (
    "AC", "AL", "AM", "AP", "BA", "CE", "DF", "ES", "GO",
    "MA", "MG", "MS", "MT", "PA", "PB", "PE", "PI", "PR",
    "RJ", "RN", "RO", "RR", "RS", "SC", "SE", "SP", "TO",
)

OPERADOR_EMAIL = os.environ.get("OPERADOR_EMAIL", "airflow@georural.com.br")
CAR_ANO_SAFRA = os.environ.get("CAR_ANO_SAFRA", "")  # vazio = ano de modificacao do arquivo no Drive

# Formato gravado em processo.epsg_origem / epsg_destino / arquivo_processado.epsg.
# Aqui so os digitos ("4674"). Se o back usar "EPSG:4674", mude EPSG_PREFIXO.
EPSG_PREFIXO = os.environ.get("EPSG_PREFIXO", "")
EPSG_DESTINO = "4674"
EPSG_PADRAO_ORIGEM = os.environ.get("EPSG_PADRAO_ORIGEM", "4674")  # usado so se o arquivo vier sem .prj


@dataclass(frozen=True)
class Fonte:
    chave: str                       # identificador unico desta entrada
    destino: str                     # REFERENCIA | CARGA
    arquivo: str                     # regex (case-insensitive) no nome do arquivo
    pasta: str | None = None         # nome EXATO da pasta pai no Drive (opcional)
    fonte: str | None = None         # camada_referencia.fonte (REFERENCIA)
    nome_camada: str | None = None   # camada_referencia.nome_camada (REFERENCIA)
    id_colunas: tuple[str, ...] = () # candidatas (minusculas) p/ identificador_origem / codigo_imovel
    conjunto_nome: str | None = None # CARGA: conjunto.nome
    orgao_sigla: str | None = None   # CARGA: orgao.sigla
    habilitada: bool = True
    ja_carregada_manual: bool = False  # 1a vez: registra baseline (md5) sem recarregar
    encoding: str | None = None      # ENCODING do shapefile; None = GDAL decide (.cpg / LATIN1)
    obs: str = ""


FONTES: tuple[Fonte, ...] = (
    # --- CARGA (passa pela esteira) -------------------------------------------------------
    Fonte(
        chave="CAR_AREA_IMOVEL", destino="CARGA", arquivo=r"\.zip$", pasta="CAR",
        id_colunas=("cod_imovel", "cod_imovel_", "cod_car"),
        conjunto_nome="Imóveis rurais", orgao_sigla="SICAR",
    ),
    Fonte(
        chave="INCAR_AREA_IMOVEL", destino="REFERENCIA", arquivo=r"^AREA_IMOVEL\.zip$", pasta="INCAR",
        fonte="INCAR_AREA_IMOVEL", nome_camada="Área de Imóveis Rurais (SICAR/INCAR)",
        id_colunas=("cod_imovel", "cod_imovel_", "ogc_fid"), habilitada=False,
        obs="duplicata de CAR/AREA_IMOVEL.zip",
    ),
    # --- REFERENCIA ja carregadas a mao (baseline na 1a execucao) -------------------------
    Fonte(
        chave="FUNAI_TERRA_INDIGENA", destino="REFERENCIA", arquivo=r"^tis_poligonais",
        fonte="FUNAI_TERRA_INDIGENA", nome_camada="Terras Indígenas (FUNAI)",
        id_colunas=("terrai_cod", "ogc_fid"), ja_carregada_manual=True,
    ),
    Fonte(
        chave="IBAMA_EMBARGO", destino="REFERENCIA", arquivo=r"^adm_embargos_ibama",
        fonte="IBAMA_EMBARGO", nome_camada="Áreas Embargadas (IBAMA)",
        id_colunas=("num_tad", "num_tad_", "ogc_fid"), ja_carregada_manual=True,
    ),
    Fonte(
        chave="ICMBIO_UC_FEDERAL", destino="REFERENCIA", arquivo=r"limite_ucs_federais",
        fonte="ICMBIO_UC_FEDERAL", nome_camada="Unidades de Conservação Federais (ICMBio)",
        id_colunas=("cd_cnuc", "cnuc", "ogc_fid"), ja_carregada_manual=True,
    ),
    Fonte(
        chave="INCRA_QUILOMBOLA", destino="REFERENCIA", arquivo=r"Quilombolas",
        fonte="INCRA_QUILOMBOLA", nome_camada="Áreas de Quilombolas (INCRA)",
        id_colunas=("cd_quilomb", "ogc_fid"), ja_carregada_manual=True,
    ),
    Fonte(
        chave="IBGE_MUNICIPIO", destino="REFERENCIA", arquivo=r"^PR_Municipios",
        fonte="IBGE_MUNICIPIO", nome_camada="Municípios do Paraná (IBGE)",
        id_colunas=("cd_mun", "ogc_fid"), ja_carregada_manual=True,
    ),
    Fonte(
        chave="IBGE_BIOMA_250MIL", destino="REFERENCIA", arquivo=r"^Biomas_250mil",
        fonte="IBGE_BIOMA_250MIL", nome_camada="Biomas 1:250.000 (IBGE)",
        id_colunas=("cd_bioma", "ogc_fid"), ja_carregada_manual=True,
    ),
    Fonte(
        chave="IBGE_BIOMA_5000MIL", destino="REFERENCIA", arquivo=r"^Biomas_5000mil",
        fonte="IBGE_BIOMA_5000MIL", nome_camada="Biomas 1:5.000.000 (IBGE)",
        id_colunas=("cd_bioma", "ogc_fid"), ja_carregada_manual=True,
    ),
    # --- REFERENCIA pendentes (substituem os polígonos sinteticos da V29) -----------------
    Fonte(
        chave="INCRA_ASSENTAMENTO", destino="REFERENCIA", arquivo=r"^Assentamento Brasil",
        fonte="INCRA_ASSENTAMENTO", nome_camada="Assentamentos Brasil (INCRA)",
        id_colunas=("cd_sipra", "ogc_fid"),
    ),
    Fonte(
        chave="SFB_FLORESTA_PUBLICA", destino="REFERENCIA", arquivo=r"^CNFP",
        fonte="SFB_FLORESTA_PUBLICA", nome_camada="Cadastro Nacional de Florestas Públicas (SFB)",
        id_colunas=("cd_cnfp", "cod_cnfp", "ogc_fid"),
    ),
    Fonte(
        chave="INPE_DESMATAMENTO_CERRADO", destino="REFERENCIA",
        arquivo=r"^yearly_deforestation_biome_cerrado",
        fonte="INPE_DESMATAMENTO_CERRADO", nome_camada="Desmatamento Anual Cerrado PRODES (INPE)",
        id_colunas=("uid", "uuid", "ogc_fid"),
    ),
    Fonte(
        chave="INPE_DESMATAMENTO_MATA_ATLANTICA", destino="REFERENCIA",
        arquivo=r"^yearly_deforestation_biome_mata_atlantica",
        fonte="INPE_DESMATAMENTO_MATA_ATLANTICA", nome_camada="Desmatamento Anual Mata Atlântica PRODES (INPE)",
        id_colunas=("uid", "uuid", "ogc_fid"),
    ),
    Fonte(
        chave="INPE_QUEIMADA", destino="REFERENCIA", arquivo=r"^bdqueimadas",
        fonte="INPE_QUEIMADA", nome_camada="Focos de Queimadas BDQueimadas (INPE)",
        id_colunas=("id", "id_foco", "ogc_fid"),
    ),
    Fonte(
        chave="MMA_UC_CNUC", destino="REFERENCIA", arquivo=r"^cnuc_.*\.csv$",
        fonte="MMA_UC_CNUC", nome_camada="Cadastro Nacional de Unidades de Conservação (MMA)",
        id_colunas=("ogc_fid",), habilitada=False,
        obs="CSV: confirmar se tem geometria (WKT/lat-lon) antes de habilitar (nota da V18)",
    ),
)


def casar(nome: str, pasta: str | None) -> Fonte | None:
    """Primeira entrada cujo regex bate no nome (e cuja pasta, se informada, e a pasta pai)."""
    for f in FONTES:
        if f.pasta is not None and f.pasta != pasta:
            continue
        if re.search(f.arquivo, nome, re.IGNORECASE):
            return f
    return None


def por_chave(chave: str) -> Fonte:
    for f in FONTES:
        if f.chave == chave:
            return f
    raise KeyError(f"fonte desconhecida: {chave}")


def epsg_txt(codigo: str) -> str:
    return f"{EPSG_PREFIXO}{codigo}"
