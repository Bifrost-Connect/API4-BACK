"""Logica das tasks: planejar (Drive), processar (Drive e Conectores dinamicos) e resumir."""
from __future__ import annotations

import logging
import os
import re
import shutil
import urllib.request
from dataclasses import dataclass
from datetime import date, datetime, timezone

from psycopg2 import sql

from . import config, conectores, db, gdal, storage

log = logging.getLogger(__name__)

_FORMATO_POR_EXT = {
    ".shp": "Shapefile",
    ".gpkg": "GeoPackage",
    ".geojson": "GeoJSON",
    ".json": "GeoJSON",
    ".csv": "CSV",
}


@dataclass
class Resultado:
    processo_id: int | None
    linhas: int
    descartadas: int
    mensagem: str


# ======================================================================================
# planejar (Google Drive legado)
# ======================================================================================
def planejar(destino: str, forcar: list[str], run_id: str) -> list[dict]:
    """Lista o Drive e devolve so os arquivos deste destino que sao novos ou mudaram (md5)."""
    from . import drive

    if not config.DRIVE_ROOT_FOLDER_ID:
        raise ValueError("DRIVE_ROOT_FOLDER_ID nao definido (.env)")

    svc = drive.servico(config.DRIVE_CONN_ID)
    arquivos = drive.listar_arquivos(svc, config.DRIVE_ROOT_FOLDER_ID)
    log.info("%d arquivos no Drive", len(arquivos))

    itens: list[dict] = []
    for a in arquivos:
        cfg = config.casar(a["nome"], a["pasta"])
        if cfg is None:
            log.warning("sem mapeamento em config.FONTES (ignorado): %s", a["caminho"])
            continue
        if cfg.destino != destino:
            continue
        if not cfg.habilitada:
            log.info("desabilitado (%s): %s", cfg.obs or cfg.chave, a["caminho"])
            continue
        if not a["md5"]:
            log.warning("sem md5Checksum no Drive (arquivo nativo do Google?): %s", a["caminho"])
            continue

        ultimo = db.controle_ultimo_ok(a["drive_file_id"])
        forcado = cfg.chave in forcar
        if ultimo and ultimo["md5_drive"] == a["md5"] and not forcado:
            log.info("sem mudanca: %s", a["caminho"])
            continue

        if cfg.ja_carregada_manual and ultimo is None and not forcado:
            linhas = db.contar_fonte(cfg.fonte)
            db.controle_baseline(a, cfg.fonte, run_id, linhas)
            log.info("baseline registrado (sem recarregar): %s (%d linhas ja no banco)", a["caminho"], linhas)
            continue

        itens.append({**a, "chave": cfg.chave})
        log.info("a processar: %s", a["caminho"])
    return itens


# ======================================================================================
# processar (Google Drive legado)
# ======================================================================================
def processar(item: dict, run_id: str) -> dict:
    from . import drive

    cfg = config.por_chave(item["chave"])
    ctrl = db.controle_iniciar(item, cfg.fonte, cfg.destino, run_id)
    trabalho = os.path.join(config.DATA_DIR, "tmp", f"{_seguro(run_id)}_{item['drive_file_id']}")
    try:
        os.makedirs(trabalho, exist_ok=True)
        zip_path = os.path.join(trabalho, "origem.zip")
        if not item["nome"].lower().endswith(".zip"):
            raise ValueError(f"formato nao suportado (so .zip): {item['nome']}")

        svc = drive.servico(config.DRIVE_CONN_ID)
        md5, sha = drive.baixar(svc, item["drive_file_id"], zip_path)
        if md5 != item["md5"]:
            raise RuntimeError(
                f"md5 do download ({md5}) difere do md5 do Drive ({item['md5']}): "
                "arquivo corrompido ou alterado durante a execucao"
            )

        carga = _referencia if cfg.destino == "REFERENCIA" else _car
        res = carga(cfg, item, zip_path, trabalho, sha)
        db.controle_finalizar(
            ctrl, "CARREGADO", hash_sha256=sha, processo_id=res.processo_id,
            linhas_carregadas=res.linhas, linhas_descartadas=res.descartadas, mensagem=res.mensagem,
        )
        return {"arquivo": item["nome"], "linhas": res.linhas, "processo_id": res.processo_id}
    except Exception as e:
        db.controle_finalizar(ctrl, "FALHOU", mensagem=f"{type(e).__name__}: {e}")
        raise
    finally:
        shutil.rmtree(trabalho, ignore_errors=True)


# ======================================================================================
# processar_conector (conectores cadastrados em conector_fonte_dados)
# ======================================================================================
def processar_conector(
    conector_id: int,
    params_override: dict | None = None,
    credencial_aberta: str | None = None,
    ctrl_id: int | None = None,
    run_id: str = "",
) -> dict:
    """Executa ingestao completa de um conector (download multi-protocolo -> GDAL -> PostGIS -> pos-processamento)."""
    conector = db.buscar_conector(conector_id)
    if not conector:
        raise LookupError(f"Conector {conector_id} nao encontrado em conector_fonte_dados")

    destino = conector["destino"]
    fonte_ref = conector.get("fonte_referencia")
    fonte_ctrl = fonte_ref if destino == "REFERENCIA" else (fonte_ref or conector["nome"][:60])

    if ctrl_id is None:
        item_inicial = {
            "drive_file_id": f"conector:{conector_id}",
            "caminho": conector["url_base"],
            "nome": conector["nome"],
            "md5": None,
            "tamanho": 0,
        }
        ctrl_id = db.controle_iniciar(item_inicial, fonte_ctrl, destino, run_id, conector_id=conector_id)

    trabalho = os.path.join(config.DATA_DIR, "tmp", f"{_seguro(run_id or 'manual')}_conector_{conector_id}_{ctrl_id}")
    try:
        os.makedirs(trabalho, exist_ok=True)
        db.controle_atualizar_etapa(ctrl_id, "BAIXANDO", f"Conectando em {conector['tipo_protocolo']} ({conector['url_base']})")
        dl = conectores.baixar_conector(conector, params_override, credencial_aberta, trabalho)
        db.controle_atualizar_hashes(ctrl_id, dl.md5, dl.sha256, dl.tamanho_bytes, dl.nome_arquivo)

        id_colunas = tuple(
            c.strip() for c in (conector.get("coluna_identificador") or "ogc_fid").split(",") if c.strip()
        ) or ("ogc_fid",)
        agora_iso = datetime.now(timezone.utc).isoformat()

        if destino == "REFERENCIA":
            res = _carregar_referencia_conector(conector, dl, id_colunas, trabalho, agora_iso, ctrl_id)
        elif destino == "CARGA":
            res = _carregar_carga_conector(conector, dl, id_colunas, trabalho, agora_iso, ctrl_id)
        else:
            raise ValueError(f"destino invalido no conector {conector_id}: {destino}")

        db.controle_finalizar(
            ctrl_id,
            "CARREGADO",
            hash_sha256=dl.sha256,
            processo_id=res.processo_id,
            linhas_carregadas=res.linhas,
            linhas_descartadas=res.descartadas,
            mensagem=res.mensagem,
        )
        db.atualizar_status_conector(conector_id, "CARREGADO")
        return {
            "conector_id": conector_id,
            "arquivo": dl.nome_arquivo,
            "linhas": res.linhas,
            "processo_id": res.processo_id,
        }
    except Exception as e:
        db.controle_finalizar(ctrl_id, "FALHOU", mensagem=f"{type(e).__name__}: {e}")
        db.atualizar_status_conector(conector_id, "FALHOU")
        raise
    finally:
        shutil.rmtree(trabalho, ignore_errors=True)


def _preparar_camadas(caminho_arquivo: str, trabalho: str) -> list[str]:
    if caminho_arquivo.lower().endswith(".zip"):
        return gdal.extrair(caminho_arquivo, os.path.join(trabalho, "x"))
    if caminho_arquivo.lower().endswith(gdal.EXTENSOES_GEO):
        return [caminho_arquivo]
    raise ValueError(f"formato nao suportado: {caminho_arquivo}")


def _carregar_staging_padrao(
    caminho_arquivo: str,
    trabalho: str,
    tabela: str,
    encoding: str | None = None,
    epsg_fallback: str | None = None,
) -> tuple[list[str], str | None, bool]:
    caminhos = _preparar_camadas(caminho_arquivo, trabalho)
    epsg_det, tem_srs = gdal.detectar_srs(caminhos[0])
    s_srs = None if tem_srs else (epsg_fallback or config.EPSG_PADRAO_ORIGEM)
    if s_srs:
        log.warning("arquivo sem SRS: assumindo EPSG:%s", s_srs)
    gdal.carregar_staging(db.partes_conexao(), caminhos, tabela, s_srs, encoding)
    return caminhos, epsg_det, tem_srs


def _carregar_referencia_db(
    fonte: str,
    nome_camada: str,
    id_colunas: tuple[str, ...],
    tabela: str,
    data_ref: date,
) -> Resultado:
    con = db.conexao()
    try:
        with con.cursor() as cur:
            cols = db.colunas_staging(cur, tabela)
            id_col = _escolher(id_colunas, cols)
            log.info("identificador_origem = coluna '%s'", id_col)

            cur.execute("DELETE FROM camada_referencia WHERE fonte = %s", (fonte,))
            apagadas = cur.rowcount

            cur.execute(
                sql.SQL(
                    "INSERT INTO camada_referencia "
                    "(fonte, nome_camada, identificador_origem, geom, atributos, data_referencia) "
                    "SELECT %s, %s, t.{idc}::text, t.geom, to_jsonb(a), %s "
                    "  FROM stg.{tab} t "
                    " CROSS JOIN LATERAL (SELECT {attrs}) a "
                    " WHERE t.geom IS NOT NULL"
                ).format(idc=sql.Identifier(id_col), tab=sql.Identifier(tabela), attrs=_colunas_attr(cols)),
                (fonte, nome_camada, data_ref),
            )
            inseridas = cur.rowcount
            if inseridas == 0:
                raise RuntimeError("staging sem feicoes com geometria: abortado para nao apagar a camada atual")

            cur.execute(sql.SQL("SELECT count(*) FROM stg.{}").format(sql.Identifier(tabela)))
            total = cur.fetchone()[0]
        con.commit()
    except Exception:
        con.rollback()
        raise
    finally:
        con.close()

    db.analyze("camada_referencia")
    msg = (
        f"{fonte}: {apagadas} linhas antigas removidas, {inseridas} inseridas "
        f"(data_referencia {data_ref}, id={id_col}). Cache de resultados_calculos desta fonte pode estar defasado."
    )
    return Resultado(None, inseridas, total - inseridas, msg)


def _carregar_referencia_conector(
    conector: dict,
    dl: conectores.DownloadResult,
    id_colunas: tuple[str, ...],
    trabalho: str,
    agora_iso: str,
    ctrl_id: int,
) -> Resultado:
    fonte = conector["fonte_referencia"]
    nome_camada = conector.get("nome_camada_exibicao") or fonte
    tabela = _nome_staging("ref", fonte, dl.md5)
    try:
        db.controle_atualizar_etapa(ctrl_id, "PROCESSANDO_POSTGIS", f"Carregando {dl.nome_arquivo} em staging")
        _carregar_staging_padrao(
            dl.caminho_arquivo,
            trabalho,
            tabela,
            epsg_fallback=conector.get("epsg_origem") or config.EPSG_PADRAO_ORIGEM,
        )
        data_ref = inferir_data_referencia(dl.nome_arquivo, agora_iso)
        return _carregar_referencia_db(fonte, nome_camada, id_colunas, tabela, data_ref)
    finally:
        db.apagar_staging(tabela)


def _carregar_carga_conector(
    conector: dict,
    dl: conectores.DownloadResult,
    id_colunas: tuple[str, ...],
    trabalho: str,
    agora_iso: str,
    ctrl_id: int,
) -> Resultado:
    existente = db.processo_por_hash(dl.sha256)
    if existente:
        return Resultado(existente, 0, 0, f"ARQUIVO_JA_EXISTE: hash ja esta em arquivo_original (processo {existente}); nada carregado.")

    inicio = datetime.now()
    tabela = _nome_staging("car", f"c{conector['id']}", dl.md5)
    try:
        db.controle_atualizar_etapa(ctrl_id, "PROCESSANDO_POSTGIS", f"Carregando {dl.nome_arquivo} em staging")
        caminhos, epsg_det, tem_srs = _carregar_staging_padrao(
            dl.caminho_arquivo,
            trabalho,
            tabela,
            epsg_fallback=conector.get("epsg_origem") or config.EPSG_PADRAO_ORIGEM,
        )
        url = storage.guardar(dl.caminho_arquivo, dl.sha256, dl.nome_arquivo)
        formato = _resolver_formato(caminhos[0], dl.extensao)
        item = {
            "nome": dl.nome_arquivo,
            "tamanho": dl.tamanho_bytes,
            "modified": agora_iso,
            "drive_file_id": f"conector:{conector['id']}",
        }
        res = _gravar_carga_db(
            item=item,
            sha=dl.sha256,
            url=url,
            tabela=tabela,
            epsg_det=epsg_det if tem_srs else None,
            formato=formato,
            inicio=inicio,
            id_colunas=id_colunas,
            orgao_id=conector.get("orgao_id"),
            conjunto_id=conector.get("conjunto_id"),
            orgao_sigla="SICAR",
            conjunto_nome="Imóveis rurais",
            declarado=conector.get("epsg_origem") or config.EPSG_PADRAO_ORIGEM,
            extensao=dl.extensao.lstrip(".") or "zip",
            tipo_mime=dl.tipo_mime,
            origem_msg=f"Arquivo recebido via Conector #{conector['id']} ({conector['nome']})",
        )
        if res.processo_id:
            _notificar_pos_processamento(res.processo_id)
        return res
    finally:
        db.apagar_staging(tabela)


def _notificar_pos_processamento(processo_id: int) -> None:
    """Notifica o Spring Boot de forma best-effort para cruzar referencias e acionar motor de regras."""
    url = f"{config.BACKEND_API_URL.rstrip('/')}/conectores/interno/processos/{processo_id}/pos-processar"
    req = urllib.request.Request(
        url,
        data=b"{}",
        headers={
            "Content-Type": "application/json",
            "X-Airflow-Token": config.AIRFLOW_INTERNAL_TOKEN,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            log.info("pos-processamento processo %s -> HTTP %s", processo_id, getattr(resp, "status", "OK"))
    except Exception as e:  # noqa: BLE001 - best-effort quando backend offline
        log.warning("nao consegui notificar pos-processamento do processo %s (%s): %s", processo_id, url, e)


# ======================================================================================
# destino REFERENCIA legado (Drive)
# ======================================================================================
def _referencia(cfg: config.Fonte, item: dict, zip_path: str, trabalho: str, sha: str) -> Resultado:
    tabela = _nome_staging("ref", cfg.fonte, item["md5"])
    try:
        _carregar_staging_padrao(zip_path, trabalho, tabela, encoding=cfg.encoding)
        data_ref = inferir_data_referencia(item["nome"], item["modified"])
        return _carregar_referencia_db(cfg.fonte, cfg.nome_camada, cfg.id_colunas, tabela, data_ref)
    finally:
        db.apagar_staging(tabela)


# ======================================================================================
# destino CARGA legado (Drive)
# ======================================================================================
def _car(cfg: config.Fonte, item: dict, zip_path: str, trabalho: str, sha: str) -> Resultado:
    existente = db.processo_por_hash(sha)
    if existente:
        return Resultado(existente, 0, 0, f"ARQUIVO_JA_EXISTE: hash ja esta em arquivo_original (processo {existente}); nada carregado.")

    inicio = datetime.now()
    tabela = _nome_staging("car", "car", item["md5"])
    try:
        caminhos, epsg_det, tem_srs = _carregar_staging_padrao(zip_path, trabalho, tabela, encoding=cfg.encoding)
        url = storage.guardar(zip_path, sha, item["nome"])
        formato = _resolver_formato(caminhos[0], ".zip")
        return _gravar_car(cfg, item, sha, url, tabela, epsg_det if tem_srs else None, formato, inicio)
    finally:
        db.apagar_staging(tabela)


def _gravar_car(cfg, item, sha, url, tabela, epsg_det, formato, inicio) -> Resultado:
    return _gravar_carga_db(
        item=item,
        sha=sha,
        url=url,
        tabela=tabela,
        epsg_det=epsg_det,
        formato=formato,
        inicio=inicio,
        id_colunas=cfg.id_colunas,
        orgao_id=None,
        conjunto_id=None,
        orgao_sigla=cfg.orgao_sigla,
        conjunto_nome=cfg.conjunto_nome,
        declarado=config.EPSG_PADRAO_ORIGEM,
        extensao="zip",
        tipo_mime="application/zip",
        origem_msg=f"Arquivo recebido via Airflow (Drive {item['drive_file_id']})",
    )


def _gravar_carga_db(
    *,
    item: dict,
    sha: str,
    url: str,
    tabela: str,
    epsg_det: str | None,
    formato: str,
    inicio: datetime,
    id_colunas: tuple[str, ...],
    orgao_id: int | None,
    conjunto_id: int | None,
    orgao_sigla: str | None,
    conjunto_nome: str | None,
    declarado: str,
    extensao: str,
    tipo_mime: str,
    origem_msg: str,
) -> Resultado:
    ano_safra = config.CAR_ANO_SAFRA or item["modified"][:4]
    competencia = item["modified"][:7]

    con = db.conexao()
    try:
        with con.cursor() as cur:
            cols = db.colunas_staging(cur, tabela)
            id_col = _escolher(id_colunas, cols)
            idc, tab = sql.Identifier(id_col), sql.Identifier(tabela)
            log.info("codigo_imovel = coluna '%s'", id_col)

            operador = _id(
                cur,
                "SELECT u.id FROM usuario u JOIN perfil p ON p.id = u.perfil_id "
                "WHERE u.email = %s AND p.nome = 'OPERADOR' AND u.ativo",
                (config.OPERADOR_EMAIL,),
                f"usuario OPERADOR ativo {config.OPERADOR_EMAIL} (migration V47)",
            )
            conjunto = conjunto_id or _id(
                cur, "SELECT id FROM conjunto WHERE nome = %s AND ativo", (conjunto_nome,), f"conjunto '{conjunto_nome}'"
            )
            orgao = orgao_id or _id(
                cur, "SELECT id FROM orgao WHERE sigla = %s AND ativo", (orgao_sigla,), f"orgao '{orgao_sigla}'"
            )
            e_ingestao = _id(cur, "SELECT id FROM etapa WHERE nome = 'INGESTAO'", (), "etapa INGESTAO")
            e_tratamento = _id(cur, "SELECT id FROM etapa WHERE nome = 'TRATAMENTO'", (), "etapa TRATAMENTO")
            s_concluida = _id(cur, "SELECT id FROM situacao WHERE nome = 'CONCLUIDA'", (), "situacao CONCLUIDA")

            cur.execute(
                sql.SQL(
                    "SELECT count(*), "
                    "       count(*) FILTER (WHERE t.geom IS NULL), "
                    "       count(*) FILTER (WHERE t.geom IS NOT NULL AND (t.{i} IS NULL OR btrim(t.{i}::text) = '')) "
                    "  FROM stg.{t} t"
                ).format(i=idc, t=tab)
            )
            total, sem_geom, sem_codigo = cur.fetchone()

            cur.execute(
                "INSERT INTO processo (operador_id, conjunto_id, orgao_id, etapa_atual_id, situacao_atual_id, "
                "                      ano_safra, epsg_origem, epsg_destino, epsg_detectado, data_inicio, "
                "                      competencia, formato) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) RETURNING id",
                (
                    operador, conjunto, orgao, e_tratamento, s_concluida, ano_safra,
                    config.epsg_txt(declarado), config.epsg_txt(config.EPSG_DESTINO),
                    config.epsg_txt(epsg_det) if epsg_det else None, inicio, competencia, formato,
                ),
            )
            processo_id = cur.fetchone()[0]

            cur.execute(
                "INSERT INTO arquivo_original (processo_id, usuario_upload_id, nome_original, extensao, tipo_mime, "
                "                              tamanho_bytes, url_armazenamento, hash_sha256) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s) RETURNING id",
                (processo_id, operador, item["nome"], extensao, tipo_mime, item["tamanho"], url, sha),
            )
            original_id = cur.fetchone()[0]

            cur.execute(
                "INSERT INTO processo_etapa (processo_id, etapa_id, situacao_id, data_inicio, data_fim, mensagem, resultado) "
                "VALUES (%s, %s, %s, %s, now(), %s, %s)",
                (processo_id, e_ingestao, s_concluida, inicio, origem_msg, f"sha256={sha}"),
            )
            cur.execute(
                "INSERT INTO processo_etapa (processo_id, etapa_id, situacao_id, data_inicio, data_fim, mensagem) "
                "VALUES (%s, %s, %s, %s, now(), %s) RETURNING id",
                (processo_id, e_tratamento, s_concluida, inicio, "Reprojecao para 4674 e extracao das feicoes via ogr2ogr/PostGIS"),
            )
            etapa_trat_id = cur.fetchone()[0]

            cur.execute(
                "INSERT INTO arquivo_processado (arquivo_original_id, processo_id, etapa_geracao_id, nome_arquivo, "
                "                                formato, url_armazenamento, epsg, versao) "
                "VALUES (%s, %s, %s, %s, 'FEICAO_CARGA', %s, %s, 1) RETURNING id",
                (original_id, processo_id, e_tratamento, item["nome"], url, config.epsg_txt(config.EPSG_DESTINO)),
            )
            processado_id = cur.fetchone()[0]

            cur.execute(
                sql.SQL(
                    "INSERT INTO feicao_carga (arquivo_processado_id, codigo_imovel, geom, atributos) "
                    "WITH escolhidos AS ("
                    "  SELECT DISTINCT ON (x.cod) x.cod, x.ogc_fid "
                    "    FROM (SELECT btrim(t.{i}::text) AS cod, t.ogc_fid FROM stg.{t} t WHERE t.geom IS NOT NULL) x "
                    "   WHERE x.cod IS NOT NULL AND x.cod <> '' "
                    "   ORDER BY x.cod, x.ogc_fid) "
                    "SELECT %s, e.cod, t.geom, to_jsonb(a) "
                    "  FROM escolhidos e "
                    "  JOIN stg.{t} t ON t.ogc_fid = e.ogc_fid "
                    " CROSS JOIN LATERAL (SELECT {attrs}) a"
                ).format(i=idc, t=tab, attrs=_colunas_attr(cols)),
                (processado_id,),
            )
            inseridas = cur.rowcount
            if inseridas == 0:
                raise RuntimeError("nenhuma feicao valida no arquivo (sem geometria ou sem codigo): carga abortada")
            duplicados = total - sem_geom - sem_codigo - inseridas

            logs = [("INFO", f"Carga automatica (Airflow): {inseridas} feicoes extraidas, destino EPSG:{config.EPSG_DESTINO}", None)]
            if sem_geom:
                logs.append(("WARNING", f"{sem_geom} registros sem geometria descartados", "SEM_GEOMETRIA"))
            if sem_codigo:
                logs.append(("WARNING", f"{sem_codigo} registros sem {id_col} descartados", "SEM_CODIGO_IMOVEL"))
            if duplicados:
                logs.append(("WARNING", f"{duplicados} registros com {id_col} repetido: mantida a primeira ocorrencia", "CODIGO_DUPLICADO"))
            if epsg_det and epsg_det != declarado:
                logs.append(("WARNING", f"EPSG detectado {epsg_det} diferente do declarado {declarado}", "EPSG_DIVERGENTE"))
            cur.executemany(
                "INSERT INTO log_processamento (processo_id, processo_etapa_id, nivel, mensagem, codigo_erro) "
                "VALUES (%s, %s, %s, %s, %s)",
                [(processo_id, etapa_trat_id, n, m, c) for n, m, c in logs],
            )
        con.commit()
    except Exception:
        con.rollback()
        raise
    finally:
        con.close()

    descartadas = sem_geom + sem_codigo + duplicados
    msg = (
        f"processo {processo_id} criado: {inseridas} feicoes (descartadas: {descartadas}); "
        "parado em TRATAMENTO/CONCLUIDA aguardando validacao pela esteira"
    )
    return Resultado(processo_id, inseridas, descartadas, msg)


# ======================================================================================
# resumo
# ======================================================================================
def resumo_texto(dag_id: str, run_id: str) -> str | None:
    linhas = db.resumo_da_execucao(run_id)
    if not linhas:
        return None
    base = [l for l in linhas if l["status"] == "BASELINE"]
    reais = [l for l in linhas if l["status"] != "BASELINE"]
    ok = [l for l in reais if l["status"] == "CARREGADO"]
    ruins = [l for l in reais if l["status"] != "CARREGADO"]

    icone = "✅" if not ruins else "⚠️"
    out = [f"{icone} **{dag_id}** · {len(ok)} carregado(s), {len(ruins)} com problema · execução `{run_id}`"]
    for l in ok:
        extra = f" → processo #{l['processo_id']} (aguarda validação e aprovação do Gestor)" if l["destino"] == "CARGA" else ""
        out.append(
            f"✅ `{l['nome_arquivo']}` · {l['linhas_carregadas'] or 0} linhas, {l['linhas_descartadas'] or 0} descartadas"
            f" · {_dur(l['segundos'])}{extra}"
        )
    for l in ruins:
        out.append(f"❌ `{l['nome_arquivo']}` · {l['status']}: {(l['mensagem'] or '')[:300]}")
    if base:
        out.append(f"📌 {len(base)} arquivo(s) já carregados antes do Airflow registrados como baseline (não recarregados)")
    return "\n".join(out)


# ======================================================================================
# utilitarios
# ======================================================================================
def _resolver_formato(caminho_camada: str, ext_fallback: str) -> str:
    ext = os.path.splitext(caminho_camada)[1].lower() or ext_fallback.lower()
    return _FORMATO_POR_EXT.get(ext, "Shapefile")


def _seguro(txt: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]", "_", txt)


def _nome_staging(prefixo: str, fonte: str, md5: str) -> str:
    return re.sub(r"[^a-z0-9_]", "_", f"{prefixo}_{fonte}_{md5[:8]}".lower())[:63]


def _escolher(candidatas: tuple[str, ...], colunas: list[str]) -> str:
    for c in candidatas:
        if c in colunas:
            return c
    raise ValueError(
        f"nenhuma coluna de identificador entre {list(candidatas)}; colunas disponiveis: {colunas}. "
        "Ajuste id_colunas em config.FONTES ou coluna_identificador no conector."
    )


def _colunas_attr(cols: list[str]) -> sql.Composable:
    """t.col1, t.col2, ... sem geom/ogc_fid: evita serializar a geometria dentro do JSONB."""
    attrs = [c for c in cols if c not in ("geom", "ogc_fid")]
    return sql.SQL(", ").join(sql.SQL("t.{}").format(sql.Identifier(c)) for c in attrs)


def _id(cur, consulta: str, params: tuple, rotulo: str) -> int:
    cur.execute(consulta, params)
    r = cur.fetchone()
    if not r:
        raise LookupError(f"nao encontrei no banco: {rotulo}")
    return r[0]


def _dur(segundos: int | None) -> str:
    s = int(segundos or 0)
    return f"{s // 60}m{s % 60:02d}s"


def inferir_data_referencia(nome: str, modificado_iso: str) -> date:
    """Data de referencia a partir do nome do arquivo; cai na data de modificacao."""
    def _d(a, m, d) -> date | None:
        try:
            return date(int(a), int(m), int(d))
        except ValueError:
            return None

    m = re.search(r"v(\d{4})(\d{2})(\d{2})", nome)
    if m and (d := _d(*m.groups())):
        return d
    datas = re.findall(r"(\d{4})-(\d{2})-(\d{2})", nome)
    if datas and (d := _d(*datas[-1])):
        return d
    m = re.search(r"_(\d{4})_(\d{2})(?!\d)", nome)
    if m and (d := _d(m.group(1), m.group(2), 1)):
        return d
    m = re.search(r"(?<!\d)(\d{2})(\d{4})(?!\d)", nome)
    if m and int(m.group(2)) >= 2000 and (d := _d(m.group(2), m.group(1), 1)):
        return d
    m = re.search(r"(?<!\d)(20\d{2})(?!\d)", nome)
    if m:
        return date(int(m.group(1)), 12, 31)
    return datetime.fromisoformat(modificado_iso.replace("Z", "+00:00")).date()
