"""Armazenamento do arquivo original (valor de arquivo_original.url_armazenamento)."""
from __future__ import annotations

import logging
import os
import shutil

from . import config

log = logging.getLogger(__name__)


def guardar(zip_path: str, sha256: str, nome: str) -> str:
    """Guarda o arquivo e devolve a URL/caminho. A chave inclui o sha256: reenvio sobrescreve o mesmo objeto."""
    if config.STORAGE_BACKEND == "local":
        return _local(zip_path, sha256, nome)
    if config.STORAGE_BACKEND == "oci":
        return _oci(zip_path, sha256, nome)
    raise ValueError(f"STORAGE_BACKEND invalido: {config.STORAGE_BACKEND} (use oci ou local)")


def _local(zip_path: str, sha256: str, nome: str) -> str:
    destino = os.path.join(config.COFRE_LOCAL_DIR, sha256[:2], sha256, nome)
    os.makedirs(os.path.dirname(destino), exist_ok=True)
    shutil.copyfile(zip_path, destino)
    return destino


def _oci(zip_path: str, sha256: str, nome: str) -> str:
    import oci  # import tardio: so a task de carga precisa do SDK

    if not config.OCI_BUCKET:
        raise ValueError("OCI_BUCKET nao definido (.env)")

    if config.OCI_AUTH == "instance_principal":
        signer = oci.auth.signers.InstancePrincipalsSecurityTokenSigner()
        client = oci.object_storage.ObjectStorageClient(config={"region": signer.region}, signer=signer)
    else:
        cfg = oci.config.from_file(os.path.expanduser(config.OCI_CONFIG_FILE))
        client = oci.object_storage.ObjectStorageClient(cfg)

    namespace = config.OCI_NAMESPACE or client.get_namespace().data
    objeto = f"cargas/{sha256}/{nome}"
    log.info("enviando %s para oci://%s@%s/%s", nome, config.OCI_BUCKET, namespace, objeto)

    manager = oci.object_storage.UploadManager(client, allow_parallel_uploads=True)
    manager.upload_file(namespace, config.OCI_BUCKET, objeto, zip_path, content_type="application/zip")
    return f"oci://{config.OCI_BUCKET}@{namespace}/{objeto}"
