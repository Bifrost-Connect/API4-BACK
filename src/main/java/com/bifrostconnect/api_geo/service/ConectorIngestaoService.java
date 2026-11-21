package com.bifrostconnect.api_geo.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.bifrostconnect.api_geo.dto.AirflowIngestaoStatusResponse;
import com.bifrostconnect.api_geo.dto.ConectorFonteDadosRequest;
import com.bifrostconnect.api_geo.dto.ConectorFonteDadosResponse;
import com.bifrostconnect.api_geo.dto.ExecutarConectorRequest;
import com.bifrostconnect.api_geo.dto.ResultadoCalculoAnalitico;
import com.bifrostconnect.api_geo.dto.ResultadoValidacaoTopologica;
import com.bifrostconnect.api_geo.entity.ArquivoOriginal;
import com.bifrostconnect.api_geo.entity.ArquivoProcessado;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.exception.FalhaGeoespacialException;
import com.bifrostconnect.api_geo.repository.ArquivoOriginalRepository;
import com.bifrostconnect.api_geo.repository.ArquivoProcessadoRepository;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Gerencia conectores externos (OGC WFS, HTTP direto, ArcGIS REST, Google Drive),
 * disparo do Airflow com fallback local, agendamento periódico e pós-processamento de cargas.
 */
@Service
public class ConectorIngestaoService {

    private static final Logger log = LoggerFactory.getLogger(ConectorIngestaoService.class);

    private static final Set<String> PROTOCOLOS_VALIDOS = Set.of("OGC_WFS", "HTTP_DIRECT", "GOOGLE_DRIVE", "ARCGIS_REST");
    private static final Set<String> DESTINOS_VALIDOS = Set.of("CARGA", "REFERENCIA");
    private static final Set<String> FORMATOS_VALIDOS = Set.of("SHAPE-ZIP", "GEOJSON", "CSV", "GPKG");
    private static final Set<String> AUTH_VALIDAS = Set.of(
            "NONE", "API_KEY_HEADER", "API_KEY_QUERY", "BEARER_TOKEN", "BASIC_AUTH", "SERVICE_ACCOUNT_JSON");
    private static final Set<String> PERIODICIDADES_VALIDAS = Set.of(
            "MANUAL", "HORARIA", "DIARIA", "SEMANAL", "MENSAL", "CRON");

    private static final Duration TIMEOUT_CONEXAO_HTTP = Duration.ofSeconds(10);
    private static final Duration TIMEOUT_DOWNLOAD_HTTP = Duration.ofMinutes(5);
    private static final int LOTE_REFERENCIA_MAX = 500;
    private static final int TAMANHO_MAX_CODIGO = 100;
    private static final Pattern DRIVE_FOLDER_PATTERN = Pattern.compile("/folders/([A-Za-z0-9_-]+)");
    private static final Pattern DRIVE_FILE_PATTERN = Pattern.compile("/d/([A-Za-z0-9_-]+)");
    private static final Pattern DRIVE_QUERY_ID_PATTERN = Pattern.compile("[?&]id=([A-Za-z0-9_-]+)");
    private static final Pattern CONTENT_DISP_FILENAME = Pattern.compile(
            "filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final String SELECT_CONECTOR_PUBLICO = """
            SELECT c.id, c.nome, c.descricao, c.tipo_protocolo, c.url_base, c.destino,
                   c.fonte_referencia, c.nome_camada_exibicao, c.orgao_id, o.sigla AS orgao_sigla,
                   c.conjunto_id, cj.nome AS conjunto_nome, c.formato_dados, c.epsg_origem,
                   c.coluna_identificador, c.parametros_json::text AS parametros_json,
                   c.tipo_autenticacao, c.nome_credencial, c.prefixo_credencial,
                   (c.credencial_cifrada IS NOT NULL AND c.credencial_cifrada <> '') AS possui_credencial,
                   c.periodicidade, c.expressao_cron, c.ativo, c.ultima_execucao_em,
                   c.proxima_execucao_em, c.ultimo_status, c.criado_por_id,
                   c.data_criacao, c.data_atualizacao
              FROM conector_fonte_dados c
              LEFT JOIN orgao o ON o.id = c.orgao_id
              LEFT JOIN conjunto cj ON cj.id = c.conjunto_id
            """;

    private static final String SELECT_CONECTOR_INTERNO = """
            SELECT c.id, c.nome, c.descricao, c.tipo_protocolo, c.url_base, c.destino,
                   c.fonte_referencia, c.nome_camada_exibicao, c.orgao_id, c.conjunto_id,
                   c.formato_dados, c.epsg_origem, c.coluna_identificador,
                   c.parametros_json::text AS parametros_json, c.tipo_autenticacao,
                   c.nome_credencial, c.prefixo_credencial, c.credencial_cifrada,
                   c.periodicidade, c.expressao_cron, c.ativo
              FROM conector_fonte_dados c
             WHERE c.id = ?
            """;

    private static final String SELECT_EXECUCOES = """
            SELECT c.id, c.conector_id, cfd.nome AS conector_nome, c.dag_run_id,
                   c.nome_arquivo, c.drive_caminho, c.destino, c.fonte,
                   c.status AS status_banco, c.processo_id,
                   e.nome AS etapa_processo, s.nome AS situacao_processo,
                   c.linhas_carregadas, c.linhas_descartadas, c.tamanho_bytes,
                   c.hash_sha256, c.md5_drive, c.mensagem, c.data_inicio, c.data_fim,
                   EXTRACT(EPOCH FROM (COALESCE(c.data_fim, now()) - c.data_inicio))::bigint AS duracao_segundos
              FROM ingestao_drive_controle c
              LEFT JOIN conector_fonte_dados cfd ON cfd.id = c.conector_id
              LEFT JOIN processo p ON p.id = c.processo_id
              LEFT JOIN etapa e ON e.id = p.etapa_atual_id
              LEFT JOIN situacao s ON s.id = p.situacao_atual_id
            """;

    record ConectorInterno(
            Long id,
            String nome,
            String descricao,
            String tipoProtocolo,
            String urlBase,
            String destino,
            String fonteReferencia,
            String nomeCamadaExibicao,
            Long orgaoId,
            Long conjuntoId,
            String formatoDados,
            String epsgOrigem,
            String colunaIdentificador,
            Map<String, Object> parametrosJson,
            String tipoAutenticacao,
            String nomeCredencial,
            String prefixoCredencial,
            String credencialCifrada,
            String periodicidade,
            String expressaoCron,
            boolean ativo) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final ConectorCriptoService criptoService;
    private final ObjectMapper objectMapper;
    private final GeoJsonArquivoService geoJsonArquivoService;
    private final GeoJsonEpsgService geoJsonEpsgService;
    private final ProcessoRepository processoRepository;
    private final ProcessoEstadoService processoEstadoService;
    private final ProcessoService processoService;
    private final ArquivoOriginalRepository arquivoOriginalRepository;
    private final ArquivoProcessadoRepository arquivoProcessadoRepository;
    private final ValidacaoTopologicaService validacaoTopologicaService;
    private final CalculoAnaliticoService calculoAnaliticoService;
    private final AuditoriaLogService auditoriaLogService;
    private final NotificacaoService notificacaoService;
    private final HttpClient httpClient;
    private final Executor localFallbackExecutor;
    private final String airflowApiUrl;
    private final String airflowApiUsername;
    private final String airflowApiPassword;
    private final String airflowDagId;
    private final boolean fallbackExecucaoLocal;

    @Autowired
    public ConectorIngestaoService(
            JdbcTemplate jdbcTemplate,
            ConectorCriptoService criptoService,
            ObjectMapper objectMapper,
            GeoJsonArquivoService geoJsonArquivoService,
            GeoJsonEpsgService geoJsonEpsgService,
            ProcessoRepository processoRepository,
            ProcessoEstadoService processoEstadoService,
            ProcessoService processoService,
            ArquivoOriginalRepository arquivoOriginalRepository,
            ArquivoProcessadoRepository arquivoProcessadoRepository,
            ValidacaoTopologicaService validacaoTopologicaService,
            CalculoAnaliticoService calculoAnaliticoService,
            AuditoriaLogService auditoriaLogService,
            NotificacaoService notificacaoService,
            @Value("${airflow.api.url:http://localhost:8085}") String airflowApiUrl,
            @Value("${airflow.api.username:admin}") String airflowApiUsername,
            @Value("${airflow.api.password:admin}") String airflowApiPassword,
            @Value("${airflow.dag.id:geodatahub_ingestao}") String airflowDagId,
            @Value("${airflow.fallback-execucao-local:true}") boolean fallbackExecucaoLocal) {
        this(
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
                HttpClient.newBuilder().connectTimeout(TIMEOUT_CONEXAO_HTTP).followRedirects(HttpClient.Redirect.NORMAL).build(),
                ForkJoinPool.commonPool(),
                airflowApiUrl,
                airflowApiUsername,
                airflowApiPassword,
                airflowDagId,
                fallbackExecucaoLocal);
    }

    ConectorIngestaoService(
            JdbcTemplate jdbcTemplate,
            ConectorCriptoService criptoService,
            ObjectMapper objectMapper,
            GeoJsonArquivoService geoJsonArquivoService,
            GeoJsonEpsgService geoJsonEpsgService,
            ProcessoRepository processoRepository,
            ProcessoEstadoService processoEstadoService,
            ProcessoService processoService,
            ArquivoOriginalRepository arquivoOriginalRepository,
            ArquivoProcessadoRepository arquivoProcessadoRepository,
            ValidacaoTopologicaService validacaoTopologicaService,
            CalculoAnaliticoService calculoAnaliticoService,
            AuditoriaLogService auditoriaLogService,
            NotificacaoService notificacaoService,
            HttpClient httpClient,
            Executor localFallbackExecutor,
            String airflowApiUrl,
            String airflowApiUsername,
            String airflowApiPassword,
            String airflowDagId,
            boolean fallbackExecucaoLocal) {
        this.jdbcTemplate = jdbcTemplate;
        this.criptoService = criptoService;
        this.objectMapper = objectMapper;
        this.geoJsonArquivoService = geoJsonArquivoService;
        this.geoJsonEpsgService = geoJsonEpsgService;
        this.processoRepository = processoRepository;
        this.processoEstadoService = processoEstadoService;
        this.processoService = processoService;
        this.arquivoOriginalRepository = arquivoOriginalRepository;
        this.arquivoProcessadoRepository = arquivoProcessadoRepository;
        this.validacaoTopologicaService = validacaoTopologicaService;
        this.calculoAnaliticoService = calculoAnaliticoService;
        this.auditoriaLogService = auditoriaLogService;
        this.notificacaoService = notificacaoService;
        this.httpClient = httpClient;
        this.localFallbackExecutor = localFallbackExecutor;
        this.airflowApiUrl = airflowApiUrl;
        this.airflowApiUsername = airflowApiUsername;
        this.airflowApiPassword = airflowApiPassword;
        this.airflowDagId = airflowDagId;
        this.fallbackExecucaoLocal = fallbackExecucaoLocal;
    }

    public List<Map<String, Object>> listarTemplatesPublicos() {
        return List.of(
                template("SICAR_WFS", "SICAR Nacional - Imóveis Rurais",
                        "Consulta direta ao GeoServer oficial do SICAR por UF, município, código CAR e situação.",
                        "OGC_WFS", "https://geoserver.car.gov.br/geoserver/sicar/wfs",
                        "CARGA", null, "Imóveis Rurais SICAR (WFS)", "SHAPE-ZIP", "4674",
                        "cod_imovel,cod_imovel_,cod_car",
                        Map.of("version", "1.0.0", "typeName", "sicar:sicar_imoveis_pr", "uf", "PR", "maxFeatures", 5000),
                        "NONE", null),
                template("INPE_PRODES_WFS", "INPE TerraBrasilis - PRODES Desmatamento",
                        "Polígonos oficiais de desmatamento anual PRODES publicados pelo INPE TerraBrasilis.",
                        "OGC_WFS", "https://terrabrasilis.dpi.inpe.br/geoserver/ows",
                        "REFERENCIA", "INPE_DESMATAMENTO_CERRADO", "Desmatamento Anual Cerrado PRODES (INPE)",
                        "SHAPE-ZIP", "4674", "uid,uuid,ogc_fid",
                        Map.of("version", "1.0.0", "typeName", "prodes-cerrado-nb:accumulated_deforestation_2000", "maxFeatures", 5000),
                        "NONE", null),
                template("FUNAI_TI_WFS", "FUNAI - Terras Indígenas Poligonais",
                        "Limites oficiais de Terras Indígenas diretamente do GeoServer público da FUNAI.",
                        "OGC_WFS", "https://geoserver.funai.gov.br/geoserver/ows",
                        "REFERENCIA", "FUNAI_TERRA_INDIGENA", "Terras Indígenas (FUNAI)",
                        "SHAPE-ZIP", "4674", "terrai_cod,ogc_fid",
                        Map.of("version", "1.0.0", "typeName", "Funai:tis_poligonais", "maxFeatures", 5000),
                        "NONE", null),
                template("IBGE_BIOMAS_WFS", "IBGE Geoserviços - Biomas 1:250.000",
                        "Limites oficiais dos Biomas Brasileiros publicados via OGC WFS pelo IBGE.",
                        "OGC_WFS", "https://geoservicos.ibge.gov.br/geoserver/ows",
                        "REFERENCIA", "IBGE_BIOMA_250MIL", "Biomas 1:250.000 (IBGE)",
                        "SHAPE-ZIP", "4674", "cd_bioma,ogc_fid",
                        Map.of("version", "1.0.0", "typeName", "CGMAT:qg_2025_240_bioma", "maxFeatures", 5000),
                        "NONE", null),
                template("NASA_FIRMS_API", "NASA FIRMS - Focos de Calor Ativos VIIRS",
                        "Focos ativos de calor VIIRS NRT Brasil via API REST da NASA FIRMS (requer MAP_KEY).",
                        "HTTP_DIRECT", "https://firms.modaps.eosdis.nasa.gov/api/country/csv/{API_KEY}/VIIRS_SNPP_NRT/BRA/1",
                        "REFERENCIA", "INPE_QUEIMADA", "Focos de Calor Ativos VIIRS (NASA FIRMS)",
                        "CSV", "4326", "latitude,acq_date",
                        Map.of(), "API_KEY_QUERY", "MAP_KEY"),
                template("IBAMA_EMBARGOS_HTTP", "IBAMA Dados Abertos - Áreas Embargadas",
                        "Download direto do pacote oficial de áreas embargadas publicado pelo IBAMA.",
                        "HTTP_DIRECT", "https://pamgia.ibama.gov.br/dados/adm_embargos_ibama_a.zip",
                        "REFERENCIA", "IBAMA_EMBARGO", "Áreas Embargadas (IBAMA)",
                        "SHAPE-ZIP", "4674", "num_tad,num_tad_,ogc_fid",
                        Map.of(), "NONE", null),
                template("GOOGLE_DRIVE", "Google Drive Institucional",
                        "Sincronização de arquivos geoespaciais hospedados em pasta/arquivo no Google Drive via Service Account.",
                        "GOOGLE_DRIVE", "https://drive.google.com/drive/folders/SEU_FOLDER_ID",
                        "REFERENCIA", "CAR_AREA_IMOVEL", "Base Google Drive Institucional",
                        "SHAPE-ZIP", "4674", "cod_imovel,ogc_fid",
                        Map.of(), "SERVICE_ACCOUNT_JSON", null),
                template("ARCGIS_REST", "ArcGIS FeatureServer / API GeoJSON Customizada",
                        "Consulta paginada ou filtrada em serviços ArcGIS FeatureServer/MapServer REST com saída GeoJSON.",
                        "ARCGIS_REST", "https://exemplo.org/arcgis/rest/services/Camada/FeatureServer/0",
                        "REFERENCIA", "CAMADA_EXTERNA", "Camada ArcGIS FeatureServer",
                        "GEOJSON", "4674", "objectid,id,cod_imovel",
                        Map.of("where", "1=1"), "BEARER_TOKEN", null)
        );
    }

    public List<ConectorFonteDadosResponse> listarConectores() {
        return jdbcTemplate.query(
                SELECT_CONECTOR_PUBLICO + " ORDER BY c.id DESC",
                (rs, i) -> mapearConectorPublico(rs));
    }

    public ConectorFonteDadosResponse buscarConectorPorId(Long id) {
        List<ConectorFonteDadosResponse> lista = jdbcTemplate.query(
                SELECT_CONECTOR_PUBLICO + " WHERE c.id = ?",
                (rs, i) -> mapearConectorPublico(rs),
                id);
        if (lista.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conector não encontrado para o ID: " + id);
        }
        return lista.get(0);
    }

    public ConectorFonteDadosResponse criarConector(ConectorFonteDadosRequest req, Long usuarioId) {
        DadosNormalizados dados = validarENormalizar(req);

        String credencialCifrada = "NONE".equals(dados.tipoAutenticacao())
                ? null
                : criptoService.cifrar(req.credencial());
        String prefixoCredencial = "NONE".equals(dados.tipoAutenticacao())
                ? null
                : criptoService.mascararPrefixo(req.credencial());

        Long criadoPor = resolverUsuarioExistente(usuarioId);
        LocalDateTime proximaExecucao = calcularProximaExecucao(dados.periodicidade(), dados.expressaoCron(), dados.ativo());
        String parametrosStr = serializarJson(dados.parametrosJson());

        try {
            Long id = jdbcTemplate.queryForObject("""
                    INSERT INTO conector_fonte_dados (
                        nome, descricao, tipo_protocolo, url_base, destino, fonte_referencia,
                        nome_camada_exibicao, orgao_id, conjunto_id, formato_dados, epsg_origem,
                        coluna_identificador, parametros_json, tipo_autenticacao, nome_credencial,
                        prefixo_credencial, credencial_cifrada, periodicidade, expressao_cron,
                        ativo, proxima_execucao_em, criado_por_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """,
                    Long.class,
                    dados.nome(),
                    dados.descricao(),
                    dados.tipoProtocolo(),
                    dados.urlBase(),
                    dados.destino(),
                    dados.fonteReferencia(),
                    dados.nomeCamadaExibicao(),
                    req.orgaoId(),
                    req.conjuntoId(),
                    dados.formatoDados(),
                    dados.epsgOrigem(),
                    dados.colunaIdentificador(),
                    parametrosStr,
                    dados.tipoAutenticacao(),
                    dados.nomeCredencial(),
                    prefixoCredencial,
                    credencialCifrada,
                    dados.periodicidade(),
                    dados.expressaoCron(),
                    dados.ativo(),
                    paraTimestamp(proximaExecucao),
                    criadoPor);

            return buscarConectorPorId(id);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Não foi possível cadastrar o conector (verifique duplicidade de nome ou vínculos).",
                    e);
        }
    }

    public ConectorFonteDadosResponse atualizarConector(Long id, ConectorFonteDadosRequest req) {
        ConectorInterno existente = buscarConectorInterno(id);
        DadosNormalizados dados = validarENormalizar(req);

        String credencialCifrada;
        String prefixoCredencial;
        if ("NONE".equals(dados.tipoAutenticacao())) {
            credencialCifrada = null;
            prefixoCredencial = null;
        } else if (req.credencial() != null && !req.credencial().isBlank()) {
            credencialCifrada = criptoService.cifrar(req.credencial());
            prefixoCredencial = criptoService.mascararPrefixo(req.credencial());
        } else {
            credencialCifrada = existente.credencialCifrada();
            prefixoCredencial = existente.prefixoCredencial();
        }

        LocalDateTime proximaExecucao = calcularProximaExecucao(dados.periodicidade(), dados.expressaoCron(), dados.ativo());
        String parametrosStr = serializarJson(dados.parametrosJson());

        try {
            jdbcTemplate.update("""
                    UPDATE conector_fonte_dados
                       SET nome = ?,
                           descricao = ?,
                           tipo_protocolo = ?,
                           url_base = ?,
                           destino = ?,
                           fonte_referencia = ?,
                           nome_camada_exibicao = ?,
                           orgao_id = ?,
                           conjunto_id = ?,
                           formato_dados = ?,
                           epsg_origem = ?,
                           coluna_identificador = ?,
                           parametros_json = ?::jsonb,
                           tipo_autenticacao = ?,
                           nome_credencial = ?,
                           prefixo_credencial = ?,
                           credencial_cifrada = ?,
                           periodicidade = ?,
                           expressao_cron = ?,
                           ativo = ?,
                           proxima_execucao_em = ?,
                           data_atualizacao = now()
                     WHERE id = ?
                    """,
                    dados.nome(),
                    dados.descricao(),
                    dados.tipoProtocolo(),
                    dados.urlBase(),
                    dados.destino(),
                    dados.fonteReferencia(),
                    dados.nomeCamadaExibicao(),
                    req.orgaoId(),
                    req.conjuntoId(),
                    dados.formatoDados(),
                    dados.epsgOrigem(),
                    dados.colunaIdentificador(),
                    parametrosStr,
                    dados.tipoAutenticacao(),
                    dados.nomeCredencial(),
                    prefixoCredencial,
                    credencialCifrada,
                    dados.periodicidade(),
                    dados.expressaoCron(),
                    dados.ativo(),
                    paraTimestamp(proximaExecucao),
                    id);

            return buscarConectorPorId(id);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Não foi possível atualizar o conector (verifique duplicidade de nome ou vínculos).",
                    e);
        }
    }

    public ConectorFonteDadosResponse alternarAtivo(Long id, boolean ativo) {
        ConectorInterno existente = buscarConectorInterno(id);
        LocalDateTime proximaExecucao = calcularProximaExecucao(
                existente.periodicidade(), existente.expressaoCron(), ativo);

        jdbcTemplate.update("""
                UPDATE conector_fonte_dados
                   SET ativo = ?,
                       proxima_execucao_em = ?,
                       data_atualizacao = now()
                 WHERE id = ?
                """,
                ativo,
                paraTimestamp(proximaExecucao),
                id);

        return buscarConectorPorId(id);
    }

    public void excluirConector(Long id) {
        int removidos = jdbcTemplate.update("DELETE FROM conector_fonte_dados WHERE id = ?", id);
        if (removidos == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conector não encontrado para o ID: " + id);
        }
    }

    public Map<String, Object> testarConexao(Long id) {
        ConectorInterno conector = buscarConectorInterno(id);
        String segredo = criptoService.decifrar(conector.credencialCifrada());
        long inicioNs = System.nanoTime();

        if ("GOOGLE_DRIVE".equals(conector.tipoProtocolo())) {
            return validarConfigGoogleDrive(conector, segredo, inicioNs);
        }

        if (!"NONE".equals(conector.tipoAutenticacao()) && (segredo == null || segredo.isBlank())) {
            return resultadoTeste(false, null,
                    "Credencial não configurada para autenticação " + conector.tipoAutenticacao() + ".",
                    decorridoMs(inicioNs));
        }

        try {
            String urlTeste = montarUrlTeste(conector);
            HttpRequest.Builder builder = HttpRequest.newBuilder().GET().timeout(TIMEOUT_CONEXAO_HTTP);
            String urlFinal = aplicarAutenticacaoHttp(
                    conector.tipoAutenticacao(), conector.nomeCredencial(), segredo, urlTeste, builder);
            builder.uri(URI.create(urlFinal));

            HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            boolean sucesso = status >= 200 && status < 400;
            String msg = sucesso
                    ? "Conexão validada com sucesso (HTTP " + status + ")."
                    : "Endpoint respondeu com HTTP " + status + ".";
            return resultadoTeste(sucesso, status, msg, decorridoMs(inicioNs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return resultadoTeste(false, null, "Teste de conexão interrompido.", decorridoMs(inicioNs));
        } catch (Exception e) {
            return resultadoTeste(false, null,
                    "Falha ao conectar ao endpoint: " + sanitizarErro(e.getMessage(), segredo),
                    decorridoMs(inicioNs));
        }
    }

    public AirflowIngestaoStatusResponse executarConector(
            Long id,
            ExecutarConectorRequest override,
            Long usuarioId) {
        ConectorInterno conector = buscarConectorInterno(id);
        String segredo = criptoService.decifrar(conector.credencialCifrada());
        Map<String, Object> paramsOverride = override != null && override.parametrosOverride() != null
                ? override.parametrosOverride()
                : Map.of();

        String runId = "conector_" + id + "_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().substring(0, 8);
        String fonteCtrl = resolverFonteControle(conector);
        String urlCaminho = limitar(conector.urlBase(), 1000);
        String nomeCtrl = limitar(conector.nome(), 255);

        Long ctrlId = jdbcTemplate.queryForObject("""
                INSERT INTO ingestao_drive_controle (
                    drive_file_id, drive_caminho, nome_arquivo, destino, fonte,
                    md5_drive, status, mensagem, dag_run_id, conector_id
                ) VALUES (?, ?, ?, ?, ?, NULL, 'EM_ANDAMENTO', '[PENDENTE] Na fila de execução do Airflow...', ?, ?)
                RETURNING id
                """,
                Long.class,
                "conector:" + id,
                urlCaminho,
                nomeCtrl,
                conector.destino(),
                fonteCtrl,
                runId,
                id);

        jdbcTemplate.update("""
                UPDATE conector_fonte_dados
                   SET ultimo_status = 'EM_ANDAMENTO',
                       ultima_execucao_em = now(),
                       data_atualizacao = now()
                 WHERE id = ?
                """, id);

        boolean disparouAirflow = dispararDagAirflow(id, ctrlId, runId, paramsOverride, segredo, override);
        if (!disparouAirflow) {
            if (!fallbackExecucaoLocal) {
                String erroMsg = "Não foi possível acionar o Airflow para o conector #" + id + ".";
                finalizarControleFalha(ctrlId, id, erroMsg);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, erroMsg);
            }
            log.warn("Airflow indisponível para conector #{} (ctrl_id={}); iniciando fallback local assíncrono.", id, ctrlId);
            CompletableFuture.runAsync(
                    () -> executarFallbackLocal(conector, ctrlId, paramsOverride, segredo, usuarioId),
                    localFallbackExecutor);
        }

        return buscarExecucaoPorId(ctrlId);
    }

    @Scheduled(fixedDelay = 60000)
    public void verificarAgendamentosPeriodicos() {
        List<Map<String, Object>> devidos = jdbcTemplate.queryForList("""
                SELECT id, periodicidade, expressao_cron
                  FROM conector_fonte_dados
                 WHERE ativo = TRUE
                   AND periodicidade <> 'MANUAL'
                   AND proxima_execucao_em IS NOT NULL
                   AND proxima_execucao_em <= now()
                """);

        for (Map<String, Object> row : devidos) {
            Long id = ((Number) row.get("id")).longValue();
            String periodicidade = String.valueOf(row.get("periodicidade"));
            String expressaoCron = row.get("expressao_cron") != null ? String.valueOf(row.get("expressao_cron")) : null;

            try {
                LocalDateTime proxima = calcularProximaExecucao(periodicidade, expressaoCron, true);
                jdbcTemplate.update("""
                        UPDATE conector_fonte_dados
                           SET proxima_execucao_em = ?,
                               data_atualizacao = now()
                         WHERE id = ?
                        """, paraTimestamp(proxima), id);

                executarConector(id, null, null);
            } catch (Exception e) {
                log.error("Erro ao disparar agendamento periódico do conector #{}: {}", id, e.getMessage());
            }
        }
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Map<String, Object> executarPosProcessamentoCarga(Long processoId, Long usuarioId) {
        Processo processo = processoRepository.findById(processoId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Processo não encontrado para o ID: " + processoId));

        List<Long> copiasIds = jdbcTemplate.queryForList("""
                SELECT id FROM arquivo_processado
                 WHERE processo_id = ?
                 ORDER BY versao DESC, id DESC
                 LIMIT 1
                """, Long.class, processoId);

        if (copiasIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Processo " + processoId + " não possui cópia de trabalho (arquivo_processado).");
        }

        Long copiaId = copiasIds.get(0);
        ArquivoProcessado copia = arquivoProcessadoRepository.findById(copiaId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Arquivo processado não encontrado para o ID: " + copiaId));

        processoEstadoService.atualizarEstado(
                processo,
                "VALIDACAO",
                "EM_ANDAMENTO",
                "Validação pós-ingestão Airflow iniciada.");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM feicao_carga WHERE arquivo_processado_id = ?",
                Integer.class,
                copia.getId());
        int totalFeicoes = count != null ? count : 0;

        ResultadoValidacaoTopologica topologia;
        try {
            topologia = validacaoTopologicaService.validarFeicoesJaCarregadas(
                    processo, copia, totalFeicoes, totalFeicoes);
        } catch (FalhaGeoespacialException e) {
            return enviarParaQuarentena(
                    processo,
                    copia.getId(),
                    totalFeicoes,
                    usuarioId,
                    "QUARENTENA_GEOESPACIAL",
                    "Falha geoespacial na " + e.getOperacao() + " (" + e.getTipo().getDescricao() + ").",
                    List.of(e.getDetalheTecnico()),
                    e.getCodigoErro(),
                    e.getDetalheTecnico());
        }

        ResultadoCalculoAnalitico calculo = null;
        try {
            calculo = calculoAnaliticoService.calcular(processo, copia);
        } catch (FalhaGeoespacialException e) {
            return enviarParaQuarentena(
                    processo,
                    copia.getId(),
                    totalFeicoes,
                    usuarioId,
                    "QUARENTENA_GEOESPACIAL",
                    "Falha geoespacial na " + e.getOperacao() + " (" + e.getTipo().getDescricao() + ").",
                    List.of(e.getDetalheTecnico()),
                    e.getCodigoErro(),
                    e.getDetalheTecnico());
        } catch (Exception e) {
            auditoriaLogService.registrarLog(
                    processo,
                    "WARNING",
                    "Cálculo analítico pós-ingestão Airflow suspenso: " + e.getMessage());
        }

        List<ResultadoValidacaoTopologica.Problema> inaceitaveis = topologia.problemasInaceitaveis();
        if (!inaceitaveis.isEmpty()) {
            return enviarParaQuarentena(
                    processo,
                    copia.getId(),
                    totalFeicoes,
                    usuarioId,
                    "QUARENTENA_TOPOLOGIA",
                    "Validação topológica encontrou " + inaceitaveis.size()
                            + " sobreposição(ões)/duplicação(ões) inaceitável(is).",
                    inaceitaveis.stream().map(ResultadoValidacaoTopologica.Problema::mensagem).toList(),
                    null,
                    null);
        }

        if (calculo != null && calculo.possuiInconsistencias()) {
            return enviarParaQuarentena(
                    processo,
                    copia.getId(),
                    totalFeicoes,
                    usuarioId,
                    "QUARENTENA_CALCULO",
                    "Cálculo analítico encontrou " + calculo.inconsistencias().size()
                            + " inconsistência(s) matemática(s)/geométrica(s).",
                    calculo.inconsistencias(),
                    null,
                    null);
        }

        auditoriaLogService.registrarLog(
                processo,
                "INFO",
                "Validação e cálculo pós-ingestão Airflow concluídos (" + totalFeicoes
                        + " feições). Aguardando decisão do Gestor na etapa de Validação.");

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("processoId", processo.getId());
        resultado.put("arquivoProcessadoId", copia.getId());
        resultado.put("totalFeicoes", totalFeicoes);
        resultado.put("quarentena", false);
        resultado.put("etapa", "VALIDACAO");
        resultado.put("situacao", "EM_ANDAMENTO");
        return resultado;
    }

    public List<AirflowIngestaoStatusResponse> listarExecucoes(
            Long conectorId,
            String destino,
            String status,
            int limit) {
        StringBuilder sql = new StringBuilder(SELECT_EXECUCOES + " WHERE 1 = 1");
        List<Object> args = new ArrayList<>();

        if (conectorId != null) {
            sql.append(" AND c.conector_id = ?");
            args.add(conectorId);
        }
        if (destino != null && !destino.isBlank()) {
            sql.append(" AND UPPER(c.destino) = ?");
            args.add(destino.trim().toUpperCase(Locale.ROOT));
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND UPPER(c.status) = ?");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }

        int limiteEfetivo = limit <= 0 ? 50 : Math.min(limit, 200);
        sql.append(" ORDER BY c.data_inicio DESC, c.id DESC LIMIT ?");
        args.add(limiteEfetivo);

        return jdbcTemplate.query(sql.toString(), (rs, i) -> mapearExecucao(rs), args.toArray());
    }

    static String resolverEtapaUi(String statusBanco, String mensagem) {
        if ("CARREGADO".equals(statusBanco) || "BASELINE".equals(statusBanco)) {
            return "CONCLUIDO";
        }
        if ("FALHOU".equals(statusBanco)) {
            return "ERRO";
        }
        if ("EM_ANDAMENTO".equals(statusBanco) && mensagem != null) {
            if (mensagem.startsWith("[BAIXANDO]")) {
                return "BAIXANDO";
            }
            if (mensagem.startsWith("[PROCESSANDO_POSTGIS]")) {
                return "PROCESSANDO_POSTGIS";
            }
        }
        return "PENDENTE";
    }

    // ------------------------------------------------------------------
    // Disparo Airflow & Fallback local
    // ------------------------------------------------------------------

    private boolean dispararDagAirflow(
            Long conectorId,
            Long ctrlId,
            String runId,
            Map<String, Object> paramsOverride,
            String credencialAberta,
            ExecutarConectorRequest override) {
        try {
            Map<String, Object> conf = new LinkedHashMap<>();
            conf.put("conector_id", conectorId);
            conf.put("ctrl_id", ctrlId);
            if (!paramsOverride.isEmpty()) {
                conf.put("parametros_override", paramsOverride);
            }
            if (credencialAberta != null && !credencialAberta.isBlank()) {
                conf.put("credencial_aberta", credencialAberta);
            }
            if (override != null && Boolean.TRUE.equals(override.forcarReprocessamento())) {
                conf.put("forcar_reprocessamento", true);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("dag_run_id", runId);
            payload.put("conf", conf);

            String base = airflowApiUrl.endsWith("/")
                    ? airflowApiUrl.substring(0, airflowApiUrl.length() - 1)
                    : airflowApiUrl;
            String url = base + "/api/v1/dags/" + airflowDagId + "/dagRuns";
            String auth = Base64.getEncoder().encodeToString(
                    (airflowApiUsername + ":" + airflowApiPassword).getBytes(StandardCharsets.UTF_8));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT_CONEXAO_HTTP)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + auth)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return true;
            }
            log.warn("Airflow retornou HTTP {} ao disparar DAG {} para conector #{}.", resp.statusCode(), airflowDagId, conectorId);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Disparo Airflow interrompido para conector #{}.", conectorId);
            return false;
        } catch (Exception e) {
            log.warn("Erro ao acionar API REST do Airflow para conector #{}: {}",
                    conectorId, sanitizarErro(e.getMessage(), credencialAberta));
            return false;
        }
    }

    private void executarFallbackLocal(
            ConectorInterno conector,
            Long ctrlId,
            Map<String, Object> paramsOverride,
            String segredo,
            Long usuarioId) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("conector_local_" + conector.id() + "_");
            atualizarEtapaControle(ctrlId, "BAIXANDO",
                    "Conectando em " + conector.tipoProtocolo() + " (" + conector.urlBase() + ") via fallback local");

            if ("GOOGLE_DRIVE".equals(conector.tipoProtocolo())) {
                throw new IllegalStateException("Execução de conector GOOGLE_DRIVE requer o worker Airflow ativo.");
            }

            Map<String, Object> mergedParams = new LinkedHashMap<>();
            if (conector.parametrosJson() != null) {
                mergedParams.putAll(conector.parametrosJson());
            }
            if (paramsOverride != null) {
                mergedParams.putAll(paramsOverride);
            }

            String urlDownload = montarUrlDownload(conector, mergedParams, paramsOverride);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder().GET().timeout(TIMEOUT_DOWNLOAD_HTTP);
            String urlFinal = aplicarAutenticacaoHttp(
                    conector.tipoAutenticacao(), conector.nomeCredencial(), segredo, urlDownload, reqBuilder);
            reqBuilder.uri(URI.create(urlFinal));

            HttpResponse<InputStream> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("Download falhou com HTTP " + resp.statusCode());
            }

            String extPadrao = resolverExtensaoPadrao(conector.formatoDados(), conector.tipoProtocolo());
            String nomeArquivo = resolverNomeArquivo(resp, conector, mergedParams, extPadrao);
            Path arquivoBaixado = tempDir.resolve(nomeArquivo);

            MessageDigest md5 = MessageDigest.getInstance("MD5");
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            long tamanhoBytes = gravarComHashes(resp.body(), arquivoBaixado, md5, sha256);

            String md5Hex = HexFormat.of().formatHex(md5.digest());
            String shaHex = HexFormat.of().formatHex(sha256.digest());
            validarPayloadBaixado(arquivoBaixado, conector.formatoDados(), extPadrao);

            jdbcTemplate.update("""
                    UPDATE ingestao_drive_controle
                       SET md5_drive = ?, hash_sha256 = ?, tamanho_bytes = ?, nome_arquivo = ?
                     WHERE id = ?
                    """, md5Hex, shaHex, tamanhoBytes, nomeArquivo, ctrlId);

            atualizarEtapaControle(ctrlId, "PROCESSANDO_POSTGIS",
                    "Processando " + nomeArquivo + " no PostGIS");

            if ("REFERENCIA".equals(conector.destino())) {
                int inseridas = ingerirReferenciaLocal(conector, arquivoBaixado);
                String msgOk = limitar(
                        conector.fonteReferencia() + ": " + inseridas + " feições carregadas (fallback local).", 4000);
                finalizarControleSucesso(ctrlId, conector.id(), shaHex, null, inseridas, msgOk);
            } else {
                ResultadoCargaLocal resCarga = ingerirCargaLocal(
                        conector, arquivoBaixado, nomeArquivo, shaHex, tamanhoBytes, usuarioId);
                finalizarControleSucesso(ctrlId, conector.id(), shaHex, resCarga.processoId(),
                        resCarga.linhasCarregadas(), limitar(resCarga.mensagem(), 4000));
            }
        } catch (Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String errMsg = sanitizarErro(t.getClass().getSimpleName() + ": " + t.getMessage(), segredo);
            log.error("Falha no fallback local do conector #{} (ctrl_id={}): {}", conector.id(), ctrlId, errMsg);
            finalizarControleFalha(ctrlId, conector.id(), errMsg);
        } finally {
            limparDiretorio(tempDir);
        }
    }

    private int ingerirReferenciaLocal(ConectorInterno conector, Path arquivoBaixado) throws IOException {
        Path geoJson = geoJsonArquivoService.preparar(arquivoBaixado);
        List<String> colunasId = resolverColunasIdentificador(conector.colunaIdentificador());
        String nomeCamada = conector.nomeCamadaExibicao() != null && !conector.nomeCamadaExibicao().isBlank()
                ? conector.nomeCamadaExibicao()
                : conector.fonteReferencia();
        int srid = resolverSridFallback(geoJson, conector.epsgOrigem());
        String sqlInsert = srid == 4674
                ? """
                  INSERT INTO camada_referencia (fonte, nome_camada, identificador_origem, geom, atributos, data_referencia)
                  VALUES (?, ?, ?, ST_Force2D(ST_SetSRID(ST_GeomFromGeoJSON(?), 4674)), ?::jsonb, CURRENT_DATE)
                  """
                : """
                  INSERT INTO camada_referencia (fonte, nome_camada, identificador_origem, geom, atributos, data_referencia)
                  VALUES (?, ?, ?, ST_Force2D(ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(?), %d), 4674)), ?::jsonb, CURRENT_DATE)
                  """.formatted(srid);

        List<Object[]> lote = new ArrayList<>();
        int inseridas = 0;
        int posicao = 0;
        boolean deletouAntigos = false;

        try (JsonParser parser = objectMapper.getFactory().createParser(geoJson.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("GeoJSON inválido gerado para " + conector.fonteReferencia());
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String campo = parser.currentName();
                JsonToken valor = parser.nextToken();
                if (!"features".equals(campo) || valor != JsonToken.START_ARRAY) {
                    parser.skipChildren();
                    continue;
                }
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    JsonNode feicao = objectMapper.readTree(parser);
                    posicao++;
                    JsonNode geom = feicao.get("geometry");
                    if (geom == null || !geom.isObject() || geom.isEmpty()) {
                        continue;
                    }
                    String identificador = extrairIdentificador(feicao, colunasId, posicao);
                    JsonNode props = feicao.get("properties");
                    lote.add(new Object[] {
                            conector.fonteReferencia(),
                            nomeCamada,
                            identificador,
                            geom.toString(),
                            props != null && props.isObject() ? props.toString() : null
                    });
                    if (lote.size() >= LOTE_REFERENCIA_MAX) {
                        if (!deletouAntigos) {
                            jdbcTemplate.update("DELETE FROM camada_referencia WHERE fonte = ?", conector.fonteReferencia());
                            deletouAntigos = true;
                        }
                        jdbcTemplate.batchUpdate(sqlInsert, lote);
                        inseridas += lote.size();
                        lote.clear();
                    }
                }
            }
        } finally {
            geoJsonArquivoService.excluirTemporario(geoJson, arquivoBaixado);
        }

        if (!lote.isEmpty()) {
            if (!deletouAntigos) {
                jdbcTemplate.update("DELETE FROM camada_referencia WHERE fonte = ?", conector.fonteReferencia());
            }
            jdbcTemplate.batchUpdate(sqlInsert, lote);
            inseridas += lote.size();
        }
        if (inseridas == 0) {
            throw new IllegalStateException("Nenhuma feição com geometria encontrada para " + conector.fonteReferencia());
        }
        return inseridas;
    }

    private record ResultadoCargaLocal(Long processoId, long linhasCarregadas, String mensagem) {}

    private ResultadoCargaLocal ingerirCargaLocal(
            ConectorInterno conector,
            Path arquivoBaixado,
            String nomeArquivo,
            String sha256,
            long tamanhoBytes,
            Long usuarioId) throws IOException {
        List<Long> existentes = jdbcTemplate.queryForList(
                "SELECT processo_id FROM arquivo_original WHERE hash_sha256 = ? LIMIT 1",
                Long.class, sha256);
        if (!existentes.isEmpty()) {
            Long existenteId = existentes.get(0);
            return new ResultadoCargaLocal(existenteId, 0L,
                    "ARQUIVO_JA_EXISTE: hash já registrado no processo " + existenteId);
        }

        Long operadorId = resolverOperadorCarga(usuarioId);
        Long orgaoId = conector.orgaoId() != null
                ? conector.orgaoId()
                : buscarPrimeiroIdAtivo("SELECT id FROM orgao WHERE ativo = TRUE ORDER BY id LIMIT 1", "órgão ativo");
        Long conjuntoId = conector.conjuntoId() != null
                ? conector.conjuntoId()
                : buscarPrimeiroIdAtivo("SELECT id FROM conjunto WHERE ativo = TRUE ORDER BY id LIMIT 1", "conjunto ativo");

        Path storageDir = Paths.get("zona_bruta_storage");
        Files.createDirectories(storageDir);
        Path caminhoCofre = storageDir.resolve(System.currentTimeMillis() + "_" + nomeArquivo);
        Files.copy(arquivoBaixado, caminhoCofre, StandardCopyOption.REPLACE_EXISTING);

        Processo processo = new Processo();
        processo.setAnoSafra(String.valueOf(Year.now().getValue()));
        processo.setEpsgOrigem(conector.epsgOrigem());
        processo.setOrgaoId(orgaoId);
        processo.setConjuntoId(conjuntoId);
        processo.setOperadorId(operadorId);
        processo.setFormato(conector.formatoDados());
        processo.setDataCriacao(LocalDateTime.now());
        processo.setDataInicio(LocalDateTime.now());
        Processo salvo = processoRepository.saveAndFlush(processo);

        processoEstadoService.inicializarEstado(
                salvo, "INGESTAO", "EM_ANDAMENTO",
                "Carga iniciada via Conector #" + conector.id() + " (" + conector.nome() + ").");

        ArquivoOriginal original = new ArquivoOriginal();
        original.setProcesso(salvo);
        original.setUsuarioUploadId(operadorId);
        original.setNomeOriginal(nomeArquivo);
        original.setExtensao(extrairExtensaoSemPonto(nomeArquivo));
        original.setTipoMime(resolverMimePorFormato(conector.formatoDados()));
        original.setTamanhoBytes(tamanhoBytes);
        original.setUrlArmazenamento(caminhoCofre.toString());
        original.setHashSha256(sha256);
        original.setImutavel(true);
        arquivoOriginalRepository.saveAndFlush(original);

        processoService.processarArquivo(salvo, nomeArquivo, caminhoCofre, operadorId);

        List<Long> counts = jdbcTemplate.queryForList("""
                SELECT COUNT(*)
                  FROM feicao_carga fc
                  JOIN arquivo_processado ap ON ap.id = fc.arquivo_processado_id
                 WHERE ap.processo_id = ?
                """, Long.class, salvo.getId());
        long carregadas = counts.isEmpty() || counts.get(0) == null ? 0L : counts.get(0);

        return new ResultadoCargaLocal(salvo.getId(), carregadas,
                "Processo #" + salvo.getId() + " processado com " + carregadas + " feições via fallback local.");
    }

    // ------------------------------------------------------------------
    // Quarentena e Auditoria pós-processamento
    // ------------------------------------------------------------------

    private Map<String, Object> enviarParaQuarentena(
            Processo processo,
            Long copiaId,
            int totalFeicoes,
            Long usuarioId,
            String acaoAuditoria,
            String motivo,
            List<String> detalhes,
            String codigoErro,
            String detalheTecnico) {
        String mensagem = motivo
                + " Carga encaminhada para a Quarentena na etapa de Validação, aguardando parecer do Gestor.";

        processoEstadoService.atualizarEstado(processo, "VALIDACAO", "EM_VALIDACAO", mensagem);

        int limite = 10;
        String amostra = String.join(" ", detalhes.subList(0, Math.min(limite, detalhes.size())))
                + (detalhes.size() > limite ? " (+" + (detalhes.size() - limite) + " outros)" : "");

        auditoriaLogService.registrarLog(
                processo,
                "WARNING",
                mensagem + (amostra.isBlank() ? "" : " Detalhes: " + amostra),
                codigoErro,
                detalheTecnico);

        Long usuarioAuditoria = usuarioId != null ? usuarioId : processo.getOperadorId();
        String textoAuditoria = "Processo ID: " + processo.getId() + "; " + motivo;
        if (usuarioAuditoria != null) {
            auditoriaLogService.registrarAuditoria(usuarioAuditoria, acaoAuditoria, textoAuditoria);
        } else {
            jdbcTemplate.update("""
                    INSERT INTO auditoria (usuario_id, processo_id, acao, descricao, detalhes, data_hora)
                    VALUES (NULL, ?, ?, ?, ?, now())
                    """, processo.getId(), acaoAuditoria, motivo, textoAuditoria);
        }

        notificacaoService.notificarAuditores(
                processo,
                "Carga do processo " + processo.getId() + " está em Quarentena na etapa de Validação: " + motivo);

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("processoId", processo.getId());
        resultado.put("arquivoProcessadoId", copiaId);
        resultado.put("totalFeicoes", totalFeicoes);
        resultado.put("quarentena", true);
        resultado.put("etapa", "VALIDACAO");
        resultado.put("situacao", "EM_VALIDACAO");
        resultado.put("motivo", motivo);
        return resultado;
    }

    // ------------------------------------------------------------------
    // Validação, Cron e Helpers
    // ------------------------------------------------------------------

    private record DadosNormalizados(
            String nome,
            String descricao,
            String tipoProtocolo,
            String urlBase,
            String destino,
            String fonteReferencia,
            String nomeCamadaExibicao,
            String formatoDados,
            String epsgOrigem,
            String colunaIdentificador,
            Map<String, Object> parametrosJson,
            String tipoAutenticacao,
            String nomeCredencial,
            String periodicidade,
            String expressaoCron,
            boolean ativo) {
    }

    private DadosNormalizados validarENormalizar(ConectorFonteDadosRequest req) {
        String protocolo = normalizarMaiusculo(req.tipoProtocolo(), "tipoProtocolo");
        if (!PROTOCOLOS_VALIDOS.contains(protocolo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tipoProtocolo inválido: " + protocolo);
        }

        String destino = normalizarMaiusculo(req.destino(), "destino");
        if (!DESTINOS_VALIDOS.contains(destino)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "destino inválido: " + destino);
        }

        String fonteRef = trimOrNull(req.fonteReferencia());
        if ("REFERENCIA".equals(destino) && fonteRef == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "fonteReferencia é obrigatória quando destino = REFERENCIA.");
        }

        String formato = normalizarMaiusculo(req.formatoDados(), "formatoDados");
        if (!FORMATOS_VALIDOS.contains(formato)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "formatoDados inválido: " + formato);
        }

        String tipoAuth = normalizarMaiusculo(req.tipoAutenticacao(), "tipoAutenticacao");
        if (!AUTH_VALIDAS.contains(tipoAuth)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tipoAutenticacao inválido: " + tipoAuth);
        }

        String periodicidade = normalizarMaiusculo(req.periodicidade(), "periodicidade");
        if (!PERIODICIDADES_VALIDAS.contains(periodicidade)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "periodicidade inválida: " + periodicidade);
        }

        String cronNormalizado = validarENormalizarCron(periodicidade, req.expressaoCron());
        boolean ativo = req.ativo() == null || req.ativo();

        return new DadosNormalizados(
                req.nome().trim(),
                trimOrNull(req.descricao()),
                protocolo,
                req.urlBase().trim(),
                destino,
                fonteRef,
                trimOrNull(req.nomeCamadaExibicao()),
                formato,
                req.epsgOrigem().trim(),
                req.colunaIdentificador().trim(),
                req.parametrosJson() != null ? req.parametrosJson() : Map.of(),
                tipoAuth,
                trimOrNull(req.nomeCredencial()),
                periodicidade,
                cronNormalizado,
                ativo);
    }

    private String validarENormalizarCron(String periodicidade, String expressaoCron) {
        if ("MANUAL".equals(periodicidade)) {
            return null;
        }
        String cronResolvido = switch (periodicidade) {
            case "HORARIA" -> "0 0 * * * *";
            case "DIARIA" -> "0 0 2 * * *";
            case "SEMANAL" -> "0 0 3 * * MON";
            case "MENSAL" -> "0 0 4 1 * *";
            case "CRON" -> normalizarCincoOuSeisCamposCron(expressaoCron);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Periodicidade inválida: " + periodicidade);
        };

        try {
            CronExpression.parse(cronResolvido);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expressão cron inválida: " + e.getMessage(), e);
        }
        return cronResolvido;
    }

    private String normalizarCincoOuSeisCamposCron(String expressaoCron) {
        if (expressaoCron == null || expressaoCron.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "expressaoCron é obrigatória para periodicidade CRON.");
        }
        String trimmed = expressaoCron.trim();
        String[] campos = trimmed.split("\\s+");
        if (campos.length == 5) {
            return "0 " + trimmed;
        }
        if (campos.length == 6) {
            return trimmed;
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Expressão cron deve possuir 5 ou 6 campos.");
    }

    private LocalDateTime calcularProximaExecucao(String periodicidade, String expressaoCron, boolean ativo) {
        if (!ativo || "MANUAL".equalsIgnoreCase(periodicidade)) {
            return null;
        }
        String cron = validarENormalizarCron(periodicidade.toUpperCase(Locale.ROOT), expressaoCron);
        return CronExpression.parse(cron).next(LocalDateTime.now());
    }

    private Map<String, Object> validarConfigGoogleDrive(ConectorInterno conector, String segredo, long inicioNs) {
        String url = conector.urlBase() == null ? "" : conector.urlBase().trim();
        String idDrive = extrairDriveId(url, conector.parametrosJson());
        if (url.isBlank() || idDrive.isBlank() || idDrive.contains("SEU_FOLDER_ID")) {
            return resultadoTeste(false, null,
                    "Informe uma URL ou ID de pasta/arquivo válido do Google Drive.", decorridoMs(inicioNs));
        }

        if ("SERVICE_ACCOUNT_JSON".equals(conector.tipoAutenticacao())) {
            if (segredo == null || segredo.isBlank()) {
                return resultadoTeste(false, null,
                        "Credencial SERVICE_ACCOUNT_JSON não configurada.", decorridoMs(inicioNs));
            }
            try {
                JsonNode json = objectMapper.readTree(segredo);
                if (!json.isObject() || json.path("client_email").asText("").isBlank()) {
                    return resultadoTeste(false, null,
                            "JSON da Service Account inválido (client_email ausente).", decorridoMs(inicioNs));
                }
            } catch (JsonProcessingException e) {
                return resultadoTeste(false, null,
                        "Credencial SERVICE_ACCOUNT_JSON não é um JSON válido.", decorridoMs(inicioNs));
            }
        }
        return resultadoTeste(true, null,
                "Configuração do Google Drive validada (ID: " + idDrive + ").", decorridoMs(inicioNs));
    }

    private String montarUrlTeste(ConectorInterno conector) {
        String base = conector.urlBase().trim();
        return switch (conector.tipoProtocolo()) {
            case "OGC_WFS" -> {
                String version = strParam(conector.parametrosJson(), "version", "1.0.0");
                String u = anexarQueryParam(base, "service", "WFS");
                u = anexarQueryParam(u, "request", "GetCapabilities");
                yield anexarQueryParam(u, "version", version);
            }
            case "ARCGIS_REST" -> {
                String limpa = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
                if (!limpa.endsWith("/query")) {
                    limpa = limpa + "/query";
                }
                String u = anexarQueryParam(limpa, "f", "json");
                u = anexarQueryParam(u, "returnCountOnly", "true");
                yield anexarQueryParam(u, "where", "1=1");
            }
            default -> base;
        };
    }

    private String montarUrlDownload(
            ConectorInterno conector,
            Map<String, Object> params,
            Map<String, Object> override) {
        String base = conector.urlBase().trim();
        return switch (conector.tipoProtocolo()) {
            case "OGC_WFS" -> {
                String version = strParam(params, "version", "1.0.0");
                String typeName = strParam(params, "typeName", strParam(params, "typeNames", strParam(params, "layer", "")));
                String uf = strParam(params, "uf", "");
                boolean overrideType = override != null && (override.containsKey("typeName") || override.containsKey("typeNames"));
                if (!uf.isBlank() && typeName.contains("sicar_imoveis_") && !overrideType) {
                    typeName = typeName.replaceAll("sicar_imoveis_[a-z]{2}$", "sicar_imoveis_" + uf.toLowerCase(Locale.ROOT));
                }

                String u = anexarQueryParam(base, "service", "WFS");
                u = anexarQueryParam(u, "version", version);
                u = anexarQueryParam(u, "request", "GetFeature");
                String outFmt = strParam(params, "outputFormat", wfsOutputFormat(conector.formatoDados()));
                u = anexarQueryParam(u, "outputFormat", outFmt);
                u = anexarQueryParam(u, "srsName", strParam(params, "srsName", "EPSG:" + conector.epsgOrigem()));
                if (!typeName.isBlank()) {
                    u = anexarQueryParam(u, version.startsWith("2.") ? "typeNames" : "typeName", typeName);
                }
                Object maxFeat = params.containsKey("maxFeatures") ? params.get("maxFeatures") : params.get("count");
                if (maxFeat != null) {
                    u = anexarQueryParam(u, version.startsWith("2.") ? "count" : "maxFeatures", String.valueOf(maxFeat));
                }
                if (params.get("bbox") != null) {
                    u = anexarQueryParam(u, "bbox", String.valueOf(params.get("bbox")));
                }
                String cql = construirCqlFilter(params);
                if (cql != null) {
                    u = anexarQueryParam(u, "CQL_FILTER", cql);
                }
                yield u;
            }
            case "ARCGIS_REST" -> {
                String limpa = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
                if (!limpa.endsWith("/query")) {
                    limpa = limpa + "/query";
                }
                Map<String, Object> q = new LinkedHashMap<>(params);
                q.putIfAbsent("f", "geojson");
                q.putIfAbsent("where", "1=1");
                q.putIfAbsent("outFields", "*");
                q.putIfAbsent("outSR", conector.epsgOrigem());
                String u = limpa;
                for (Map.Entry<String, Object> e : q.entrySet()) {
                    if (e.getValue() != null) {
                        u = anexarQueryParam(u, e.getKey(), String.valueOf(e.getValue()));
                    }
                }
                yield u;
            }
            default -> {
                String u = base;
                for (Map.Entry<String, Object> e : params.entrySet()) {
                    if (e.getValue() != null && !"nome_arquivo".equals(e.getKey())) {
                        u = anexarQueryParam(u, e.getKey(), String.valueOf(e.getValue()));
                    }
                }
                yield u;
            }
        };
    }

    private String aplicarAutenticacaoHttp(
            String tipoAuth,
            String nomeCredencial,
            String segredo,
            String url,
            HttpRequest.Builder builder) {
        if ("NONE".equals(tipoAuth)) {
            return url;
        }
        if (segredo == null || segredo.isBlank()) {
            throw new IllegalStateException("Credencial obrigatória para autenticação " + tipoAuth);
        }
        return switch (tipoAuth) {
            case "API_KEY_HEADER" -> {
                String headerName = nomeCredencial != null && !nomeCredencial.isBlank() ? nomeCredencial : "X-API-Key";
                builder.header(headerName, segredo);
                yield url;
            }
            case "API_KEY_QUERY" -> {
                String encoded = URLEncoder.encode(segredo, StandardCharsets.UTF_8);
                if (url.contains("{API_KEY}")) {
                    yield url.replace("{API_KEY}", encoded);
                }
                String paramName = nomeCredencial != null && !nomeCredencial.isBlank() ? nomeCredencial : "api_key";
                yield anexarQueryParam(url, paramName, segredo);
            }
            case "BEARER_TOKEN" -> {
                builder.header("Authorization", "Bearer " + segredo);
                yield url;
            }
            case "BASIC_AUTH" -> {
                String encoded = Base64.getEncoder().encodeToString(segredo.getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + encoded);
                yield url;
            }
            default -> throw new IllegalStateException("Autenticação HTTP não suportada: " + tipoAuth);
        };
    }

    private static String anexarQueryParam(String url, String chave, String valor) {
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + URLEncoder.encode(chave, StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(valor, StandardCharsets.UTF_8);
    }

    private static String construirCqlFilter(Map<String, Object> params) {
        String cqlRaw = strParam(params, "cqlFilter", "");
        if (!cqlRaw.isBlank()) {
            return cqlRaw;
        }
        List<String> partes = new ArrayList<>();
        String municipio = strParam(params, "municipio", "");
        if (!municipio.isBlank()) {
            partes.add("strToUpperCase(municipio) LIKE '%" + municipio.toUpperCase(Locale.ROOT).replace("'", "''") + "%'");
        }
        String codIbge = strParam(params, "cod_municipio_ibge", "");
        if (!codIbge.isBlank()) {
            partes.add(codIbge.matches("\\d+")
                    ? "cod_municipio_ibge = " + codIbge
                    : "cod_municipio_ibge = '" + codIbge.replace("'", "''") + "'");
        }
        for (String campo : List.of("cod_imovel", "status_imovel", "tipo_imovel")) {
            String val = strParam(params, campo, "");
            if (!val.isBlank()) {
                partes.add(campo + " = '" + val.replace("'", "''") + "'");
            }
        }
        return partes.isEmpty() ? null : String.join(" AND ", partes);
    }

    private static String wfsOutputFormat(String formato) {
        return switch (formato.toUpperCase(Locale.ROOT)) {
            case "GEOJSON" -> "application/json";
            case "GPKG" -> "application/geopackage+sqlite3";
            case "CSV" -> "csv";
            default -> "SHAPE-ZIP";
        };
    }

    private static String extrairDriveId(String url, Map<String, Object> params) {
        if (params != null) {
            if (params.get("drive_file_id") != null) {
                return String.valueOf(params.get("drive_file_id"));
            }
            if (params.get("file_id") != null) {
                return String.valueOf(params.get("file_id"));
            }
            if (params.get("folder_id") != null) {
                return String.valueOf(params.get("folder_id"));
            }
        }
        Matcher mFolder = DRIVE_FOLDER_PATTERN.matcher(url);
        if (mFolder.find()) {
            return mFolder.group(1);
        }
        Matcher mFile = DRIVE_FILE_PATTERN.matcher(url);
        if (mFile.find()) {
            return mFile.group(1);
        }
        Matcher mQuery = DRIVE_QUERY_ID_PATTERN.matcher(url);
        if (mQuery.find()) {
            return mQuery.group(1);
        }
        return "";
    }

    private long gravarComHashes(InputStream in, Path destino, MessageDigest md5, MessageDigest sha256) throws IOException {
        long total = 0L;
        byte[] buffer = new byte[65536];
        try (InputStream input = in; OutputStream out = Files.newOutputStream(destino)) {
            int lidos;
            while ((lidos = input.read(buffer)) != -1) {
                out.write(buffer, 0, lidos);
                md5.update(buffer, 0, lidos);
                sha256.update(buffer, 0, lidos);
                total += lidos;
            }
        }
        return total;
    }

    private void validarPayloadBaixado(Path arquivo, String formatoDados, String extPadrao) throws IOException {
        String fmt = formatoDados == null ? "" : formatoDados.toUpperCase(Locale.ROOT);
        if ("SHAPE-ZIP".equals(fmt) || ".zip".equals(extPadrao)) {
            try (ZipFile zip = new ZipFile(arquivo.toFile())) {
                if (zip.size() == 0) {
                    throw new IllegalStateException("Arquivo ZIP baixado está vazio.");
                }
            } catch (IOException e) {
                throw new IllegalStateException("Resposta recebida não é um arquivo ZIP válido.", e);
            }
        } else if ("GEOJSON".equals(fmt) || ".geojson".equals(extPadrao)) {
            String inicio = Files.readString(arquivo, StandardCharsets.UTF_8).stripLeading();
            if (!inicio.startsWith("{") && !inicio.startsWith("[")) {
                throw new IllegalStateException("Resposta recebida não é um GeoJSON válido.");
            }
        }
    }

    private int resolverSridFallback(Path geoJson, String epsgOrigem) {
        try {
            String detectado = geoJsonEpsgService.detectar(geoJson);
            if (detectado != null && detectado.matches("\\d+")) {
                return Integer.parseInt(detectado);
            }
        } catch (Exception ignored) {
        }
        if (epsgOrigem != null && epsgOrigem.matches("\\d+")) {
            return Integer.parseInt(epsgOrigem);
        }
        return 4674;
    }

    private List<String> resolverColunasIdentificador(String colunaIdentificador) {
        if (colunaIdentificador == null || colunaIdentificador.isBlank()) {
            return List.of("cod_imovel", "ogc_fid");
        }
        List<String> colunas = new ArrayList<>();
        for (String parte : colunaIdentificador.split(",")) {
            String limpa = parte.trim().toLowerCase(Locale.ROOT);
            if (!limpa.isEmpty()) {
                colunas.add(limpa);
            }
        }
        return colunas.isEmpty() ? List.of("cod_imovel", "ogc_fid") : colunas;
    }

    private String extrairIdentificador(JsonNode feicao, List<String> colunasPrioritarias, int posicao) {
        JsonNode props = feicao.get("properties");
        if (props != null && props.isObject()) {
            for (String alvo : colunasPrioritarias) {
                Iterator<String> campos = props.fieldNames();
                while (campos.hasNext()) {
                    String campo = campos.next();
                    if (alvo.equals(campo.toLowerCase(Locale.ROOT))) {
                        String val = props.get(campo).asText("").trim();
                        if (!val.isEmpty()) {
                            return val.length() > TAMANHO_MAX_CODIGO ? val.substring(0, TAMANHO_MAX_CODIGO) : val;
                        }
                    }
                }
            }
        }
        return "FEICAO_" + posicao;
    }

    private String resolverNomeArquivo(
            HttpResponse<?> resp,
            ConectorInterno conector,
            Map<String, Object> params,
            String extPadrao) {
        String cd = resp.headers().firstValue("Content-Disposition").orElse("");
        Matcher m = CONTENT_DISP_FILENAME.matcher(cd);
        if (m.find()) {
            String base = Paths.get(m.group(1).trim()).getFileName().toString();
            if (!base.isBlank()) {
                return base;
            }
        }
        String custom = strParam(params, "nome_arquivo", "");
        if (!custom.isBlank()) {
            return Paths.get(custom).getFileName().toString();
        }
        String slug = conector.nome().replaceAll("[^A-Za-z0-9_.-]+", "_").replaceAll("^_+|_+$", "");
        return (slug.isBlank() ? "conector_" + conector.id() : slug) + extPadrao;
    }

    private static String resolverExtensaoPadrao(String formatoDados, String protocolo) {
        if (formatoDados != null) {
            return switch (formatoDados.toUpperCase(Locale.ROOT)) {
                case "GEOJSON" -> ".geojson";
                case "CSV" -> ".csv";
                case "GPKG" -> ".gpkg";
                default -> ".zip";
            };
        }
        return "ARCGIS_REST".equals(protocolo) ? ".geojson" : ".zip";
    }

    private static String resolverMimePorFormato(String formatoDados) {
        if (formatoDados == null) {
            return "application/zip";
        }
        return switch (formatoDados.toUpperCase(Locale.ROOT)) {
            case "GEOJSON" -> "application/geo+json";
            case "CSV" -> "text/csv";
            case "GPKG" -> "application/geopackage+sqlite3";
            default -> "application/zip";
        };
    }

    private static String extrairExtensaoSemPonto(String nomeArquivo) {
        int idx = nomeArquivo.lastIndexOf('.');
        if (idx >= 0 && idx < nomeArquivo.length() - 1) {
            return nomeArquivo.substring(idx + 1).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private void atualizarEtapaControle(Long ctrlId, String tag, String mensagem) {
        String msg = limitar("[" + tag + "] " + mensagem, 4000);
        jdbcTemplate.update("UPDATE ingestao_drive_controle SET mensagem = ? WHERE id = ?", msg, ctrlId);
    }

    private void finalizarControleSucesso(
            Long ctrlId,
            Long conectorId,
            String sha256,
            Long processoId,
            long linhasCarregadas,
            String mensagem) {
        jdbcTemplate.update("""
                UPDATE ingestao_drive_controle
                   SET status = 'CARREGADO',
                       hash_sha256 = ?,
                       processo_id = ?,
                       linhas_carregadas = ?,
                       linhas_descartadas = 0,
                       mensagem = ?,
                       data_fim = now()
                 WHERE id = ?
                """, sha256, processoId, linhasCarregadas, mensagem, ctrlId);

        jdbcTemplate.update("""
                UPDATE conector_fonte_dados
                   SET ultimo_status = 'CARREGADO',
                       ultima_execucao_em = now(),
                       data_atualizacao = now()
                 WHERE id = ?
                """, conectorId);
    }

    private void finalizarControleFalha(Long ctrlId, Long conectorId, String mensagem) {
        jdbcTemplate.update("""
                UPDATE ingestao_drive_controle
                   SET status = ?,
                       mensagem = ?,
                       data_fim = now()
                 WHERE id = ?
                """, "FALHOU", limitar(mensagem, 4000), ctrlId);

        jdbcTemplate.update("""
                UPDATE conector_fonte_dados
                   SET ultimo_status = 'FALHOU',
                       ultima_execucao_em = now(),
                       data_atualizacao = now()
                 WHERE id = ?
                """, conectorId);
    }

    private Long resolverOperadorCarga(Long usuarioId) {
        List<Long> airflowUser = jdbcTemplate.queryForList(
                "SELECT id FROM usuario WHERE email = 'airflow@georural.com.br' AND ativo = TRUE LIMIT 1",
                Long.class);
        if (!airflowUser.isEmpty()) {
            return airflowUser.get(0);
        }
        if (usuarioId != null) {
            Long existe = resolverUsuarioExistente(usuarioId);
            if (existe != null) {
                return existe;
            }
        }
        return buscarPrimeiroIdAtivo("SELECT id FROM usuario WHERE ativo = TRUE ORDER BY id LIMIT 1", "usuário ativo");
    }

    private Long buscarPrimeiroIdAtivo(String sql, String rotulo) {
        List<Long> ids = jdbcTemplate.queryForList(sql, Long.class);
        if (ids.isEmpty()) {
            throw new IllegalStateException("Nenhum registro encontrado para " + rotulo);
        }
        return ids.get(0);
    }

    private Long resolverUsuarioExistente(Long usuarioId) {
        if (usuarioId == null) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject("SELECT id FROM usuario WHERE id = ?", Long.class, usuarioId);
        } catch (Exception e) {
            return null;
        }
    }

    private ConectorInterno buscarConectorInterno(Long id) {
        List<ConectorInterno> lista = jdbcTemplate.query(
                SELECT_CONECTOR_INTERNO,
                (rs, i) -> mapearConectorInterno(rs),
                id);
        if (lista.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conector não encontrado para o ID: " + id);
        }
        return lista.get(0);
    }

    private AirflowIngestaoStatusResponse buscarExecucaoPorId(Long ctrlId) {
        List<AirflowIngestaoStatusResponse> lista = jdbcTemplate.query(
                SELECT_EXECUCOES + " WHERE c.id = ?",
                (rs, i) -> mapearExecucao(rs),
                ctrlId);
        if (lista.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Execução não encontrada para o ID: " + ctrlId);
        }
        return lista.get(0);
    }

    private ConectorFonteDadosResponse mapearConectorPublico(ResultSet rs) throws SQLException {
        return new ConectorFonteDadosResponse(
                rs.getLong("id"),
                rs.getString("nome"),
                rs.getString("descricao"),
                rs.getString("tipo_protocolo"),
                rs.getString("url_base"),
                rs.getString("destino"),
                rs.getString("fonte_referencia"),
                rs.getString("nome_camada_exibicao"),
                getLongNullable(rs, "orgao_id"),
                rs.getString("orgao_sigla"),
                getLongNullable(rs, "conjunto_id"),
                rs.getString("conjunto_nome"),
                rs.getString("formato_dados"),
                rs.getString("epsg_origem"),
                rs.getString("coluna_identificador"),
                desserializarJson(rs.getString("parametros_json")),
                rs.getString("tipo_autenticacao"),
                rs.getString("nome_credencial"),
                rs.getString("prefixo_credencial"),
                rs.getBoolean("possui_credencial"),
                rs.getString("periodicidade"),
                rs.getString("expressao_cron"),
                rs.getBoolean("ativo"),
                paraLocalDateTime(rs.getTimestamp("ultima_execucao_em")),
                paraLocalDateTime(rs.getTimestamp("proxima_execucao_em")),
                rs.getString("ultimo_status"),
                getLongNullable(rs, "criado_por_id"),
                paraLocalDateTime(rs.getTimestamp("data_criacao")),
                paraLocalDateTime(rs.getTimestamp("data_atualizacao"))
        );
    }

    private ConectorInterno mapearConectorInterno(ResultSet rs) throws SQLException {
        return new ConectorInterno(
                rs.getLong("id"),
                rs.getString("nome"),
                rs.getString("descricao"),
                rs.getString("tipo_protocolo"),
                rs.getString("url_base"),
                rs.getString("destino"),
                rs.getString("fonte_referencia"),
                rs.getString("nome_camada_exibicao"),
                getLongNullable(rs, "orgao_id"),
                getLongNullable(rs, "conjunto_id"),
                rs.getString("formato_dados"),
                rs.getString("epsg_origem"),
                rs.getString("coluna_identificador"),
                desserializarJson(rs.getString("parametros_json")),
                rs.getString("tipo_autenticacao"),
                rs.getString("nome_credencial"),
                rs.getString("prefixo_credencial"),
                rs.getString("credencial_cifrada"),
                rs.getString("periodicidade"),
                rs.getString("expressao_cron"),
                rs.getBoolean("ativo")
        );
    }

    private AirflowIngestaoStatusResponse mapearExecucao(ResultSet rs) throws SQLException {
        String statusBanco = rs.getString("status_banco");
        String mensagem = rs.getString("mensagem");
        return new AirflowIngestaoStatusResponse(
                rs.getLong("id"),
                getLongNullable(rs, "conector_id"),
                rs.getString("conector_nome"),
                rs.getString("dag_run_id"),
                rs.getString("nome_arquivo"),
                rs.getString("drive_caminho"),
                rs.getString("destino"),
                rs.getString("fonte"),
                statusBanco,
                resolverEtapaUi(statusBanco, mensagem),
                getLongNullable(rs, "processo_id"),
                rs.getString("etapa_processo"),
                rs.getString("situacao_processo"),
                getLongNullable(rs, "linhas_carregadas"),
                getLongNullable(rs, "linhas_descartadas"),
                getLongNullable(rs, "tamanho_bytes"),
                rs.getString("hash_sha256"),
                rs.getString("md5_drive"),
                mensagem,
                paraLocalDateTime(rs.getTimestamp("data_inicio")),
                paraLocalDateTime(rs.getTimestamp("data_fim")),
                getLongNullable(rs, "duracao_segundos")
        );
    }

    private String serializarJson(Map<String, Object> mapa) {
        try {
            return objectMapper.writeValueAsString(mapa == null ? Map.of() : mapa);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Não foi possível serializar parametrosJson.", e);
        }
    }

    private Map<String, Object> desserializarJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private static Map<String, Object> template(
            String codigo,
            String nome,
            String descricao,
            String tipoProtocolo,
            String urlBase,
            String destino,
            String fonteReferencia,
            String nomeCamadaExibicao,
            String formatoDados,
            String epsgOrigem,
            String colunaIdentificador,
            Map<String, Object> parametrosJson,
            String tipoAutenticacao,
            String nomeCredencial) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("codigo", codigo);
        item.put("nome", nome);
        item.put("descricao", descricao);
        item.put("tipoProtocolo", tipoProtocolo);
        item.put("urlBase", urlBase);
        item.put("destino", destino);
        item.put("fonteReferencia", fonteReferencia);
        item.put("nomeCamadaExibicao", nomeCamadaExibicao);
        item.put("formatoDados", formatoDados);
        item.put("epsgOrigem", epsgOrigem);
        item.put("colunaIdentificador", colunaIdentificador);
        item.put("parametrosJson", parametrosJson);
        item.put("tipoAutenticacao", tipoAutenticacao);
        item.put("nomeCredencial", nomeCredencial);
        item.put("periodicidade", "MANUAL");
        return item;
    }

    private static Map<String, Object> resultadoTeste(
            boolean sucesso,
            Integer statusHttp,
            String mensagem,
            long tempoMs) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("sucesso", sucesso);
        res.put("statusHttp", statusHttp);
        res.put("mensagem", mensagem);
        res.put("tempoMs", tempoMs);
        return res;
    }

    private static String resolverFonteControle(ConectorInterno conector) {
        if (conector.fonteReferencia() != null && !conector.fonteReferencia().isBlank()) {
            return limitar(conector.fonteReferencia(), 60);
        }
        return limitar(conector.nome(), 60);
    }

    private static String sanitizarErro(String texto, String segredo) {
        String base = texto == null ? "erro desconhecido" : texto;
        if (segredo != null && !segredo.isBlank()) {
            base = base.replace(segredo, "****");
            String encoded = URLEncoder.encode(segredo, StandardCharsets.UTF_8);
            base = base.replace(encoded, "****");
        }
        return base;
    }

    private static void limparDiretorio(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static long decorridoMs(long inicioNs) {
        return Math.max(0L, (System.nanoTime() - inicioNs) / 1_000_000L);
    }

    private static String normalizarMaiusculo(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, campo + " é obrigatório.");
        }
        return valor.trim().toUpperCase(Locale.ROOT);
    }

    private static String trimOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static String strParam(Map<String, Object> params, String key, String defaultVal) {
        if (params == null) {
            return defaultVal;
        }
        Object v = params.get(key);
        if (v == null) {
            return defaultVal;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? defaultVal : s;
    }

    private static String limitar(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static Long getLongNullable(ResultSet rs, String col) throws SQLException {
        long val = rs.getLong(col);
        return rs.wasNull() ? null : val;
    }

    private static Timestamp paraTimestamp(LocalDateTime dt) {
        return dt == null ? null : Timestamp.valueOf(dt);
    }

    private static LocalDateTime paraLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
