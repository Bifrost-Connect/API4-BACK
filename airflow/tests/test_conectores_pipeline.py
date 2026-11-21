"""Testes unitarios TDD para o pacote geodatahub_airflow (conectores, db, gdal, storage, pipeline)."""
from __future__ import annotations

import hashlib
import io
import json
import os
import sys
import tempfile
import types
import unittest
import zipfile
from pathlib import Path
from unittest.mock import MagicMock, patch

AIRFLOW_ROOT = Path(__file__).resolve().parents[1]
if str(AIRFLOW_ROOT) not in sys.path:
    sys.path.insert(0, str(AIRFLOW_ROOT))


def _install_fake_airflow_and_psycopg2() -> None:
    """Registra stubs leves de airflow e psycopg2 quando nao estao instalados localmente."""
    if "airflow" not in sys.modules:
        airflow_mod = types.ModuleType("airflow")
        hooks_mod = types.ModuleType("airflow.hooks")
        base_mod = types.ModuleType("airflow.hooks.base")
        providers_mod = types.ModuleType("airflow.providers")
        pg_prov_mod = types.ModuleType("airflow.providers.postgres")
        pg_hooks_mod = types.ModuleType("airflow.providers.postgres.hooks")
        pg_mod = types.ModuleType("airflow.providers.postgres.hooks.postgres")

        class FakeBaseHook:
            @staticmethod
            def get_connection(conn_id):
                raise RuntimeError(f"mock BaseHook.get_connection({conn_id})")

        class FakePostgresHook:
            def __init__(self, postgres_conn_id=None):
                self.postgres_conn_id = postgres_conn_id

            def get_conn(self):
                raise RuntimeError("mock PostgresHook.get_conn")

        base_mod.BaseHook = FakeBaseHook
        pg_mod.PostgresHook = FakePostgresHook

        sys.modules["airflow"] = airflow_mod
        sys.modules["airflow.hooks"] = hooks_mod
        sys.modules["airflow.hooks.base"] = base_mod
        sys.modules["airflow.providers"] = providers_mod
        sys.modules["airflow.providers.postgres"] = pg_prov_mod
        sys.modules["airflow.providers.postgres.hooks"] = pg_hooks_mod
        sys.modules["airflow.providers.postgres.hooks.postgres"] = pg_mod

    if "psycopg2" not in sys.modules:
        psycopg2_mod = types.ModuleType("psycopg2")
        sql_mod = types.ModuleType("psycopg2.sql")

        class FakeSQL:
            def __init__(self, text):
                self.text = text

            def format(self, *args, **kwargs):
                return self

            def join(self, items):
                return FakeSQL(", ".join(str(i) for i in items))

            def __str__(self):
                return self.text

        sql_mod.SQL = FakeSQL
        sql_mod.Identifier = lambda name: FakeSQL(f'"{name}"')
        psycopg2_mod.sql = sql_mod
        sys.modules["psycopg2"] = psycopg2_mod
        sys.modules["psycopg2.sql"] = sql_mod

    if "google" not in sys.modules:
        google_mod = types.ModuleType("google")
        oauth2_mod = types.ModuleType("google.oauth2")
        sa_mod = types.ModuleType("google.oauth2.service_account")
        apiclient_mod = types.ModuleType("googleapiclient")
        disc_mod = types.ModuleType("googleapiclient.discovery")
        http_mod = types.ModuleType("googleapiclient.http")

        sa_mod.Credentials = MagicMock()
        disc_mod.build = MagicMock()
        http_mod.MediaIoBaseDownload = MagicMock()

        sys.modules["google"] = google_mod
        sys.modules["google.oauth2"] = oauth2_mod
        sys.modules["google.oauth2.service_account"] = sa_mod
        sys.modules["googleapiclient"] = apiclient_mod
        sys.modules["googleapiclient.discovery"] = disc_mod
        sys.modules["googleapiclient.http"] = http_mod


_install_fake_airflow_and_psycopg2()


def _make_zip_bytes(filename: str = "camada.shp", payload: bytes = b"SHP") -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr(filename, payload)
    return buf.getvalue()


class TestConfig(unittest.TestCase):
    def test_defaults_and_constants(self):
        from geodatahub_airflow import config

        self.assertEqual(config.STORAGE_BACKEND, os.environ.get("STORAGE_BACKEND", "local"))
        self.assertEqual(config.COFRE_LOCAL_DIR, os.environ.get("COFRE_LOCAL_DIR", "/opt/airflow/data/cofre"))
        self.assertIn("geoserver.car.gov.br", config.SICAR_WFS_URL)
        self.assertIn("8080/api", config.BACKEND_API_URL)
        self.assertTrue(config.AIRFLOW_INTERNAL_TOKEN)
        self.assertEqual(len(config.UFS_BR), 27)
        self.assertIn("PR", config.UFS_BR)
        self.assertIn("SP", config.UFS_BR)
        self.assertTrue(callable(config.casar))
        self.assertTrue(callable(config.por_chave))
        self.assertTrue(callable(config.epsg_txt))


class TestConectores(unittest.TestCase):
    def test_construir_cql_sicar_vazio_e_override(self):
        from geodatahub_airflow.conectores import construir_cql_sicar

        self.assertIsNone(construir_cql_sicar({}))
        self.assertEqual(
            construir_cql_sicar({"cqlFilter": "status_imovel = 'AT'", "municipio": "Curitiba"}),
            "status_imovel = 'AT'",
        )

    def test_construir_cql_sicar_sanitizacao_e_combinacao(self):
        from geodatahub_airflow.conectores import construir_cql_sicar

        cql = construir_cql_sicar(
            {
                "municipio": "Pontal do 'Parana'",
                "cod_municipio_ibge": 4119954,
                "cod_imovel": "PR-4119954-ABC'123",
                "status_imovel": "AT",
                "tipo_imovel": "IRU",
            }
        )
        self.assertIsNotNone(cql)
        self.assertIn("strToUpperCase(municipio) LIKE '%PONTAL DO ''PARANA''%'", cql)
        self.assertIn("cod_municipio_ibge = 4119954", cql)
        self.assertIn("cod_imovel = 'PR-4119954-ABC''123'", cql)
        self.assertIn("status_imovel = 'AT'", cql)
        self.assertIn("tipo_imovel = 'IRU'", cql)
        self.assertEqual(cql.count(" AND "), 4)

    def test_montar_auth_http_todos_modos(self):
        from geodatahub_airflow.conectores import montar_auth_http

        url, params, headers, auth = montar_auth_http("NONE", None, None, "https://x.test/wfs", {"a": "1"}, {})
        self.assertEqual(url, "https://x.test/wfs")
        self.assertEqual(params, {"a": "1"})
        self.assertEqual(headers, {})
        self.assertIsNone(auth)

        _, _, headers, auth = montar_auth_http("API_KEY_HEADER", None, "sec-1", "https://x.test", {}, {})
        self.assertEqual(headers["X-API-Key"], "sec-1")
        self.assertIsNone(auth)

        _, _, headers, _ = montar_auth_http("API_KEY_HEADER", "X-Custom", "sec-2", "https://x.test", {}, {})
        self.assertEqual(headers["X-Custom"], "sec-2")

        url_q, params_q, _, _ = montar_auth_http(
            "API_KEY_QUERY", "token", "abc", "https://x.test?key={API_KEY}", {}, {}
        )
        self.assertEqual(url_q, "https://x.test?key=abc")
        self.assertEqual(params_q, {})

        url_q2, params_q2, _, _ = montar_auth_http("API_KEY_QUERY", None, "abc", "https://x.test", {}, {})
        self.assertEqual(url_q2, "https://x.test")
        self.assertEqual(params_q2["api_key"], "abc")

        _, _, headers_b, _ = montar_auth_http("BEARER_TOKEN", None, "jwt-99", "https://x.test", {}, {})
        self.assertEqual(headers_b["Authorization"], "Bearer jwt-99")

        _, _, _, auth_basic = montar_auth_http("BASIC_AUTH", None, "usr:p@ss:123", "https://x.test", {}, {})
        self.assertEqual(auth_basic, ("usr", "p@ss:123"))

    def test_baixar_conector_wfs_shape_zip_valido(self):
        from geodatahub_airflow.conectores import DownloadResult, baixar_conector

        zip_bytes = _make_zip_bytes("sicar.shp", b"GEOMDATA")
        fake_resp = MagicMock()
        fake_resp.status_code = 200
        fake_resp.headers = {"Content-Disposition": 'attachment; filename="sicar_pr.zip"'}
        fake_resp.iter_content.return_value = [zip_bytes[:10], zip_bytes[10:]]
        fake_resp.raise_for_status.return_value = None

        conector = {
            "id": 1,
            "nome": "SICAR WFS",
            "tipo_protocolo": "OGC_WFS",
            "url_base": "https://geoserver.car.gov.br/geoserver/sicar/wfs",
            "formato_dados": "SHAPE-ZIP",
            "epsg_origem": "4674",
            "parametros_json": {"version": "1.0.0", "typeName": "sicar:sicar_imoveis_pr", "maxFeatures": 100},
            "tipo_autenticacao": "NONE",
            "nome_credencial": None,
        }

        with tempfile.TemporaryDirectory() as tmpdir, patch("geodatahub_airflow.conectores._http_get", return_value=fake_resp) as mock_get:
            res = baixar_conector(conector, {"municipio": "Curitiba"}, None, tmpdir)
            self.assertTrue(os.path.exists(res.caminho_arquivo))

        self.assertIsInstance(res, DownloadResult)
        self.assertEqual(res.nome_arquivo, "sicar_pr.zip")
        self.assertEqual(res.extensao, ".zip")
        self.assertEqual(res.tamanho_bytes, len(zip_bytes))
        self.assertEqual(res.md5, hashlib.md5(zip_bytes).hexdigest())
        self.assertEqual(res.sha256, hashlib.sha256(zip_bytes).hexdigest())
        called_params = mock_get.call_args.kwargs["params"]
        self.assertEqual(called_params["service"], "WFS")
        self.assertEqual(called_params["request"], "GetFeature")
        self.assertEqual(called_params["outputFormat"], "SHAPE-ZIP")
        self.assertIn("CURITIBA", called_params["CQL_FILTER"])

    def test_baixar_conector_wfs_xml_exception_dispara_runtime_error(self):
        from geodatahub_airflow.conectores import baixar_conector

        xml_err = b"<ServiceExceptionReport><ServiceException>Layer sicar:invalida not found</ServiceException></ServiceExceptionReport>"
        fake_resp = MagicMock()
        fake_resp.status_code = 200
        fake_resp.headers = {}
        fake_resp.iter_content.return_value = [xml_err]
        fake_resp.raise_for_status.return_value = None

        conector = {
            "id": 1,
            "nome": "SICAR WFS",
            "tipo_protocolo": "OGC_WFS",
            "url_base": "https://geoserver.car.gov.br/geoserver/sicar/wfs",
            "formato_dados": "SHAPE-ZIP",
            "epsg_origem": "4674",
            "parametros_json": {"typeName": "sicar:invalida"},
            "tipo_autenticacao": "NONE",
            "nome_credencial": None,
        }

        with tempfile.TemporaryDirectory() as tmpdir, patch("geodatahub_airflow.conectores._http_get", return_value=fake_resp):
            with self.assertRaises(RuntimeError) as ctx:
                baixar_conector(conector, None, None, tmpdir)
        self.assertIn("Layer sicar:invalida not found", str(ctx.exception))

    def test_baixar_conector_arcgis_rest_geojson_e_validacao(self):
        from geodatahub_airflow.conectores import baixar_conector

        geojson_bytes = json.dumps({"type": "FeatureCollection", "features": []}).encode("utf-8")
        fake_resp = MagicMock()
        fake_resp.headers = {}
        fake_resp.iter_content.return_value = [geojson_bytes]
        fake_resp.raise_for_status.return_value = None

        conector = {
            "id": 2,
            "nome": "ArcGIS Layer",
            "tipo_protocolo": "ARCGIS_REST",
            "url_base": "https://maps.example.com/arcgis/rest/services/Imoveis/FeatureServer/0",
            "formato_dados": "GEOJSON",
            "epsg_origem": "4674",
            "parametros_json": {"where": "UF='PR'"},
            "tipo_autenticacao": "BEARER_TOKEN",
            "nome_credencial": None,
        }

        with tempfile.TemporaryDirectory() as tmpdir, patch("geodatahub_airflow.conectores._http_get", return_value=fake_resp) as mock_get:
            res = baixar_conector(conector, None, "tok-xyz", tmpdir)

        self.assertTrue(mock_get.call_args.args[0].endswith("/query"))
        params = mock_get.call_args.kwargs["params"]
        self.assertEqual(params["f"], "geojson")
        self.assertEqual(params["where"], "UF='PR'")
        self.assertEqual(params["outFields"], "*")
        self.assertEqual(params["outSR"], "4674")
        self.assertEqual(mock_get.call_args.kwargs["headers"]["Authorization"], "Bearer tok-xyz")
        self.assertEqual(res.extensao, ".geojson")

        html_err = MagicMock()
        html_err.headers = {}
        html_err.iter_content.return_value = [b"<html><body>502 Bad Gateway</body></html>"]
        html_err.raise_for_status.return_value = None
        with tempfile.TemporaryDirectory() as tmpdir, patch("geodatahub_airflow.conectores._http_get", return_value=html_err):
            with self.assertRaises(RuntimeError):
                baixar_conector(conector, None, "tok-xyz", tmpdir)

    def test_baixar_conector_google_drive_delega_para_drive(self):
        from geodatahub_airflow.conectores import baixar_conector

        zip_bytes = _make_zip_bytes("a.shp", b"123")
        expected_md5 = hashlib.md5(zip_bytes).hexdigest()
        expected_sha = hashlib.sha256(zip_bytes).hexdigest()

        def fake_drive_baixar(svc, file_id, destino):
            with open(destino, "wb") as f:
                f.write(zip_bytes)
            return expected_md5, expected_sha

        conector = {
            "id": 3,
            "nome": "Drive CAR",
            "tipo_protocolo": "GOOGLE_DRIVE",
            "url_base": "https://drive.google.com/file/d/FILE_123/view",
            "formato_dados": "SHAPE-ZIP",
            "epsg_origem": "4674",
            "parametros_json": {"nome_arquivo": "CAR_PR.zip"},
            "tipo_autenticacao": "NONE",
            "nome_credencial": None,
        }

        with tempfile.TemporaryDirectory() as tmpdir, \
                patch("geodatahub_airflow.drive.servico", return_value=MagicMock()), \
                patch("geodatahub_airflow.drive.baixar", side_effect=fake_drive_baixar) as mock_dl:
            res = baixar_conector(conector, None, None, tmpdir)

        mock_dl.assert_called_once()
        self.assertEqual(mock_dl.call_args.args[1], "FILE_123")
        self.assertEqual(res.md5, expected_md5)
        self.assertEqual(res.sha256, expected_sha)


class TestDbGdalStorage(unittest.TestCase):
    def test_db_novas_funcoes_e_controle_iniciar_com_conector(self):
        from geodatahub_airflow import db

        cur = MagicMock()
        cur.fetchone.side_effect = [
            (77,),
            (5, "SICAR", "desc", "OGC_WFS", "https://u", "CARGA", None, "Camada", 1, 2, "SHAPE-ZIP", "4674", "cod_imovel", {}, "NONE", None, None, None, "MANUAL", None, True),
        ]
        cur.description = [(c,) for c in (
            "id", "nome", "descricao", "tipo_protocolo", "url_base", "destino", "fonte_referencia",
            "nome_camada_exibicao", "orgao_id", "conjunto_id", "formato_dados", "epsg_origem",
            "coluna_identificador", "parametros_json", "tipo_autenticacao", "nome_credencial",
            "prefixo_credencial", "credencial_cifrada", "periodicidade", "expressao_cron", "ativo",
        )]
        con = MagicMock()
        con.cursor.return_value.__enter__.return_value = cur

        with patch.object(db, "conexao", return_value=con):
            cid = db.controle_iniciar(
                {"drive_file_id": "conector:5", "caminho": "https://u", "nome": "sicar.zip", "md5": None, "tamanho": 0},
                fonte="SICAR",
                destino="CARGA",
                run_id="manual__1",
                conector_id=5,
            )
            self.assertEqual(cid, 77)
            insert_sql, insert_params = cur.execute.call_args.args
            self.assertIn("conector_id", insert_sql)
            self.assertEqual(insert_params[-1], 5)

            db.controle_atualizar_etapa(77, "BAIXANDO", "x" * 5000)
            etapa_sql, etapa_params = cur.execute.call_args.args
            self.assertIn("UPDATE ingestao_drive_controle", etapa_sql)
            self.assertTrue(etapa_params[0].startswith("[BAIXANDO] "))
            self.assertEqual(len(etapa_params[0]), 4000)

            db.controle_atualizar_hashes(77, "a" * 32, "b" * 64, 1234, "arq.zip")
            hash_sql, hash_params = cur.execute.call_args.args
            self.assertIn("hash_sha256", hash_sql)
            self.assertEqual(hash_params, ("a" * 32, "b" * 64, 1234, "arq.zip", 77))

            row = db.buscar_conector(5)
            self.assertEqual(row["id"], 5)
            self.assertEqual(row["tipo_protocolo"], "OGC_WFS")

            db.atualizar_status_conector(5, "CARREGADO")
            st_sql, st_params = cur.execute.call_args.args
            self.assertIn("UPDATE conector_fonte_dados", st_sql)
            self.assertEqual(st_params, ("CARREGADO", 5))

    def test_gdal_extensoes_e_csv_open_options(self):
        from geodatahub_airflow import gdal

        for ext in (".shp", ".gpkg", ".geojson", ".json", ".csv"):
            self.assertIn(ext, gdal.EXTENSOES_GEO)

        with patch.object(gdal, "_run") as mock_run:
            gdal.carregar_staging(
                {"host": "db", "port": "5432", "dbname": "geo", "user": "u", "password": "p", "sslmode": ""},
                ["/tmp/queimadas.csv"],
                "stg_teste",
                None,
                None,
            )
        cmd = mock_run.call_args.args[0]
        self.assertIn("X_POSSIBLE_NAMES=longitude,lon,long,x", cmd)
        self.assertIn("Y_POSSIBLE_NAMES=latitude,lat,y", cmd)
        self.assertIn("KEEP_GEOM_COLUMNS=NO", cmd)

    def test_storage_local_retorna_caminho_plano_sem_file_scheme(self):
        from geodatahub_airflow import config, storage

        with tempfile.TemporaryDirectory() as cofre_dir, tempfile.NamedTemporaryFile(delete=False) as src:
            src.write(b"conteudo")
            src_path = src.name
        try:
            with patch.object(config, "STORAGE_BACKEND", "local"), patch.object(config, "COFRE_LOCAL_DIR", cofre_dir):
                res = storage.guardar(src_path, "ab" + "c" * 62, "dados.zip")
            self.assertFalse(res.startswith("file://"))
            self.assertTrue(res.startswith(cofre_dir))
            self.assertTrue(os.path.exists(res))
        finally:
            os.remove(src_path)


class TestPipelineConector(unittest.TestCase):
    def test_processar_conector_inexistente_dispara_lookup_error(self):
        from geodatahub_airflow import db, pipeline

        with patch.object(db, "buscar_conector", return_value=None):
            with self.assertRaises(LookupError):
                pipeline.processar_conector(999)

    def test_processar_conector_referencia_geojson_fluxo_completo(self):
        from geodatahub_airflow import db, gdal, pipeline
        from geodatahub_airflow.conectores import DownloadResult

        conector = {
            "id": 10,
            "nome": "FUNAI WFS",
            "tipo_protocolo": "OGC_WFS",
            "url_base": "https://geoserver.funai.gov.br/geoserver/ows",
            "destino": "REFERENCIA",
            "fonte_referencia": "FUNAI_TERRA_INDIGENA",
            "nome_camada_exibicao": "Terras Indigenas",
            "orgao_id": None,
            "conjunto_id": None,
            "formato_dados": "GEOJSON",
            "epsg_origem": "4674",
            "coluna_identificador": "terrai_cod,ogc_fid",
            "parametros_json": {},
            "tipo_autenticacao": "NONE",
            "nome_credencial": None,
            "ativo": True,
        }

        cur = MagicMock()
        cur.rowcount = 12
        cur.fetchone.return_value = (12,)
        con = MagicMock()
        con.cursor.return_value.__enter__.return_value = cur

        dl_res = DownloadResult(
            caminho_arquivo="/tmp/fake_funai.geojson",
            nome_arquivo="funai.geojson",
            md5="1" * 32,
            sha256="2" * 64,
            tamanho_bytes=512,
            extensao=".geojson",
            tipo_mime="application/geo+json",
        )

        with tempfile.TemporaryDirectory() as data_dir, \
                patch("geodatahub_airflow.config.DATA_DIR", data_dir), \
                patch.object(db, "buscar_conector", return_value=conector), \
                patch.object(db, "controle_iniciar", return_value=501) as mock_ini, \
                patch.object(db, "controle_atualizar_etapa") as mock_etapa, \
                patch.object(db, "controle_atualizar_hashes") as mock_hashes, \
                patch.object(db, "partes_conexao", return_value={"host": "h", "port": "5432", "dbname": "d", "user": "u", "password": "p"}), \
                patch.object(db, "conexao", return_value=con), \
                patch.object(db, "colunas_staging", return_value=["ogc_fid", "terrai_cod", "nome", "geom"]), \
                patch.object(db, "analyze") as mock_analyze, \
                patch.object(db, "apagar_staging") as mock_drop, \
                patch.object(db, "controle_finalizar") as mock_fin, \
                patch.object(db, "atualizar_status_conector") as mock_status, \
                patch("geodatahub_airflow.conectores.baixar_conector", return_value=dl_res), \
                patch.object(gdal, "detectar_srs", return_value=("4674", True)), \
                patch.object(gdal, "carregar_staging") as mock_carregar:
            res = pipeline.processar_conector(10, run_id="run_1")

        mock_ini.assert_called_once()
        etapas = [c.args[1] for c in mock_etapa.call_args_list]
        self.assertIn("BAIXANDO", etapas)
        self.assertIn("PROCESSANDO_POSTGIS", etapas)
        mock_hashes.assert_called_once_with(501, "1" * 32, "2" * 64, 512, "funai.geojson")
        mock_carregar.assert_called_once()
        mock_analyze.assert_called_once_with("camada_referencia")
        mock_drop.assert_called_once()
        mock_fin.assert_called_once()
        self.assertEqual(mock_fin.call_args.args[:2], (501, "CARREGADO"))
        mock_status.assert_called_with(10, "CARREGADO")
        self.assertEqual(res["linhas"], 12)

    def test_processar_conector_carga_notifica_pos_processamento_e_tolera_erro_rede(self):
        from geodatahub_airflow import db, gdal, pipeline, storage
        from geodatahub_airflow.conectores import DownloadResult

        conector = {
            "id": 20,
            "nome": "SICAR PR",
            "tipo_protocolo": "OGC_WFS",
            "url_base": "https://geoserver.car.gov.br/geoserver/sicar/wfs",
            "destino": "CARGA",
            "fonte_referencia": None,
            "nome_camada_exibicao": "Imoveis SICAR",
            "orgao_id": 7,
            "conjunto_id": 9,
            "formato_dados": "SHAPE-ZIP",
            "epsg_origem": "4674",
            "coluna_identificador": "cod_imovel,ogc_fid",
            "parametros_json": {},
            "tipo_autenticacao": "NONE",
            "nome_credencial": None,
            "ativo": True,
        }

        cur = MagicMock()
        # _id queries: operador, e_ingestao, e_tratamento, s_concluida -> then stats -> processo_id -> original_id -> etapa_trat_id -> processado_id
        cur.fetchone.side_effect = [
            (100,),  # operador
            (1,),    # e_ingestao
            (2,),    # e_tratamento
            (3,),    # s_concluida
            (5, 0, 0),  # total, sem_geom, sem_codigo
            (888,),  # processo_id
            (889,),  # original_id
            (890,),  # etapa_trat_id
            (891,),  # processado_id
        ]
        cur.rowcount = 5
        con = MagicMock()
        con.cursor.return_value.__enter__.return_value = cur

        dl_res = DownloadResult(
            caminho_arquivo="/tmp/fake_sicar.zip",
            nome_arquivo="sicar.zip",
            md5="a" * 32,
            sha256="b" * 64,
            tamanho_bytes=2048,
            extensao=".zip",
            tipo_mime="application/zip",
        )

        with tempfile.TemporaryDirectory() as data_dir, \
                patch("geodatahub_airflow.config.DATA_DIR", data_dir), \
                patch.object(db, "buscar_conector", return_value=conector), \
                patch.object(db, "processo_por_hash", return_value=None), \
                patch.object(db, "controle_atualizar_etapa"), \
                patch.object(db, "controle_atualizar_hashes"), \
                patch.object(db, "partes_conexao", return_value={"host": "h", "port": "5432", "dbname": "d", "user": "u", "password": "p"}), \
                patch.object(db, "conexao", return_value=con), \
                patch.object(db, "colunas_staging", return_value=["ogc_fid", "cod_imovel", "geom"]), \
                patch.object(db, "apagar_staging"), \
                patch.object(db, "controle_finalizar") as mock_fin, \
                patch.object(db, "atualizar_status_conector") as mock_status, \
                patch("geodatahub_airflow.conectores.baixar_conector", return_value=dl_res), \
                patch.object(gdal, "extrair", return_value=["/tmp/x/sicar.shp"]), \
                patch.object(gdal, "detectar_srs", return_value=("4674", True)), \
                patch.object(gdal, "carregar_staging"), \
                patch.object(storage, "guardar", return_value="/opt/airflow/data/cofre/bb/bbb/sicar.zip"), \
                patch.object(pipeline, "_notificar_pos_processamento", wraps=pipeline._notificar_pos_processamento) as mock_notify, \
                patch("urllib.request.urlopen", side_effect=OSError("backend offline")):
            res = pipeline.processar_conector(20, ctrl_id=900, run_id="run_carga")

        self.assertEqual(res["processo_id"], 888)
        mock_notify.assert_called_once_with(888)
        mock_fin.assert_called_once()
        self.assertEqual(mock_fin.call_args.args[:2], (900, "CARREGADO"))
        mock_status.assert_called_with(20, "CARREGADO")

    def test_processar_conector_falha_marca_status_falhou(self):
        from geodatahub_airflow import db, pipeline

        conector = {
            "id": 30,
            "nome": "Falho",
            "tipo_protocolo": "HTTP_DIRECT",
            "url_base": "https://x.test/a.zip",
            "destino": "REFERENCIA",
            "fonte_referencia": "FONTE_X",
            "nome_camada_exibicao": "X",
            "orgao_id": None,
            "conjunto_id": None,
            "formato_dados": "SHAPE-ZIP",
            "epsg_origem": "4674",
            "coluna_identificador": "ogc_fid",
            "parametros_json": {},
            "tipo_autenticacao": "NONE",
            "nome_credencial": None,
            "ativo": True,
        }

        with tempfile.TemporaryDirectory() as data_dir, \
                patch("geodatahub_airflow.config.DATA_DIR", data_dir), \
                patch.object(db, "buscar_conector", return_value=conector), \
                patch.object(db, "controle_iniciar", return_value=700), \
                patch.object(db, "controle_atualizar_etapa"), \
                patch.object(db, "controle_finalizar") as mock_fin, \
                patch.object(db, "atualizar_status_conector") as mock_status, \
                patch("geodatahub_airflow.conectores.baixar_conector", side_effect=RuntimeError("timeout")):
            with self.assertRaises(RuntimeError):
                pipeline.processar_conector(30, run_id="run_err")

        mock_fin.assert_called_once()
        self.assertEqual(mock_fin.call_args.args[:2], (700, "FALHOU"))
        mock_status.assert_called_with(30, "FALHOU")


class TestDagDispatch(unittest.TestCase):
    def test_dag_import_e_dispatch_conector_e_legado(self):
        import importlib.util

        airflow_mod = sys.modules["airflow"]
        dec_mod = types.ModuleType("airflow.decorators")

        class FakeDAG:
            def __init__(self, **kwargs):
                self.kwargs = kwargs

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                return False

        captured = {}

        def fake_task(fn):
            captured["fn"] = fn
            return lambda *a, **kw: None

        airflow_mod.DAG = FakeDAG
        dec_mod.task = fake_task
        sys.modules["airflow.decorators"] = dec_mod

        dag_path = AIRFLOW_ROOT / "dags" / "geodatahub_ingestao_dag.py"
        spec = importlib.util.spec_from_file_location("geodatahub_ingestao_dag", dag_path)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)

        self.assertEqual(mod.dag.kwargs["dag_id"], "geodatahub_ingestao")
        self.assertIsNone(mod.dag.kwargs["schedule"])
        self.assertFalse(mod.dag.kwargs["catchup"])
        self.assertEqual(mod.dag.kwargs["max_active_runs"], 3)

        dag_run = MagicMock()
        dag_run.run_id = "manual__2026"
        dag_run.conf = {"conector_id": 42, "ctrl_id": 11, "parametros_override": {"uf": "SP"}}

        with patch("geodatahub_airflow.pipeline.processar_conector", return_value={"processo_id": 1}) as mock_proc, \
                patch("geodatahub_airflow.pipeline.resumo_texto", return_value="ok"), \
                patch("geodatahub_airflow.notify.discord") as mock_disc:
            out = captured["fn"](dag_run=dag_run)

        mock_proc.assert_called_once_with(
            conector_id=42,
            params_override={"uf": "SP"},
            credencial_aberta=None,
            ctrl_id=11,
            run_id="manual__2026",
        )
        mock_disc.assert_called_once_with("ok")
        self.assertEqual(out, {"processo_id": 1})

        dag_run.conf = {"destino": "CARGA", "forcar": ["CAR_AREA_IMOVEL"]}
        with patch("geodatahub_airflow.pipeline.planejar", return_value=[{"id": 1}]) as mock_plan, \
                patch("geodatahub_airflow.pipeline.processar", return_value={"linhas": 3}) as mock_leg, \
                patch("geodatahub_airflow.pipeline.resumo_texto", return_value=None):
            out_leg = captured["fn"](dag_run=dag_run)

        mock_plan.assert_called_once_with(destino="CARGA", forcar=["CAR_AREA_IMOVEL"], run_id="manual__2026")
        mock_leg.assert_called_once_with({"id": 1}, run_id="manual__2026")
        self.assertEqual(out_leg["processados"], 1)


if __name__ == "__main__":
    unittest.main()
