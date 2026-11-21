"""DAG principal do GeoDataHub: despacha ingestao de conectores ou sincronizacao legada do Drive."""
from __future__ import annotations

import os
import sys
from datetime import datetime, timedelta

_AIRFLOW_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _AIRFLOW_ROOT not in sys.path:
    sys.path.insert(0, _AIRFLOW_ROOT)

from airflow import DAG  # noqa: E402
from airflow.decorators import task  # noqa: E402

from geodatahub_airflow import notify, pipeline  # noqa: E402

default_args = {
    "owner": "geodatahub",
    "retries": 2,
    "retry_delay": timedelta(minutes=2),
    "execution_timeout": timedelta(minutes=45),
    "on_failure_callback": notify.falha_callback,
}

with DAG(
    dag_id="geodatahub_ingestao",
    description="Ingestao geoespacial multi-protocolo (Conectores OGC WFS/REST/Drive -> PostGIS)",
    schedule=None,
    start_date=datetime(2025, 1, 1),
    catchup=False,
    max_active_runs=3,
    default_args=default_args,
    tags=["geodatahub", "ingestao", "postgis"],
) as dag:

    @task
    def executar_ingestao(**context) -> dict:
        dag_run = context.get("dag_run")
        conf = (dag_run.conf if dag_run and dag_run.conf else {}) or {}
        run_id = getattr(dag_run, "run_id", "") or context.get("run_id", "")

        conector_id = conf.get("conector_id")
        if conector_id is not None:
            res = pipeline.processar_conector(
                conector_id=int(conector_id),
                params_override=conf.get("parametros_override"),
                credencial_aberta=conf.get("credencial_aberta"),
                ctrl_id=int(conf["ctrl_id"]) if conf.get("ctrl_id") is not None else None,
                run_id=run_id,
            )
            msg = pipeline.resumo_texto("geodatahub_ingestao", run_id)
            if msg:
                notify.discord(msg)
            return res

        destino = conf.get("destino", "REFERENCIA")
        forcar = conf.get("forcar") or []
        itens = pipeline.planejar(destino=destino, forcar=forcar, run_id=run_id)
        resultados = [pipeline.processar(item, run_id=run_id) for item in itens]
        msg = pipeline.resumo_texto("geodatahub_ingestao", run_id)
        if msg:
            notify.discord(msg)
        return {"destino": destino, "processados": len(resultados), "resultados": resultados}

    executar_ingestao()
