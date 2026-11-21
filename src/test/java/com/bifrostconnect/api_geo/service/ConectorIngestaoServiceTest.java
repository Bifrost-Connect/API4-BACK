package com.bifrostconnect.api_geo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import com.bifrostconnect.api_geo.dto.AirflowIngestaoStatusResponse;
import com.bifrostconnect.api_geo.dto.ConectorFonteDadosRequest;
import com.bifrostconnect.api_geo.dto.ConectorFonteDadosResponse;
import com.bifrostconnect.api_geo.dto.ExecutarConectorRequest;
import com.bifrostconnect.api_geo.dto.ResultadoCalculoAnalitico;
import com.bifrostconnect.api_geo.dto.ResultadoValidacaoTopologica;
import com.bifrostconnect.api_geo.dto.ResultadoValidacaoTopologica.Problema;
import com.bifrostconnect.api_geo.dto.ResultadoValidacaoTopologica.TipoProblema;
import com.bifrostconnect.api_geo.entity.ArquivoProcessado;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ArquivoOriginalRepository;
import com.bifrostconnect.api_geo.repository.ArquivoProcessadoRepository;
import com.bifrostconnect.api_geo.repository.OcorrenciaValidacaoRepository;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;
import com.bifrostconnect.api_geo.repository.RegraValidacaoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

class ConectorIngestaoServiceTest {

    private JdbcTemplate jdbcTemplate;
    private ConectorCriptoService criptoService;
    private ObjectMapper objectMapper;
    private GeoJsonArquivoService geoJsonArquivoService;
    private GeoJsonEpsgService geoJsonEpsgService;
    private ProcessoRepository processoRepository;
    private ProcessoEstadoService processoEstadoService;
    private ProcessoService processoService;
    private ArquivoOriginalRepository arquivoOriginalRepository;
    private ArquivoProcessadoRepository arquivoProcessadoRepository;
    private ValidacaoTopologicaService validacaoTopologicaService;
    private CalculoAnaliticoService calculoAnaliticoService;
    private AuditoriaLogService auditoriaLogService;
    private NotificacaoService notificacaoService;
    private HttpClient httpClient;
    private Executor executorDireto;

    private ConectorIngestaoService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        criptoService = new ConectorCriptoService("segredo-teste-unidade", "");
        objectMapper = new ObjectMapper();
        geoJsonArquivoService = mock(GeoJsonArquivoService.class);
        geoJsonEpsgService = mock(GeoJsonEpsgService.class);
        processoRepository = mock(ProcessoRepository.class);
        processoEstadoService = mock(ProcessoEstadoService.class);
        processoService = mock(ProcessoService.class);
        arquivoOriginalRepository = mock(ArquivoOriginalRepository.class);
        arquivoProcessadoRepository = mock(ArquivoProcessadoRepository.class);
        validacaoTopologicaService = mock(ValidacaoTopologicaService.class);
        calculoAnaliticoService = mock(CalculoAnaliticoService.class);
        auditoriaLogService = mock(AuditoriaLogService.class);
        notificacaoService = mock(NotificacaoService.class);
        httpClient = mock(HttpClient.class);
        executorDireto = Runnable::run;

        service = new ConectorIngestaoService(
                jdbcTemplate,
                criptoService,
                objectMapper,
                geoJsonArquivoService,
                geoJsonEpsgService,
                processoRepository,
                processoEstadoService,
                processoService,
                arquivoOriginalRepository,
                arquivoProcessadoRepository,
                validacaoTopologicaService,
                calculoAnaliticoService,
                auditoriaLogService,
                notificacaoService,
                httpClient,
                executorDireto,
                "http://localhost:8085",
                "admin",
                "admin",
                "geodatahub_ingestao",
                true
        );
    }

    @Test
    @DisplayName("Lista os 7 templates públicos estáveis esperados com seus presets (sem IBAMA PAMGIA quebrado)")
    void deveListarTemplatesPublicosCompletos() {
        List<Map<String, Object>> templates = service.listarTemplatesPublicos();

        assertThat(templates).hasSize(7);
        assertThat(templates)
                .extracting(t -> (String) t.get("codigo"))
                .containsExactly(
                        "SICAR_WFS",
                        "INPE_PRODES_WFS",
                        "FUNAI_TI_WFS",
                        "IBGE_BIOMAS_WFS",
                        "NASA_FIRMS_API",
                        "GOOGLE_DRIVE",
                        "ARCGIS_REST"
                )
                .doesNotContain("IBAMA_EMBARGOS_HTTP");

        Map<String, Object> sicar = templates.get(0);
        assertThat(sicar.get("tipoProtocolo")).isEqualTo("OGC_WFS");
        assertThat(sicar.get("urlBase")).isEqualTo("https://geoserver.car.gov.br/geoserver/sicar/wfs");
        assertThat(sicar.get("destino")).isEqualTo("CARGA");
        assertThat(sicar.get("formatoDados")).isEqualTo("SHAPE-ZIP");
        assertThat(sicar.get("colunaIdentificador")).isEqualTo("cod_imovel,cod_imovel_,cod_car");

        Map<String, Object> firms = templates.get(4);
        assertThat(firms.get("tipoAutenticacao")).isEqualTo("API_KEY_QUERY");
        assertThat(firms.get("nomeCredencial")).isEqualTo("MAP_KEY");
        assertThat(firms.get("fonteReferencia")).isEqualTo("INPE_QUEIMADA");
    }

    @Test
    @DisplayName("Valida destino REFERENCIA exigindo fonteReferencia e valida expressões cron")
    void deveValidarFonteReferenciaECron() {
        ConectorFonteDadosRequest semFonte = criarRequest(
                "Sem Fonte", "OGC_WFS", "https://example.org/wfs", "REFERENCIA", "   ",
                "SHAPE-ZIP", "NONE", null, null, "MANUAL", null, true);

        assertThatThrownBy(() -> service.criarConector(semFonte, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        ConectorFonteDadosRequest cronInvalido = criarRequest(
                "Cron Ruim", "OGC_WFS", "https://example.org/wfs", "CARGA", null,
                "SHAPE-ZIP", "NONE", null, null, "CRON", "nao-e-cron", true);

        assertThatThrownBy(() -> service.criarConector(cronInvalido, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("Cria conector cifrando a credencial e aceitando cron de 5 campos")
    void deveCriarConectorCifrandoCredencialECalculandoProximaExecucao() {
        when(jdbcTemplate.queryForObject(contains("INSERT INTO conector_fonte_dados"), eq(Long.class), any(Object[].class)))
                .thenReturn(15L);
        when(jdbcTemplate.queryForObject(eq("SELECT id FROM usuario WHERE id = ?"), eq(Long.class), eq(1L)))
                .thenReturn(1L);

        ConectorFonteDadosResponse esperado = respostaExemplo(15L, "Conector FIRMS", "HTTP_DIRECT",
                "https://firms.example.org/{API_KEY}", "REFERENCIA", "INPE_QUEIMADA",
                "API_KEY_QUERY", "MAP_KEY", "firm****", true, "CRON", "0 30 4 * * 1-5", true, LocalDateTime.now().plusHours(1));
        doReturn(List.of(esperado)).when(jdbcTemplate)
                .query(contains("FROM conector_fonte_dados c"), any(RowMapper.class), eq(15L));

        ConectorFonteDadosRequest req = criarRequest(
                "Conector FIRMS", "HTTP_DIRECT", "https://firms.example.org/{API_KEY}", "REFERENCIA", "INPE_QUEIMADA",
                "CSV", "API_KEY_QUERY", "MAP_KEY", "firms_secret_9876", "CRON", "30 4 * * 1-5", true);

        ConectorFonteDadosResponse criado = service.criarConector(req, 1L);

        assertThat(criado.id()).isEqualTo(15L);
        assertThat(criado.possuiCredencial()).isTrue();
        assertThat(criado.prefixoCredencial()).isEqualTo("firm****");

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, atLeastOnce()).queryForObject(contains("INSERT INTO conector_fonte_dados"), eq(Long.class), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();

        String textoArgs = java.util.Arrays.stream(args)
                .map(String::valueOf)
                .reduce("", (a, b) -> a + "|" + b);
        assertThat(textoArgs).doesNotContain("firms_secret_9876");
        assertThat(textoArgs).contains("v1:");
        assertThat(textoArgs).contains("firm****");
        assertThat(textoArgs).contains("0 30 4 * * 1-5");
    }

    @Test
    @DisplayName("Atualiza conector preservando credencial cifrada quando campo credencial vem em branco")
    void devePreservarCredencialQuandoAtualizacaoVierEmBranco() {
        String cifradaExistente = criptoService.cifrar("segredo-original-999");
        ConectorIngestaoService.ConectorInterno interno = new ConectorIngestaoService.ConectorInterno(
                7L, "Conector WFS", null, "OGC_WFS", "https://wfs.example.org", "REFERENCIA", "FUNAI_TERRA_INDIGENA",
                "Terras", null, null, "SHAPE-ZIP", "4674", "terrai_cod", Map.of(),
                "BEARER_TOKEN", "Authorization", "segr****", cifradaExistente, "DIARIA", null, true);

        doReturn(List.of(interno)).when(jdbcTemplate)
                .query(contains("c.credencial_cifrada"), any(RowMapper.class), eq(7L));
        doReturn(List.of(respostaExemplo(7L, "Conector WFS", "OGC_WFS", "https://wfs.example.org",
                "REFERENCIA", "FUNAI_TERRA_INDIGENA", "BEARER_TOKEN", "Authorization", "segr****",
                true, "DIARIA", null, true, LocalDateTime.now().plusDays(1))))
                .when(jdbcTemplate).query(contains("AS possui_credencial"), any(RowMapper.class), eq(7L));

        ConectorFonteDadosRequest req = criarRequest(
                "Conector WFS", "OGC_WFS", "https://wfs.example.org", "REFERENCIA", "FUNAI_TERRA_INDIGENA",
                "SHAPE-ZIP", "BEARER_TOKEN", "Authorization", "   ", "DIARIA", null, true);

        ConectorFonteDadosResponse atualizado = service.atualizarConector(7L, req);

        assertThat(atualizado.possuiCredencial()).isTrue();
        ArgumentCaptor<Object[]> updateArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("UPDATE conector_fonte_dados"), updateArgs.capture());
        assertThat(List.of(updateArgs.getValue())).contains(cifradaExistente, "segr****");
    }

    @Test
    @DisplayName("alternarAtivo, buscarConectorPorId e excluirConector retornam 404 quando ID não existe")
    void deveRetornar404QuandoConectorNaoExiste() {
        doReturn(List.of()).when(jdbcTemplate).query(contains("FROM conector_fonte_dados"), any(RowMapper.class), eq(999L));
        when(jdbcTemplate.update(eq("DELETE FROM conector_fonte_dados WHERE id = ?"), eq(999L))).thenReturn(0);

        assertThatThrownBy(() -> service.buscarConectorPorId(999L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> service.alternarAtivo(999L, false))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> service.excluirConector(999L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("testarConexao monta GetCapabilities para WFS, /query para ArcGIS e valida Google Drive")
    @SuppressWarnings("unchecked")
    void deveTestarConexaoParaTodosProtocolos() throws Exception {
        ConectorIngestaoService.ConectorInterno wfs = new ConectorIngestaoService.ConectorInterno(
                1L, "WFS", null, "OGC_WFS", "https://geo.example.org/ows", "REFERENCIA", "FUNAI",
                null, null, null, "SHAPE-ZIP", "4674", "ogc_fid", Map.of("version", "1.0.0"),
                "NONE", null, null, null, "MANUAL", null, true);

        HttpResponse<String> respOk = mock(HttpResponse.class);
        when(respOk.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(respOk);

        doReturn(List.of(wfs)).when(jdbcTemplate).query(contains("c.credencial_cifrada"), any(RowMapper.class), eq(1L));

        Map<String, Object> resWfs = service.testarConexao(1L);
        assertThat(resWfs.get("sucesso")).isEqualTo(true);
        assertThat(resWfs.get("statusHttp")).isEqualTo(200);

        ArgumentCaptor<HttpRequest> reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(reqCaptor.capture(), any());
        assertThat(reqCaptor.getValue().uri().toString()).contains("GetCapabilities");

        // ArcGIS REST com Bearer Token
        String segredoCifrado = criptoService.cifrar("jwt-token-xyz");
        ConectorIngestaoService.ConectorInterno arcgis = new ConectorIngestaoService.ConectorInterno(
                2L, "ArcGIS", null, "ARCGIS_REST", "https://maps.example.org/FeatureServer/0", "REFERENCIA", "CAMADA",
                null, null, null, "GEOJSON", "4674", "objectid", Map.of(),
                "BEARER_TOKEN", null, "jwt-****", segredoCifrado, "MANUAL", null, true);
        doReturn(List.of(arcgis)).when(jdbcTemplate).query(contains("c.credencial_cifrada"), any(RowMapper.class), eq(2L));

        Map<String, Object> resArc = service.testarConexao(2L);
        assertThat(resArc.get("sucesso")).isEqualTo(true);
        verify(httpClient, org.mockito.Mockito.times(2)).send(reqCaptor.capture(), any());
        assertThat(reqCaptor.getValue().uri().toString()).contains("/query?f=json&returnCountOnly=true");
        assertThat(reqCaptor.getValue().headers().firstValue("Authorization")).contains("Bearer jwt-token-xyz");

        // Google Drive sem folderId válido (placeholder)
        ConectorIngestaoService.ConectorInterno drivePlaceholder = new ConectorIngestaoService.ConectorInterno(
                3L, "Drive", null, "GOOGLE_DRIVE", "https://drive.google.com/drive/folders/SEU_FOLDER_ID", "REFERENCIA", "CAR",
                null, null, null, "SHAPE-ZIP", "4674", "cod_imovel", Map.of(),
                "SERVICE_ACCOUNT_JSON", null, null, null, "MANUAL", null, true);
        doReturn(List.of(drivePlaceholder)).when(jdbcTemplate).query(contains("c.credencial_cifrada"), any(RowMapper.class), eq(3L));

        Map<String, Object> resDriveErr = service.testarConexao(3L);
        assertThat(resDriveErr.get("sucesso")).isEqualTo(false);

        // Google Drive configurado corretamente
        String saCifrada = criptoService.cifrar("{\"client_email\":\"sa@proj.iam.gserviceaccount.com\",\"private_key\":\"PEM\"}");
        ConectorIngestaoService.ConectorInterno driveOk = new ConectorIngestaoService.ConectorInterno(
                4L, "Drive OK", null, "GOOGLE_DRIVE", "https://drive.google.com/drive/folders/1AbCdEfGhIjK", "REFERENCIA", "CAR",
                null, null, null, "SHAPE-ZIP", "4674", "cod_imovel", Map.of(),
                "SERVICE_ACCOUNT_JSON", null, "{\"cl****", saCifrada, "MANUAL", null, true);
        doReturn(List.of(driveOk)).when(jdbcTemplate).query(contains("c.credencial_cifrada"), any(RowMapper.class), eq(4L));

        Map<String, Object> resDriveOk = service.testarConexao(4L);
        assertThat(resDriveOk.get("sucesso")).isEqualTo(true);

        // HTTP 500 e erro de rede retornam sucesso=false sem lançar 500
        HttpResponse<String> resp500 = mock(HttpResponse.class);
        when(resp500.statusCode()).thenReturn(500);
        doReturn(List.of(wfs)).when(jdbcTemplate).query(contains("c.credencial_cifrada"), any(RowMapper.class), eq(1L));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp500);
        Map<String, Object> res500 = service.testarConexao(1L);
        assertThat(res500.get("sucesso")).isEqualTo(false);
        assertThat(res500.get("statusHttp")).isEqualTo(500);

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("Timeout"));
        Map<String, Object> resErr = service.testarConexao(1L);
        assertThat(resErr.get("sucesso")).isEqualTo(false);
        assertThat(resErr.get("statusHttp")).isNull();
    }

    @Test
    @DisplayName("habilitarCifrasTlsLegadasParaOrgaos remove TLS_RSA_* preservando demais algoritmos")
    void deveHabilitarCifrasTlsLegadasPreservandoOutrosAlgoritmos() {
        String original = Security.getProperty("jdk.tls.disabledAlgorithms");
        try {
            Security.setProperty(
                    "jdk.tls.disabledAlgorithms",
                    "SSLv3, TLSv1, TLSv1.1, RC4, TLS_RSA_*, DH keySize < 1024");

            ConectorIngestaoService.habilitarCifrasTlsLegadasParaOrgaos();

            String atualizado = Security.getProperty("jdk.tls.disabledAlgorithms");
            assertThat(atualizado)
                    .doesNotContain("TLS_RSA_")
                    .contains("SSLv3")
                    .contains("TLSv1")
                    .contains("TLSv1.1")
                    .contains("RC4")
                    .contains("DH keySize < 1024");
        } finally {
            if (original != null) {
                Security.setProperty("jdk.tls.disabledAlgorithms", original);
            }
        }
    }

    @Test
    @DisplayName("testarConexao envia headers User-Agent de navegador e Accept */* e descreve erros HTTP e SSL")
    @SuppressWarnings("unchecked")
    void deveEnviarHeadersNavegadorEDescreverErrosHttpESsl() throws Exception {
        String segredoCifrado = criptoService.cifrar("segredo-teste-123");
        ConectorIngestaoService.ConectorInterno wfs = new ConectorIngestaoService.ConectorInterno(
                1L, "SICAR", null, "OGC_WFS", "https://geoserver.car.gov.br/geoserver/sicar/wfs", "CARGA", null,
                null, null, null, "SHAPE-ZIP", "4674", "cod_imovel", Map.of(),
                "API_KEY_QUERY", "token", "segr****", segredoCifrado, "MANUAL", null, true);

        doReturn(List.of(wfs)).when(jdbcTemplate).query(contains("c.credencial_cifrada"), any(RowMapper.class), eq(1L));

        HttpResponse<String> resp403 = mock(HttpResponse.class);
        when(resp403.statusCode()).thenReturn(403);
        HttpResponse<String> resp404 = mock(HttpResponse.class);
        when(resp404.statusCode()).thenReturn(404);
        HttpResponse<String> resp401 = mock(HttpResponse.class);
        when(resp401.statusCode()).thenReturn(401);
        HttpResponse<String> resp503 = mock(HttpResponse.class);
        when(resp503.statusCode()).thenReturn(503);
        HttpResponse<String> resp418 = mock(HttpResponse.class);
        when(resp418.statusCode()).thenReturn(418);

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(resp403)
                .thenReturn(resp404)
                .thenReturn(resp401)
                .thenReturn(resp503)
                .thenReturn(resp418)
                .thenThrow(new IOException("Wrapper", new SSLHandshakeException("No appropriate protocol segredo-teste-123")));

        Map<String, Object> r403 = service.testarConexao(1L);
        assertThat(r403.get("sucesso")).isEqualTo(false);
        assertThat(r403.get("statusHttp")).isEqualTo(403);
        assertThat(r403.get("mensagem")).isEqualTo("Servidor retornou 403: acesso negado pelo WAF do órgão.");

        ArgumentCaptor<HttpRequest> reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(reqCaptor.capture(), any());
        assertThat(reqCaptor.getValue().headers().firstValue("User-Agent"))
                .contains(ConectorIngestaoService.USER_AGENT_BROWSER);
        assertThat(reqCaptor.getValue().headers().firstValue("Accept")).contains("*/*");

        Map<String, Object> r404 = service.testarConexao(1L);
        assertThat(r404.get("mensagem")).isEqualTo("Servidor retornou 404: recurso não encontrado na URL informada.");

        Map<String, Object> r401 = service.testarConexao(1L);
        assertThat(r401.get("mensagem")).isEqualTo("Servidor retornou 401: credencial ou chave de API inválida ou ausente.");

        Map<String, Object> r503 = service.testarConexao(1L);
        assertThat(r503.get("mensagem")).isEqualTo("Servidor retornou 503: instabilidade temporária no portal externo.");

        Map<String, Object> r418 = service.testarConexao(1L);
        assertThat(r418.get("mensagem")).isEqualTo("Servidor retornou HTTP 418.");

        Map<String, Object> rSsl = service.testarConexao(1L);
        assertThat(rSsl.get("sucesso")).isEqualTo(false);
        assertThat(rSsl.get("statusHttp")).isNull();
        assertThat(String.valueOf(rSsl.get("mensagem")))
                .startsWith("Falha de handshake TLS/SSL com o servidor do órgão:")
                .doesNotContain("segredo-teste-123")
                .contains("****");
    }

    @Test
    @DisplayName("testarConexaoAdHoc valida payload e testa conectividade sem persistir no banco")
    @SuppressWarnings("unchecked")
    void deveTestarConexaoAdHocSemPersistir() throws Exception {
        ConectorFonteDadosRequest invalido = criarRequest(
                "Invalido", "OGC_WFS", "https://example.org/wfs", "REFERENCIA", null,
                "SHAPE-ZIP", "NONE", null, null, "MANUAL", null, true);

        assertThatThrownBy(() -> service.testarConexaoAdHoc(invalido))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        HttpResponse<String> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(ok);

        ConectorFonteDadosRequest reqArcGis = criarRequest(
                "ArcGIS AdHoc", "ARCGIS_REST", "https://maps.example.org/FeatureServer/0", "REFERENCIA", "FONTE_ADHOC",
                "GEOJSON", "BEARER_TOKEN", "Authorization", "token-adhoc-123", "MANUAL", null, true);

        Map<String, Object> res = service.testarConexaoAdHoc(reqArcGis);

        assertThat(res.get("sucesso")).isEqualTo(true);
        assertThat(res.get("statusHttp")).isEqualTo(200);

        ArgumentCaptor<HttpRequest> reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(reqCaptor.capture(), any());
        assertThat(reqCaptor.getValue().uri().toString()).contains("/query?f=json&returnCountOnly=true");
        assertThat(reqCaptor.getValue().headers().firstValue("Authorization")).contains("Bearer token-adhoc-123");
        assertThat(reqCaptor.getValue().headers().firstValue("User-Agent"))
                .contains(ConectorIngestaoService.USER_AGENT_BROWSER);
        assertThat(reqCaptor.getValue().headers().firstValue("Accept")).contains("*/*");
        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }

    @Test
    @DisplayName("executarFallbackLocal registra mensagem diagnóstica clara em ingestao_drive_controle quando download falha com 403 ou SSL")
    @SuppressWarnings("unchecked")
    void deveRegistrarMensagemDiagnosticaQuandoDownloadFalhar() throws Exception {
        ConectorIngestaoService.ConectorInterno conector = new ConectorIngestaoService.ConectorInterno(
                14L, "SICAR WAF", null, "OGC_WFS", "https://geoserver.car.gov.br/geoserver/sicar/wfs", "CARGA", null,
                "SICAR", 1L, 1L, "SHAPE-ZIP", "4674", "cod_imovel", Map.of("typeName", "sicar:sicar_imoveis_pr"),
                "NONE", null, null, null, "MANUAL", null, true);

        doReturn(List.of(conector)).when(jdbcTemplate).query(contains("c.credencial_cifrada"), any(RowMapper.class), eq(14L));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO ingestao_drive_controle"), eq(Long.class), any(Object[].class)))
                .thenReturn(810L)
                .thenReturn(811L);

        AirflowIngestaoStatusResponse status810 = new AirflowIngestaoStatusResponse(
                810L, 14L, "SICAR WAF", "run_810", "SICAR WAF", "https://geoserver.car.gov.br/geoserver/sicar/wfs",
                "CARGA", "SICAR WAF", "EM_ANDAMENTO", "PENDENTE", null, null, null,
                null, null, null, null, null, "[PENDENTE]", LocalDateTime.now(), null, 0L);
        AirflowIngestaoStatusResponse status811 = new AirflowIngestaoStatusResponse(
                811L, 14L, "SICAR WAF", "run_811", "SICAR WAF", "https://geoserver.car.gov.br/geoserver/sicar/wfs",
                "CARGA", "SICAR WAF", "EM_ANDAMENTO", "PENDENTE", null, null, null,
                null, null, null, null, null, "[PENDENTE]", LocalDateTime.now(), null, 0L);
        doReturn(List.of(status810)).when(jdbcTemplate)
                .query(contains("FROM ingestao_drive_controle c"), any(RowMapper.class), eq(810L));
        doReturn(List.of(status811)).when(jdbcTemplate)
                .query(contains("FROM ingestao_drive_controle c"), any(RowMapper.class), eq(811L));

        HttpResponse<InputStream> resp403 = mock(HttpResponse.class);
        when(resp403.statusCode()).thenReturn(403);

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Airflow offline"))
                .thenReturn(resp403)
                .thenThrow(new IOException("Airflow offline"))
                .thenThrow(new SSLHandshakeException("PKIX path building failed"));

        service.executarConector(14L, null, 1L);

        verify(jdbcTemplate).update(
                contains("UPDATE ingestao_drive_controle"),
                eq("FALHOU"),
                eq("Servidor retornou 403: acesso negado pelo WAF do órgão."),
                eq(810L));

        service.executarConector(14L, null, 1L);

        verify(jdbcTemplate).update(
                contains("UPDATE ingestao_drive_controle"),
                eq("FALHOU"),
                contains("Falha de handshake TLS/SSL com o servidor do órgão:"),
                eq(811L));

        ArgumentCaptor<HttpRequest> reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, org.mockito.Mockito.times(4)).send(reqCaptor.capture(), any());
        List<HttpRequest> reqs = reqCaptor.getAllValues();
        assertThat(reqs.get(1).headers().firstValue("User-Agent")).contains(ConectorIngestaoService.USER_AGENT_BROWSER);
        assertThat(reqs.get(1).headers().firstValue("Accept")).contains("*/*");
    }

    @Test
    @DisplayName("executarConector insere pendência e dispara DAG no Airflow com Basic Auth e credencial decifrada")
    @SuppressWarnings("unchecked")
    void deveExecutarConectorDisparandoDagNoAirflow() throws Exception {
        String segredoCifrado = criptoService.cifrar("chave-secreta-abc");
        ConectorIngestaoService.ConectorInterno conector = new ConectorIngestaoService.ConectorInterno(
                10L, "FIRMS", null, "HTTP_DIRECT", "https://firms.example.org/{API_KEY}", "REFERENCIA", "INPE_QUEIMADA",
                "Queimadas", null, null, "CSV", "4326", "latitude", Map.of("dias", 1),
                "API_KEY_QUERY", "MAP_KEY", "chav****", segredoCifrado, "MANUAL", null, true);

        doReturn(List.of(conector)).when(jdbcTemplate).query(contains("c.credencial_cifrada"), any(RowMapper.class), eq(10L));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO ingestao_drive_controle"), eq(Long.class), any(Object[].class)))
                .thenReturn(501L);

        AirflowIngestaoStatusResponse statusEsperado = new AirflowIngestaoStatusResponse(
                501L, 10L, "FIRMS", "conector_10_run", "FIRMS", "https://firms.example.org/{API_KEY}",
                "REFERENCIA", "INPE_QUEIMADA", "EM_ANDAMENTO", "PENDENTE", null, null, null,
                null, null, null, null, null, "[PENDENTE] Na fila de execução do Airflow...",
                LocalDateTime.now(), null, 0L);
        doReturn(List.of(statusEsperado)).when(jdbcTemplate)
                .query(contains("FROM ingestao_drive_controle c"), any(RowMapper.class), eq(501L));

        HttpResponse<String> airflowResp = mock(HttpResponse.class);
        when(airflowResp.statusCode()).thenReturn(200);
        when(airflowResp.body()).thenReturn("{\"dag_run_id\":\"conector_10_run\"}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(airflowResp);

        AirflowIngestaoStatusResponse resp = service.executarConector(
                10L, new ExecutarConectorRequest(Map.of("uf", "PR"), false), 1L);

        assertThat(resp.id()).isEqualTo(501L);
        assertThat(resp.etapaUi()).isEqualTo("PENDENTE");

        ArgumentCaptor<HttpRequest> reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(reqCaptor.capture(), any());
        assertThat(reqCaptor.getValue().uri().toString())
                .isEqualTo("http://localhost:8085/api/v1/dags/geodatahub_ingestao/dagRuns");
        assertThat(reqCaptor.getValue().headers().firstValue("Authorization")).isPresent();
    }

    @Test
    @DisplayName("executarConector com falha no Airflow e fallback local desativado marca FALHOU e lança 502")
    @SuppressWarnings("unchecked")
    void deveMarcarFalhouQuandoAirflowFalharSemFallback() throws Exception {
        ConectorIngestaoService semFallback = new ConectorIngestaoService(
                jdbcTemplate, criptoService, objectMapper, geoJsonArquivoService, geoJsonEpsgService,
                processoRepository, processoEstadoService, processoService,
                arquivoOriginalRepository, arquivoProcessadoRepository,
                validacaoTopologicaService, calculoAnaliticoService, auditoriaLogService, notificacaoService,
                httpClient, executorDireto, "http://localhost:8085", "admin", "admin", "geodatahub_ingestao", false);

        ConectorIngestaoService.ConectorInterno conector = new ConectorIngestaoService.ConectorInterno(
                11L, "IBAMA", null, "HTTP_DIRECT", "https://pamgia.ibama.gov.br/dados/embargos.zip", "REFERENCIA", "IBAMA_EMBARGO",
                "Embargos", null, null, "SHAPE-ZIP", "4674", "num_tad", Map.of(),
                "NONE", null, null, null, "MANUAL", null, true);

        doReturn(List.of(conector)).when(jdbcTemplate).query(contains("c.credencial_cifrada"), any(RowMapper.class), eq(11L));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO ingestao_drive_controle"), eq(Long.class), any(Object[].class)))
                .thenReturn(601L);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection refused"));

        assertThatThrownBy(() -> semFallback.executarConector(11L, null, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));

        verify(jdbcTemplate).update(contains("UPDATE ingestao_drive_controle"), eq("FALHOU"), anyString(), eq(601L));
    }

    @Test
    @DisplayName("executarConector usa fallback local para REFERENCIA quando Airflow falha e fallbackExecucaoLocal=true")
    @SuppressWarnings("unchecked")
    void deveExecutarFallbackLocalReferenciaQuandoAirflowFalhar() throws Exception {
        ConectorIngestaoService.ConectorInterno conector = new ConectorIngestaoService.ConectorInterno(
                12L, "Local GeoJSON", null, "HTTP_DIRECT", "https://exemplo.org/camada.geojson", "REFERENCIA", "FONTE_TESTE",
                "Camada Teste", null, null, "GEOJSON", "4674", "cod_imovel", Map.of(),
                "NONE", null, null, null, "MANUAL", null, true);

        doReturn(List.of(conector)).when(jdbcTemplate).query(contains("c.credencial_cifrada"), any(RowMapper.class), eq(12L));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO ingestao_drive_controle"), eq(Long.class), any(Object[].class)))
                .thenReturn(701L);

        AirflowIngestaoStatusResponse statusInicial = new AirflowIngestaoStatusResponse(
                701L, 12L, "Local GeoJSON", "run_701", "Local GeoJSON", "https://exemplo.org/camada.geojson",
                "REFERENCIA", "FONTE_TESTE", "EM_ANDAMENTO", "PENDENTE", null, null, null,
                null, null, null, null, null, "[PENDENTE] Na fila...", LocalDateTime.now(), null, 0L);
        doReturn(List.of(statusInicial)).when(jdbcTemplate)
                .query(contains("FROM ingestao_drive_controle c"), any(RowMapper.class), eq(701L));

        String geoJson = """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","properties":{"cod_imovel":"IMV-1"},
                   "geometry":{"type":"Polygon","coordinates":[[[-49.1,-16.1],[-49.1,-16.2],[-49.0,-16.2],[-49.0,-16.1],[-49.1,-16.1]]]}}
                ]}""";

        HttpResponse<InputStream> dlResp = mock(HttpResponse.class);
        when(dlResp.statusCode()).thenReturn(200);
        when(dlResp.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(dlResp.body()).thenReturn(new ByteArrayInputStream(geoJson.getBytes(StandardCharsets.UTF_8)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Airflow offline"))
                .thenReturn(dlResp);

        when(geoJsonArquivoService.preparar(any(Path.class))).thenAnswer(inv -> {
            Path src = inv.getArgument(0);
            Path tmp = Files.createTempFile("fallback_test", ".geojson");
            Files.copy(src, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        });

        AirflowIngestaoStatusResponse resp = service.executarConector(12L, null, 1L);
        assertThat(resp.id()).isEqualTo(701L);

        verify(jdbcTemplate).update(eq("DELETE FROM camada_referencia WHERE fonte = ?"), eq("FONTE_TESTE"));
        verify(jdbcTemplate).batchUpdate(contains("INSERT INTO camada_referencia"), any(List.class));
        verify(jdbcTemplate).update(contains("SET status = 'CARREGADO'"), anyString(), isNull(), anyLong(), anyString(), eq(701L));
        verify(jdbcTemplate).update(contains("ultimo_status = 'CARREGADO'"), eq(12L));
    }

    @Test
    @DisplayName("executarConector usa fallback local para CARGA criando Processo, ArquivoOriginal e acionando ProcessoService")
    @SuppressWarnings("unchecked")
    void deveExecutarFallbackLocalCargaQuandoAirflowFalhar() throws Exception {
        ConectorIngestaoService.ConectorInterno conector = new ConectorIngestaoService.ConectorInterno(
                13L, "SICAR PR", null, "OGC_WFS", "https://geoserver.car.gov.br/geoserver/sicar/wfs", "CARGA", null,
                "Imóveis PR", 1L, 1L, "GEOJSON", "4674", "cod_imovel", Map.of("typeName", "sicar:sicar_imoveis_pr"),
                "NONE", null, null, null, "MANUAL", null, true);

        doReturn(List.of(conector)).when(jdbcTemplate).query(contains("c.credencial_cifrada"), any(RowMapper.class), eq(13L));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO ingestao_drive_controle"), eq(Long.class), any(Object[].class)))
                .thenReturn(801L);

        AirflowIngestaoStatusResponse statusInicial = new AirflowIngestaoStatusResponse(
                801L, 13L, "SICAR PR", "run_801", "SICAR PR", "https://geoserver.car.gov.br/geoserver/sicar/wfs",
                "CARGA", "SICAR PR", "EM_ANDAMENTO", "PENDENTE", null, null, null,
                null, null, null, null, null, "[PENDENTE] Na fila...", LocalDateTime.now(), null, 0L);
        doReturn(List.of(statusInicial)).when(jdbcTemplate)
                .query(contains("FROM ingestao_drive_controle c"), any(RowMapper.class), eq(801L));

        when(jdbcTemplate.queryForList(contains("SELECT processo_id FROM arquivo_original WHERE hash_sha256 = ?"), eq(Long.class), anyString()))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("airflow@georural.com.br"), eq(Long.class)))
                .thenReturn(List.of(42L));
        when(jdbcTemplate.queryForList(contains("FROM feicao_carga fc"), eq(Long.class), eq(99L)))
                .thenReturn(List.of(4L));

        when(processoRepository.saveAndFlush(any(Processo.class))).thenAnswer(inv -> {
            Processo p = inv.getArgument(0);
            p.setId(99L);
            return p;
        });

        String geoJson = "{\"type\":\"FeatureCollection\",\"features\":[]}";
        HttpResponse<InputStream> dlResp = mock(HttpResponse.class);
        when(dlResp.statusCode()).thenReturn(200);
        when(dlResp.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(dlResp.body()).thenReturn(new ByteArrayInputStream(geoJson.getBytes(StandardCharsets.UTF_8)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Airflow offline"))
                .thenReturn(dlResp);

        AirflowIngestaoStatusResponse resp = service.executarConector(13L, null, 1L);
        assertThat(resp.id()).isEqualTo(801L);

        ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
        verify(processoEstadoService).inicializarEstado(any(Processo.class), eq("INGESTAO"), eq("EM_ANDAMENTO"), anyString());
        verify(arquivoOriginalRepository).saveAndFlush(any());
        verify(processoService).processarArquivo(any(Processo.class), anyString(), pathCaptor.capture(), eq(42L));
        verify(jdbcTemplate).update(contains("SET status = 'CARREGADO'"), anyString(), eq(99L), eq(4L), anyString(), eq(801L));
        Files.deleteIfExists(pathCaptor.getValue());
    }

    @Test
    @DisplayName("verificarAgendamentosPeriodicos dispara conectores vencidos e recalcula proxima_execucao_em")
    @SuppressWarnings("unchecked")
    void deveVerificarAgendamentosPeriodicosEDispararExecucao() throws Exception {
        Map<String, Object> devido = Map.of("id", 20L, "periodicidade", "DIARIA");
        when(jdbcTemplate.queryForList(contains("proxima_execucao_em <= now()"))).thenReturn(List.of(devido));

        ConectorIngestaoService.ConectorInterno conector = new ConectorIngestaoService.ConectorInterno(
                20L, "Diario", null, "HTTP_DIRECT", "https://example.org/data.zip", "REFERENCIA", "FONTE_D",
                null, null, null, "SHAPE-ZIP", "4674", "ogc_fid", Map.of(),
                "NONE", null, null, null, "DIARIA", null, true);
        doReturn(List.of(conector)).when(jdbcTemplate).query(contains("c.credencial_cifrada"), any(RowMapper.class), eq(20L));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO ingestao_drive_controle"), eq(Long.class), any(Object[].class)))
                .thenReturn(901L);
        AirflowIngestaoStatusResponse exec = new AirflowIngestaoStatusResponse(
                901L, 20L, "Diario", "run_901", "Diario", "https://example.org/data.zip",
                "REFERENCIA", "FONTE_D", "EM_ANDAMENTO", "PENDENTE", null, null, null,
                null, null, null, null, null, "[PENDENTE]", LocalDateTime.now(), null, 0L);
        doReturn(List.of(exec)).when(jdbcTemplate)
                .query(contains("FROM ingestao_drive_controle c"), any(RowMapper.class), eq(901L));

        HttpResponse<String> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(ok);

        service.verificarAgendamentosPeriodicos();

        verify(jdbcTemplate).update(contains("SET proxima_execucao_em = ?"), any(Timestamp.class), eq(20L));
        verify(httpClient).send(any(HttpRequest.class), any());
    }

    @Test
    @DisplayName("listarExecucoes aplica filtros e limita quantidade")
    void deveListarExecucoesComFiltrosELimite() {
        doReturn(List.of()).when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));

        service.listarExecucoes(10L, "referencia", "carregado", 500);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), argsCaptor.capture());

        assertThat(sqlCaptor.getValue())
                .contains("AND c.conector_id = ?")
                .contains("AND UPPER(c.destino) = ?")
                .contains("AND UPPER(c.status) = ?")
                .contains("LIMIT ?");
        assertThat(argsCaptor.getValue()).containsExactly(10L, "REFERENCIA", "CARREGADO", 200);
    }

    @Test
    @DisplayName("executarPosProcessamentoCarga deixa em VALIDACAO / EM_ANDAMENTO quando sem quarentena")
    void deveExecutarPosProcessamentoSemQuarentena() {
        Processo processo = new Processo();
        processo.setId(50L);
        processo.setOperadorId(2L);

        ArquivoProcessado copia = new ArquivoProcessado();
        copia.setId(500L);
        copia.setProcessoId(50L);

        when(processoRepository.findById(50L)).thenReturn(Optional.of(processo));
        when(jdbcTemplate.queryForList(
                contains("FROM arquivo_processado"),
                eq(Long.class), eq(50L)))
                .thenReturn(List.of(500L));
        when(arquivoProcessadoRepository.findById(500L)).thenReturn(Optional.of(copia));
        when(jdbcTemplate.queryForObject(
                eq("SELECT COUNT(*) FROM feicao_carga WHERE arquivo_processado_id = ?"),
                eq(Integer.class), eq(500L)))
                .thenReturn(3);

        ResultadoValidacaoTopologica topoOk = new ResultadoValidacaoTopologica(3, 3, List.of());
        ResultadoCalculoAnalitico calcOk = new ResultadoCalculoAnalitico(3, new BigDecimal("15.5000"), List.of(), List.of());

        when(validacaoTopologicaService.validarFeicoesJaCarregadas(processo, copia, 3, 3)).thenReturn(topoOk);
        when(calculoAnaliticoService.calcular(processo, copia)).thenReturn(calcOk);

        Map<String, Object> res = service.executarPosProcessamentoCarga(50L, null);

        assertThat(res.get("quarentena")).isEqualTo(false);
        verify(processoEstadoService).atualizarEstado(eq(processo), eq("VALIDACAO"), eq("EM_ANDAMENTO"), anyString());
        verify(processoEstadoService, never()).atualizarEstado(eq(processo), eq("VALIDACAO"), eq("EM_VALIDACAO"), anyString());
        verify(notificacaoService, never()).notificarAuditores(any(), anyString());
    }

    @Test
    @DisplayName("executarPosProcessamentoCarga encaminha para Quarentena em problemas topológicos ou inconsistência de cálculo")
    void deveMoverParaQuarentenaQuandoHouverProblemasNaPosValidacao() {
        Processo processo = new Processo();
        processo.setId(51L);
        processo.setOperadorId(2L);

        ArquivoProcessado copia = new ArquivoProcessado();
        copia.setId(501L);
        copia.setProcessoId(51L);

        when(processoRepository.findById(51L)).thenReturn(Optional.of(processo));
        when(jdbcTemplate.queryForList(contains("FROM arquivo_processado"), eq(Long.class), eq(51L)))
                .thenReturn(List.of(501L));
        when(arquivoProcessadoRepository.findById(501L)).thenReturn(Optional.of(copia));
        when(jdbcTemplate.queryForObject(
                eq("SELECT COUNT(*) FROM feicao_carga WHERE arquivo_processado_id = ?"),
                eq(Integer.class), eq(501L)))
                .thenReturn(2);

        ResultadoValidacaoTopologica topoRuim = new ResultadoValidacaoTopologica(2, 2, List.of(
                new Problema(TipoProblema.POLIGONO_SOBREPOSTO, "CAR-1", "CAR-2", new BigDecimal("1.5"), "Sobreposição")));
        ResultadoCalculoAnalitico calcOk = new ResultadoCalculoAnalitico(2, new BigDecimal("10"), List.of(), List.of());

        when(validacaoTopologicaService.validarFeicoesJaCarregadas(processo, copia, 2, 2)).thenReturn(topoRuim);
        when(calculoAnaliticoService.calcular(processo, copia)).thenReturn(calcOk);

        Map<String, Object> res = service.executarPosProcessamentoCarga(51L, null);

        assertThat(res.get("quarentena")).isEqualTo(true);
        verify(processoEstadoService).atualizarEstado(eq(processo), eq("VALIDACAO"), eq("EM_VALIDACAO"), anyString());
        verify(auditoriaLogService).registrarLog(eq(processo), eq("WARNING"), anyString(), any(), any());
        verify(auditoriaLogService).registrarAuditoria(eq(2L), eq("QUARENTENA_TOPOLOGIA"), anyString());
        verify(notificacaoService).notificarAuditores(eq(processo), anyString());
    }

    @Test
    @DisplayName("executarPosProcessamentoCarga encaminha para Quarentena QUARENTENA_CALCULO quando cálculo inconsistente")
    void deveMoverParaQuarentenaQuandoCalculoInconsistente() {
        Processo processo = new Processo();
        processo.setId(52L);
        processo.setOperadorId(3L);

        ArquivoProcessado copia = new ArquivoProcessado();
        copia.setId(502L);

        when(processoRepository.findById(52L)).thenReturn(Optional.of(processo));
        when(jdbcTemplate.queryForList(contains("FROM arquivo_processado"), eq(Long.class), eq(52L)))
                .thenReturn(List.of(502L));
        when(arquivoProcessadoRepository.findById(502L)).thenReturn(Optional.of(copia));
        when(jdbcTemplate.queryForObject(
                eq("SELECT COUNT(*) FROM feicao_carga WHERE arquivo_processado_id = ?"),
                eq(Integer.class), eq(502L)))
                .thenReturn(1);

        when(validacaoTopologicaService.validarFeicoesJaCarregadas(processo, copia, 1, 1))
                .thenReturn(new ResultadoValidacaoTopologica(1, 1, List.of()));
        when(calculoAnaliticoService.calcular(processo, copia))
                .thenReturn(new ResultadoCalculoAnalitico(1, BigDecimal.ZERO, List.of(), List.of("Área zerada")));

        Map<String, Object> res = service.executarPosProcessamentoCarga(52L, null);

        assertThat(res.get("quarentena")).isEqualTo(true);
        verify(processoEstadoService).atualizarEstado(eq(processo), eq("VALIDACAO"), eq("EM_VALIDACAO"), anyString());
        verify(auditoriaLogService).registrarAuditoria(eq(3L), eq("QUARENTENA_CALCULO"), anyString());
        verify(notificacaoService).notificarAuditores(eq(processo), anyString());
    }

    @Test
    @DisplayName("listarExecucoes mapeia corretamente todas as etapas da UI (PENDENTE, BAIXANDO, PROCESSANDO_POSTGIS, CONCLUIDO, ERRO)")
    void deveMapearEtapaUiNasExecucoes() {
        assertThat(ConectorIngestaoService.resolverEtapaUi("CARREGADO", null)).isEqualTo("CONCLUIDO");
        assertThat(ConectorIngestaoService.resolverEtapaUi("BASELINE", "qualquer")).isEqualTo("CONCLUIDO");
        assertThat(ConectorIngestaoService.resolverEtapaUi("FALHOU", "erro")).isEqualTo("ERRO");
        assertThat(ConectorIngestaoService.resolverEtapaUi("EM_ANDAMENTO", "[BAIXANDO] Conectando...")).isEqualTo("BAIXANDO");
        assertThat(ConectorIngestaoService.resolverEtapaUi("EM_ANDAMENTO", "[PROCESSANDO_POSTGIS] Staging")).isEqualTo("PROCESSANDO_POSTGIS");
        assertThat(ConectorIngestaoService.resolverEtapaUi("EM_ANDAMENTO", "[PENDENTE] Na fila")).isEqualTo("PENDENTE");
        assertThat(ConectorIngestaoService.resolverEtapaUi("EM_ANDAMENTO", null)).isEqualTo("PENDENTE");
    }

    @Nested
    @DisplayName("ValidacaoTopologicaService.validarFeicoesJaCarregadas")
    class ValidacaoFeicoesJaCarregadasTest {

        @Test
        @DisplayName("Executa consultas topológicas para feições já no banco e grava ocorrências")
        void deveValidarFeicoesJaCarregadasERegistrarOcorrencias() throws Exception {
            JdbcTemplate topoJdbc = mock(JdbcTemplate.class);
            FeicaoCargaService feicaoCargaService = mock(FeicaoCargaService.class);
            RegraValidacaoRepository regraRepo = mock(RegraValidacaoRepository.class);
            OcorrenciaValidacaoRepository ocorrenciaRepo = mock(OcorrenciaValidacaoRepository.class);

            ValidacaoTopologicaService topoService = new ValidacaoTopologicaService(
                    topoJdbc, feicaoCargaService, regraRepo, ocorrenciaRepo);

            Processo proc = new Processo();
            proc.setId(90L);
            ArquivoProcessado copia = new ArquivoProcessado();
            copia.setId(900L);

            when(topoJdbc.query(anyString(), any(RowMapper.class), eq(900L)))
                    .thenReturn(List.of())
                    .thenReturn(List.of());
            when(topoJdbc.query(anyString(), any(RowMapper.class), eq(900L), any()))
                    .thenReturn(List.of());

            ResultadoValidacaoTopologica resultado = topoService.validarFeicoesJaCarregadas(proc, copia, 5, 5);

            assertThat(resultado.totalFeicoes()).isEqualTo(5);
            assertThat(resultado.feicoesCarregadas()).isEqualTo(5);
            assertThat(resultado.problemas()).isEmpty();
            verify(feicaoCargaService, never()).carregar(anyLong(), any());
        }
    }

    private ConectorFonteDadosRequest criarRequest(
            String nome,
            String tipoProtocolo,
            String urlBase,
            String destino,
            String fonteReferencia,
            String formatoDados,
            String tipoAutenticacao,
            String nomeCredencial,
            String credencial,
            String periodicidade,
            String expressaoCron,
            Boolean ativo) {
        return new ConectorFonteDadosRequest(
                nome, "Desc", tipoProtocolo, urlBase, destino, fonteReferencia,
                "Camada Exibição", 1L, 1L, formatoDados, "4674", "cod_imovel,ogc_fid",
                Map.of(), tipoAutenticacao, nomeCredencial, credencial, periodicidade, expressaoCron, ativo
        );
    }

    private ConectorFonteDadosResponse respostaExemplo(
            Long id, String nome, String tipoProtocolo, String urlBase, String destino, String fonteReferencia,
            String tipoAuth, String nomeCred, String prefixo, boolean possuiCred,
            String periodicidade, String cron, boolean ativo, LocalDateTime prox) {
        return new ConectorFonteDadosResponse(
                id, nome, "Desc", tipoProtocolo, urlBase, destino, fonteReferencia,
                "Camada Exibição", 1L, "SICAR", 1L, "Imóveis rurais",
                "SHAPE-ZIP", "4674", "cod_imovel,ogc_fid", Map.of(),
                tipoAuth, nomeCred, prefixo, possuiCred, periodicidade, cron, ativo,
                null, prox, null, 1L, LocalDateTime.now(), LocalDateTime.now()
        );
    }
}
