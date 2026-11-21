"""Wrappers do GDAL: extrai o zip, detecta o SRS e carrega a camada em staging (schema stg)."""
from __future__ import annotations

import logging
import os
import re
import shlex
import subprocess
import zipfile

log = logging.getLogger(__name__)

EXTENSOES_GEO = (".shp", ".gpkg", ".geojson", ".json", ".csv")


def _run(cmd: list[str], env: dict[str, str] | None = None) -> subprocess.CompletedProcess:
    log.info("exec: %s", " ".join(shlex.quote(c) for c in cmd))
    r = subprocess.run(cmd, capture_output=True, text=True, env={**os.environ, **(env or {})})
    if r.returncode != 0:
        raise RuntimeError(f"{cmd[0]} falhou (rc={r.returncode}): {(r.stderr or r.stdout)[-2000:]}")
    if r.stderr.strip():
        log.info("%s (stderr): %s", cmd[0], r.stderr[-1000:])
    return r


def extrair(zip_path: str, destino: str) -> list[str]:
    """Extrai o zip inteiro (shapefile precisa de .dbf/.shx/.prj/.cpg ao lado) e lista as camadas."""
    os.makedirs(destino, exist_ok=True)
    base = os.path.realpath(destino)
    achados: list[str] = []
    with zipfile.ZipFile(zip_path) as z:
        for m in z.infolist():
            if m.is_dir() or m.filename.startswith("__MACOSX"):
                continue
            alvo = os.path.realpath(os.path.join(base, m.filename))
            if not alvo.startswith(base + os.sep):
                raise ValueError(f"zip com caminho inseguro: {m.filename}")
            z.extract(m, base)
            if m.filename.lower().endswith(EXTENSOES_GEO):
                achados.append(alvo)
    if not achados:
        raise ValueError(f"nenhuma camada {EXTENSOES_GEO} encontrada dentro de {os.path.basename(zip_path)}")
    log.info("camadas no zip: %s", [os.path.basename(a) for a in achados])
    return sorted(achados)


def detectar_srs(caminho: str) -> tuple[str | None, bool]:
    """(epsg, tem_srs). epsg=None com tem_srs=True: ha SRS mas sem codigo EPSG (GDAL usa o WKT)."""
    saida = _run(["ogrinfo", "-so", "-al", caminho]).stdout
    linhas = saida.splitlines()
    try:
        i = next(n for n, l in enumerate(linhas) if l.startswith("Layer SRS WKT:"))
    except StopIteration:
        return None, False
    resto = linhas[i + 1 :]
    if not resto or resto[0].strip().startswith("(unknown)"):
        return None, False
    bloco: list[str] = []
    for l in resto:
        if l.startswith("Data axis") or re.match(r"^\S+: ", l) or l.startswith(("Geometry Column", "FID Column")):
            break
        bloco.append(l)
    ids = re.findall(r'(?:ID|AUTHORITY)\["EPSG",\s*"?(\d+)"?\]', "\n".join(bloco))
    return (ids[-1] if ids else None), True


def carregar_staging(
    conexao: dict[str, str],
    caminhos: list[str],
    tabela: str,
    s_srs: str | None,
    encoding: str | None,
) -> None:
    """ogr2ogr -> stg.<tabela>: reprojeta para 4674, promove para Multi, forca 2D, coluna geom/ogc_fid.

    Varias camadas entram na mesma tabela (a primeira cria, as demais fazem append).
    A senha vai por PGPASSWORD, nunca na linha de comando.
    """
    pg = f"PG:host={conexao['host']} port={conexao['port']} dbname={conexao['dbname']} user={conexao['user']}"
    if conexao.get("sslmode"):
        pg += f" sslmode={conexao['sslmode']}"
    env = {"PGPASSWORD": conexao["password"]}

    for i, caminho in enumerate(caminhos):
        cmd = [
            "ogr2ogr", "-f", "PostgreSQL", pg, caminho,
            "-nln", tabela,
            "-lco", "SCHEMA=stg",
            "-lco", "GEOMETRY_NAME=geom",
            "-lco", "FID=ogc_fid",
            "-lco", "PRECISION=NO",
            "-lco", "SPATIAL_INDEX=NONE",
            "-t_srs", "EPSG:4674",
            "-nlt", "PROMOTE_TO_MULTI",
            "-dim", "XY",
            "-gt", "65536",
            "-overwrite" if i == 0 else "-append",
        ]
        if s_srs:
            cmd += ["-s_srs", f"EPSG:{s_srs}"]
        if encoding and caminho.lower().endswith(".shp"):
            cmd += ["-oo", f"ENCODING={encoding}"]
        if caminho.lower().endswith(".csv"):
            cmd += [
                "-oo", "X_POSSIBLE_NAMES=longitude,lon,long,x",
                "-oo", "Y_POSSIBLE_NAMES=latitude,lat,y",
                "-oo", "KEEP_GEOM_COLUMNS=NO",
            ]
        _run(cmd, env=env)
