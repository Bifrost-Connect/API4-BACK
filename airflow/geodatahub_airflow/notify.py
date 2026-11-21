"""Avisos no Discord (webhook guardado em Connection do Airflow). Falha ao avisar nunca derruba a task."""
from __future__ import annotations

import logging

from . import config

log = logging.getLogger(__name__)

LIMITE_DISCORD = 1900  # o Discord aceita 2000 caracteres por mensagem


def discord(mensagem: str) -> None:
    try:
        import requests
        from airflow.hooks.base import BaseHook

        c = BaseHook.get_connection(config.DISCORD_CONN_ID)
        base = c.host or ""
        if not base.startswith("http"):
            base = f"{c.schema or 'https'}://{base}"
        url = f"{base.rstrip('/')}/{c.password}"
        r = requests.post(url, json={"content": mensagem[:LIMITE_DISCORD]}, timeout=15)
        r.raise_for_status()
    except Exception as e:  # noqa: BLE001 - aviso e best-effort
        log.warning("nao consegui avisar no Discord: %s", e)


def falha_callback(context: dict) -> None:
    """on_failure_callback: so dispara depois de esgotar os retries."""
    ti = context.get("task_instance")
    erro = str(context.get("exception") or "")[:700]
    mapa = f"[{ti.map_index}]" if getattr(ti, "map_index", -1) >= 0 else ""
    discord(
        f"❌ **{ti.dag_id}** · `{ti.task_id}`{mapa} falhou "
        f"(tentativa {ti.try_number}, execução `{ti.run_id}`)\n```{erro}```"
    )
