"""Acesso ao Google Drive (conta de servico, somente leitura)."""
from __future__ import annotations

import hashlib
import json
import logging

from airflow.hooks.base import BaseHook
from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaIoBaseDownload

log = logging.getLogger(__name__)

SCOPES = ["https://www.googleapis.com/auth/drive.readonly"]
MIME_PASTA = "application/vnd.google-apps.folder"
CHUNK_DOWNLOAD = 64 * 1024 * 1024
CHUNK_HASH = 8 * 1024 * 1024


def servico_por_json(info: dict | str):
    """Cliente Drive v3 a partir de dicionario ou string JSON de conta de servico."""
    if isinstance(info, str):
        info = json.loads(info)
    if "private_key" not in info:
        raise ValueError("Credencial nao contem a chave JSON da conta de servico")
    creds = service_account.Credentials.from_service_account_info(info, scopes=SCOPES)
    return build("drive", "v3", credentials=creds, cache_discovery=False)


def servico(conn_id: str):
    """Cliente Drive v3. A chave JSON da conta de servico fica no campo Extra da Connection."""
    conn = BaseHook.get_connection(conn_id)
    extra = conn.extra_dejson
    info = extra.get("keyfile_dict", extra)
    if isinstance(info, str):
        info = json.loads(info)
    if "private_key" not in info:
        raise ValueError(f"Connection '{conn_id}': Extra nao contem a chave JSON da conta de servico")
    return servico_por_json(info)


def listar_arquivos(svc, raiz_id: str) -> list[dict]:
    """Todos os arquivos (nao pastas) abaixo da pasta raiz, recursivamente."""
    resultado: list[dict] = []
    fila: list[tuple[str, list[str]]] = [(raiz_id, [])]
    while fila:
        pasta_id, caminho = fila.pop()
        token = None
        while True:
            resp = (
                svc.files()
                .list(
                    q=f"'{pasta_id}' in parents and trashed = false",
                    fields="nextPageToken, files(id, name, mimeType, md5Checksum, size, modifiedTime)",
                    pageSize=1000,
                    pageToken=token,
                    supportsAllDrives=True,
                    includeItemsFromAllDrives=True,
                )
                .execute(num_retries=5)
            )
            for f in resp.get("files", []):
                if f["mimeType"] == MIME_PASTA:
                    fila.append((f["id"], caminho + [f["name"]]))
                    continue
                resultado.append(
                    {
                        "drive_file_id": f["id"],
                        "nome": f["name"],
                        "md5": f.get("md5Checksum"),
                        "tamanho": int(f.get("size") or 0),
                        "modified": f["modifiedTime"],
                        "caminho": "/".join(caminho + [f["name"]]),
                        "pasta": caminho[-1] if caminho else None,
                    }
                )
            token = resp.get("nextPageToken")
            if not token:
                break
    return resultado


def baixar(svc, file_id: str, destino: str) -> tuple[str, str]:
    """Baixa em blocos para disco e devolve (md5, sha256) calculados do arquivo local."""
    req = svc.files().get_media(fileId=file_id, supportsAllDrives=True)
    ultimo = -1
    with open(destino, "wb") as fh:
        dl = MediaIoBaseDownload(fh, req, chunksize=CHUNK_DOWNLOAD)
        pronto = False
        while not pronto:
            status, pronto = dl.next_chunk(num_retries=5)
            if status:
                pct = int(status.progress() * 100)
                if pct // 10 != ultimo // 10:
                    log.info("download %s: %d%%", file_id, pct)
                    ultimo = pct

    md5 = hashlib.md5(usedforsecurity=False)
    sha = hashlib.sha256()
    with open(destino, "rb") as fh:
        while True:
            bloco = fh.read(CHUNK_HASH)
            if not bloco:
                break
            md5.update(bloco)
            sha.update(bloco)
    return md5.hexdigest(), sha.hexdigest()
