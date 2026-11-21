"""Downloader multi-protocolo (OGC_WFS, ARCGIS_REST, HTTP_DIRECT, GOOGLE_DRIVE) com hashing em streaming."""
from __future__ import annotations

import base64
import hashlib
import json
import logging
import os
import re
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass

from . import config

log = logging.getLogger(__name__)

CHUNK_SIZE = 8 * 1024 * 1024
HTTP_TIMEOUT = (30, 300)  # (connect_s, read_s)

_FORMAT_META = {
    "SHAPE-ZIP": (".zip", "application/zip"),
    "ZIP": (".zip", "application/zip"),
    "GEOJSON": (".geojson", "application/geo+json"),
    "JSON": (".json", "application/json"),
    "GPKG": (".gpkg", "application/geopackage+sqlite3"),
    "CSV": (".csv", "text/csv"),
}

_WFS_OUTPUT_FORMATS = {
    "SHAPE-ZIP": "SHAPE-ZIP",
    "GEOJSON": "application/json",
    "GPKG": "application/geopackage+sqlite3",
    "CSV": "csv",
}

_CQL_PARAM_KEYS = frozenset({
    "cqlFilter",
    "municipio",
    "cod_municipio_ibge",
    "cod_imovel",
    "status_imovel",
    "tipo_imovel",
})


@dataclass
class DownloadResult:
    caminho_arquivo: str
    nome_arquivo: str
    md5: str
    sha256: str
    tamanho_bytes: int
    extensao: str
    tipo_mime: str


def _escape_cql(valor: str) -> str:
    return str(valor).replace("'", "''")


def construir_cql_sicar(params: dict) -> str | None:
    """Constroi OGC CQL_FILTER sanitizado a partir de parametros do SICAR/WFS."""
    if not params:
        return None

    cql_raw = params.get("cqlFilter")
    if cql_raw is not None and str(cql_raw).strip():
        return str(cql_raw).strip()

    partes: list[str] = []

    municipio = params.get("municipio")
    if municipio is not None and str(municipio).strip():
        mun_esc = _escape_cql(str(municipio).strip().upper())
        partes.append(f"strToUpperCase(municipio) LIKE '%{mun_esc}%'")

    cod_ibge = params.get("cod_municipio_ibge")
    if cod_ibge is not None and str(cod_ibge).strip():
        cod_str = str(cod_ibge).strip()
        if cod_str.isdigit():
            partes.append(f"cod_municipio_ibge = {cod_str}")
        else:
            partes.append(f"cod_municipio_ibge = '{_escape_cql(cod_str)}'")

    for campo in ("cod_imovel", "status_imovel", "tipo_imovel"):
        val = params.get(campo)
        if val is not None and str(val).strip():
            partes.append(f"{campo} = '{_escape_cql(str(val).strip())}'")

    return " AND ".join(partes) if partes else None


def montar_auth_http(
    tipo_auth: str,
    nome_credencial: str | None,
    credencial_aberta: str | None,
    url: str,
    params: dict,
    headers: dict,
) -> tuple[str, dict, dict, tuple[str, str] | None]:
    """Aplica esquema de autenticacao HTTP retornando (url, params, headers, auth_tuple)."""
    tipo = (tipo_auth or "NONE").upper()
    out_params = dict(params)
    out_headers = dict(headers)

    if tipo == "NONE":
        return url, out_params, out_headers, None

    if credencial_aberta is None:
        raise ValueError(f"credencial_aberta obrigatoria para tipo_autenticacao={tipo}")

    if tipo == "API_KEY_HEADER":
        out_headers[nome_credencial or "X-API-Key"] = credencial_aberta
        return url, out_params, out_headers, None

    if tipo == "API_KEY_QUERY":
        if "{API_KEY}" in url:
            return url.replace("{API_KEY}", credencial_aberta), out_params, out_headers, None
        out_params[nome_credencial or "api_key"] = credencial_aberta
        return url, out_params, out_headers, None

    if tipo == "BEARER_TOKEN":
        out_headers["Authorization"] = f"Bearer {credencial_aberta}"
        return url, out_params, out_headers, None

    if tipo == "BASIC_AUTH":
        user, _, password = credencial_aberta.partition(":")
        return url, out_params, out_headers, (user, password)

    raise ValueError(f"tipo_autenticacao HTTP nao suportado: {tipo}")


class _UrllibStreamResponse:
    """Adaptador minimo para fallback urllib com interface compativel com requests.Response."""

    def __init__(self, resp):
        self._resp = resp
        self.status_code = getattr(resp, "status", 200)
        self.headers = dict(resp.headers.items()) if resp.headers else {}

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")

    def iter_content(self, chunk_size: int = CHUNK_SIZE):
        while True:
            chunk = self._resp.read(chunk_size)
            if not chunk:
                break
            yield chunk


def _http_get(
    url: str,
    *,
    params: dict | None = None,
    headers: dict | None = None,
    auth: tuple[str, str] | None = None,
    stream: bool = True,
    timeout: tuple[int, int] = HTTP_TIMEOUT,
):
    """Executa GET com retry/backoff via requests (ou urllib fallback)."""
    try:
        import requests
        from requests.adapters import HTTPAdapter
        from urllib3.util.retry import Retry

        session = requests.Session()
        retry = Retry(
            total=3,
            backoff_factor=1.5,
            status_forcelist=(429, 500, 502, 503, 504),
            allowed_methods=frozenset({"GET"}),
        )
        adapter = HTTPAdapter(max_retries=retry)
        session.mount("http://", adapter)
        session.mount("https://", adapter)
        return session.get(url, params=params, headers=headers, auth=auth, stream=stream, timeout=timeout)
    except ImportError:
        full_url = url
        if params:
            sep = "&" if "?" in full_url else "?"
            full_url = f"{full_url}{sep}{urllib.parse.urlencode(params)}"
        req_headers = dict(headers or {})
        if auth:
            token = base64.b64encode(f"{auth[0]}:{auth[1]}".encode("utf-8")).decode("ascii")
            req_headers["Authorization"] = f"Basic {token}"
        req = urllib.request.Request(full_url, headers=req_headers, method="GET")
        try:
            resp = urllib.request.urlopen(req, timeout=timeout[1])
            return _UrllibStreamResponse(resp)
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"HTTP {e.code}: {e.reason}") from e


def _extrair_drive_id(url_ou_id: str, params: dict) -> str:
    if params.get("drive_file_id"):
        return str(params["drive_file_id"])
    if params.get("file_id"):
        return str(params["file_id"])
    m = re.search(r"/d/([A-Za-z0-9_-]+)", url_ou_id)
    if m:
        return m.group(1)
    m = re.search(r"[?&]id=([A-Za-z0-9_-]+)", url_ou_id)
    if m:
        return m.group(1)
    return url_ou_id.strip()


def _extrair_nome_cd(headers: dict) -> str | None:
    cd = headers.get("Content-Disposition") or headers.get("content-disposition") or ""
    m = re.search(r'filename\*?=(?:UTF-8\'\')?"?([^";]+)"?', cd, re.IGNORECASE)
    return os.path.basename(m.group(1).strip()) if m else None


def _meta_formato(formato_dados: str, tipo_protocolo: str) -> tuple[str, str]:
    fmt = (formato_dados or "").upper()
    if fmt in _FORMAT_META:
        return _FORMAT_META[fmt]
    if tipo_protocolo == "ARCGIS_REST":
        return ".geojson", "application/geo+json"
    return ".zip", "application/zip"


def _preparar_requisicao_http(
    conector: dict,
    params: dict,
    params_override: dict | None,
) -> tuple[str, dict]:
    protocolo = (conector.get("tipo_protocolo") or "HTTP_DIRECT").upper()
    url_base = conector["url_base"].strip()
    formato = (conector.get("formato_dados") or "SHAPE-ZIP").upper()

    if protocolo == "OGC_WFS":
        version = str(params.get("version") or "1.0.0")
        type_name = str(params.get("typeName") or params.get("typeNames") or params.get("layer") or "")
        uf = params.get("uf")
        override_has_type = bool(params_override and ("typeName" in params_override or "typeNames" in params_override))
        if uf and "sicar_imoveis_" in type_name and not override_has_type:
            type_name = re.sub(r"sicar_imoveis_[a-z]{2}$", f"sicar_imoveis_{str(uf).lower()}", type_name)

        query: dict = {
            "service": "WFS",
            "version": version,
            "request": "GetFeature",
            "outputFormat": str(params.get("outputFormat") or _WFS_OUTPUT_FORMATS.get(formato, formato)),
            "srsName": str(params.get("srsName") or f"EPSG:{conector.get('epsg_origem') or '4674'}"),
        }
        if type_name:
            query["typeNames" if version.startswith("2.") else "typeName"] = type_name
        max_feat = params.get("maxFeatures") or params.get("count")
        if max_feat is not None:
            query["count" if version.startswith("2.") else "maxFeatures"] = max_feat
        if params.get("bbox"):
            query["bbox"] = params["bbox"]
        cql = construir_cql_sicar(params)
        if cql:
            query["CQL_FILTER"] = cql
        return url_base, query

    if protocolo == "ARCGIS_REST":
        clean_url = url_base.rstrip("/")
        if not clean_url.endswith("/query"):
            clean_url = f"{clean_url}/query"
        query = dict(params)
        query.setdefault("f", "geojson")
        query.setdefault("where", "1=1")
        query.setdefault("outFields", "*")
        query.setdefault("outSR", str(conector.get("epsg_origem") or "4674"))
        return clean_url, query

    if protocolo == "HTTP_DIRECT":
        return url_base, dict(params)

    raise ValueError(f"tipo_protocolo nao suportado: {protocolo}")


def _validar_payload(caminho: str, formato_dados: str, extensao: str) -> None:
    fmt = (formato_dados or "").upper()
    if fmt == "SHAPE-ZIP" or extensao == ".zip":
        if not zipfile.is_zipfile(caminho):
            with open(caminho, "rb") as fh:
                snippet = fh.read(500).decode("utf-8", errors="replace").strip()
            m = re.search(r"<(?:ows:)?ExceptionText[^>]*>(.*?)</(?:ows:)?ExceptionText>|<ServiceException[^>]*>(.*?)</ServiceException>", snippet, re.DOTALL | re.IGNORECASE)
            detalhe = (m.group(1) or m.group(2)).strip() if m else snippet
            raise RuntimeError(f"Resposta nao e um ZIP valido: {detalhe[:500]}")
        return

    if fmt == "GEOJSON" or extensao in (".geojson", ".json"):
        with open(caminho, "rb") as fh:
            amostra = fh.read(500).decode("utf-8", errors="replace").lstrip()
        if not amostra.startswith(("{", "[")):
            raise RuntimeError(f"Resposta GeoJSON/JSON invalida: {amostra[:500]}")


def baixar_conector(
    conector: dict,
    params_override: dict | None,
    credencial_aberta: str | None,
    destino_dir: str,
) -> DownloadResult:
    """Baixa dados de um conector configurado para destino_dir, calcula hashes e valida payload."""
    os.makedirs(destino_dir, exist_ok=True)
    raw_params = conector.get("parametros_json") or {}
    if isinstance(raw_params, str):
        raw_params = json.loads(raw_params) if raw_params.strip() else {}
    params = {**raw_params, **(params_override or {})}

    protocolo = (conector.get("tipo_protocolo") or "HTTP_DIRECT").upper()
    formato_dados = (conector.get("formato_dados") or "SHAPE-ZIP").upper()
    ext_padrao, mime_padrao = _meta_formato(formato_dados, protocolo)

    if protocolo == "GOOGLE_DRIVE":
        from . import drive

        file_id = _extrair_drive_id(conector["url_base"], params)
        nome_arquivo = str(params.get("nome_arquivo") or f"drive_{file_id}{ext_padrao}")
        caminho = os.path.join(destino_dir, nome_arquivo)
        if (conector.get("tipo_autenticacao") or "").upper() == "SERVICE_ACCOUNT_JSON" and credencial_aberta:
            svc = drive.servico_por_json(credencial_aberta)
        else:
            svc = drive.servico(config.DRIVE_CONN_ID)
        md5_hex, sha_hex = drive.baixar(svc, file_id, caminho)
        tamanho = os.path.getsize(caminho)
        ext = os.path.splitext(nome_arquivo)[1].lower() or ext_padrao
        _validar_payload(caminho, formato_dados, ext)
        return DownloadResult(caminho, nome_arquivo, md5_hex, sha_hex, tamanho, ext, mime_padrao)

    url, query_params = _preparar_requisicao_http(conector, params, params_override)
    url, query_params, headers, auth = montar_auth_http(
        conector.get("tipo_autenticacao") or "NONE",
        conector.get("nome_credencial"),
        credencial_aberta,
        url,
        query_params,
        {},
    )

    resp = _http_get(url, params=query_params, headers=headers, auth=auth, stream=True, timeout=HTTP_TIMEOUT)
    resp.raise_for_status()

    nome_cd = _extrair_nome_cd(getattr(resp, "headers", {}) or {})
    slug = re.sub(r"[^A-Za-z0-9_.-]+", "_", conector.get("nome") or f"conector_{conector.get('id', 'dl')}").strip("_")
    nome_arquivo = nome_cd or str(params.get("nome_arquivo") or f"{slug}{ext_padrao}")
    ext = os.path.splitext(nome_arquivo)[1].lower() or ext_padrao
    if not os.path.splitext(nome_arquivo)[1]:
        nome_arquivo = f"{nome_arquivo}{ext}"
    caminho = os.path.join(destino_dir, nome_arquivo)

    md5 = hashlib.md5(usedforsecurity=False)
    sha = hashlib.sha256()
    tamanho = 0
    with open(caminho, "wb") as fh:
        for chunk in resp.iter_content(chunk_size=CHUNK_SIZE):
            if not chunk:
                continue
            fh.write(chunk)
            md5.update(chunk)
            sha.update(chunk)
            tamanho += len(chunk)

    _validar_payload(caminho, formato_dados, ext)
    return DownloadResult(caminho, nome_arquivo, md5.hexdigest(), sha.hexdigest(), tamanho, ext, mime_padrao)
