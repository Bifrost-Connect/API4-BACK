"""Acesso ao PostgreSQL/PostGIS (Connection do Airflow) e tabelas de controle/conectores."""
from __future__ import annotations

from contextlib import closing

from airflow.hooks.base import BaseHook
from airflow.providers.postgres.hooks.postgres import PostgresHook
from psycopg2 import sql

from . import config


def conexao():
    """Conexao psycopg2 com autocommit desligado (a transacao da carga e controlada por quem chama)."""
    return PostgresHook(postgres_conn_id=config.GEO_CONN_ID).get_conn()


def partes_conexao() -> dict[str, str]:
    """Dados para montar o 'PG:' do ogr2ogr (a senha vai separada, por PGPASSWORD)."""
    c = BaseHook.get_connection(config.GEO_CONN_ID)
    return {
        "host": c.host,
        "port": str(c.port or 5432),
        "dbname": c.schema,
        "user": c.login,
        "password": c.password or "",
        "sslmode": str(c.extra_dejson.get("sslmode", "")),
    }


def _autocommit():
    con = conexao()
    con.autocommit = True
    return closing(con)


# --- staging ----------------------------------------------------------------------------
def colunas_staging(cur, tabela: str) -> list[str]:
    cur.execute(
        "SELECT column_name FROM information_schema.columns "
        "WHERE table_schema = 'stg' AND table_name = %s ORDER BY ordinal_position",
        (tabela,),
    )
    return [r[0] for r in cur.fetchall()]


def apagar_staging(tabela: str) -> None:
    with _autocommit() as con, con.cursor() as cur:
        cur.execute(sql.SQL("DROP TABLE IF EXISTS stg.{}").format(sql.Identifier(tabela)))


def analyze(tabela: str) -> None:
    with _autocommit() as con, con.cursor() as cur:
        cur.execute(sql.SQL("ANALYZE {}").format(sql.Identifier(tabela)))


# --- consultas de apoio -------------------------------------------------------------------
def processo_por_hash(sha256: str) -> int | None:
    with _autocommit() as con, con.cursor() as cur:
        cur.execute("SELECT processo_id FROM arquivo_original WHERE hash_sha256 = %s", (sha256,))
        r = cur.fetchone()
        return r[0] if r else None


def contar_fonte(fonte: str) -> int:
    with _autocommit() as con, con.cursor() as cur:
        cur.execute("SELECT count(*) FROM camada_referencia WHERE fonte = %s", (fonte,))
        return cur.fetchone()[0]


# --- tabela de controle (cada chamada e uma transacao propria, para sobreviver a rollback) ---
def controle_ultimo_ok(drive_file_id: str) -> dict | None:
    with _autocommit() as con, con.cursor() as cur:
        cur.execute(
            "SELECT md5_drive, status FROM ingestao_drive_controle "
            "WHERE drive_file_id = %s AND status IN ('CARREGADO', 'BASELINE') "
            "ORDER BY id DESC LIMIT 1",
            (drive_file_id,),
        )
        r = cur.fetchone()
        return {"md5_drive": r[0], "status": r[1]} if r else None


def controle_iniciar(
    item: dict,
    fonte: str | None,
    destino: str,
    run_id: str,
    conector_id: int | None = None,
) -> int:
    with _autocommit() as con, con.cursor() as cur:
        cur.execute(
            "INSERT INTO ingestao_drive_controle "
            "(drive_file_id, drive_caminho, nome_arquivo, destino, fonte, md5_drive, tamanho_bytes, "
            " status, dag_run_id, conector_id) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, 'EM_ANDAMENTO', %s, %s) RETURNING id",
            (
                item["drive_file_id"],
                item["caminho"],
                item["nome"],
                destino,
                fonte,
                item.get("md5"),
                item.get("tamanho"),
                run_id,
                conector_id,
            ),
        )
        return cur.fetchone()[0]


def controle_atualizar_etapa(ctrl_id: int, etapa_tag: str, mensagem: str) -> None:
    msg = f"[{etapa_tag}] {mensagem}"[:4000]
    with _autocommit() as con, con.cursor() as cur:
        cur.execute(
            "UPDATE ingestao_drive_controle SET mensagem = %s WHERE id = %s",
            (msg, ctrl_id),
        )


def controle_atualizar_hashes(
    ctrl_id: int,
    md5_val: str,
    sha256_val: str,
    tamanho: int,
    nome_arquivo: str,
) -> None:
    with _autocommit() as con, con.cursor() as cur:
        cur.execute(
            "UPDATE ingestao_drive_controle "
            "SET md5_drive = %s, hash_sha256 = %s, tamanho_bytes = %s, nome_arquivo = %s "
            "WHERE id = %s",
            (md5_val, sha256_val, tamanho, nome_arquivo, ctrl_id),
        )


def controle_finalizar(
    ctrl_id: int,
    status: str,
    hash_sha256: str | None = None,
    processo_id: int | None = None,
    linhas_carregadas: int | None = None,
    linhas_descartadas: int | None = None,
    mensagem: str | None = None,
) -> None:
    with _autocommit() as con, con.cursor() as cur:
        cur.execute(
            "UPDATE ingestao_drive_controle SET status = %s, hash_sha256 = %s, processo_id = %s, "
            "linhas_carregadas = %s, linhas_descartadas = %s, mensagem = %s, data_fim = now() "
            "WHERE id = %s",
            (
                status,
                hash_sha256,
                processo_id,
                linhas_carregadas,
                linhas_descartadas,
                (mensagem or "")[:4000] or None,
                ctrl_id,
            ),
        )


def controle_baseline(item: dict, fonte: str, run_id: str, linhas: int) -> None:
    with _autocommit() as con, con.cursor() as cur:
        cur.execute(
            "INSERT INTO ingestao_drive_controle "
            "(drive_file_id, drive_caminho, nome_arquivo, destino, fonte, md5_drive, tamanho_bytes, "
            " status, linhas_carregadas, mensagem, dag_run_id, data_fim) "
            "VALUES (%s, %s, %s, 'REFERENCIA', %s, %s, %s, 'BASELINE', %s, %s, %s, now())",
            (
                item["drive_file_id"],
                item["caminho"],
                item["nome"],
                fonte,
                item["md5"],
                item["tamanho"],
                linhas,
                "Carregado a mao antes do Airflow; registrado sem recarregar.",
                run_id,
            ),
        )


def resumo_da_execucao(run_id: str) -> list[dict]:
    """Ultima tentativa de cada arquivo tocado nesta execucao do DAG."""
    with _autocommit() as con, con.cursor() as cur:
        cur.execute(
            "SELECT DISTINCT ON (drive_file_id) nome_arquivo, destino, fonte, status, processo_id, "
            "       linhas_carregadas, linhas_descartadas, mensagem, "
            "       EXTRACT(EPOCH FROM (COALESCE(data_fim, now()) - data_inicio))::int AS segundos "
            "  FROM ingestao_drive_controle WHERE dag_run_id = %s "
            " ORDER BY drive_file_id, id DESC",
            (run_id,),
        )
        cols = [d[0] for d in cur.description]
        return [dict(zip(cols, r)) for r in cur.fetchall()]


# --- conectores cadastrados (V48) ---------------------------------------------------------
def buscar_conector(conector_id: int) -> dict | None:
    with _autocommit() as con, con.cursor() as cur:
        cur.execute(
            "SELECT id, nome, descricao, tipo_protocolo, url_base, destino, fonte_referencia, "
            "       nome_camada_exibicao, orgao_id, conjunto_id, formato_dados, epsg_origem, "
            "       coluna_identificador, parametros_json, tipo_autenticacao, nome_credencial, "
            "       prefixo_credencial, credencial_cifrada, periodicidade, expressao_cron, ativo "
            "  FROM conector_fonte_dados WHERE id = %s",
            (conector_id,),
        )
        row = cur.fetchone()
        if not row:
            return None
        cols = [d[0] for d in cur.description]
        return dict(zip(cols, row))


def atualizar_status_conector(conector_id: int, status: str) -> None:
    with _autocommit() as con, con.cursor() as cur:
        cur.execute(
            "UPDATE conector_fonte_dados "
            "SET ultimo_status = %s, ultima_execucao_em = now(), data_atualizacao = now() "
            "WHERE id = %s",
            (status, conector_id),
        )
