package com.bifrostconnect.api_geo.integration;

import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ProcessoDashboardRepository;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
public class MapaIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ProcessoRepository processoRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProcessoDashboardRepository processoDashboardRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("US08 - Tarefa 13: Deve retornar o GeoJSON de revisão com resumo e feições da carga")
    @WithMockUser(roles = "AUDITOR")
    void deveRetornarRevisaoPublicacaoComSucesso() throws Exception {
        Processo p = Processo.builder()
                .anoSafra("2026")
                .orgaoId(1L)
                .operadorId(1L)
                .conjuntoId(1L)
                .epsgOrigem("4326")
                .epsgDestino("31983")
                .build();
        p = processoRepository.saveAndFlush(p);

        Long usuarioId = jdbcTemplate.queryForObject("SELECT id FROM usuario LIMIT 1", Long.class);
        String hashUnico = UUID.randomUUID().toString().replace("-", "");

        Long arquivoOriginalId = jdbcTemplate.queryForObject(
            "INSERT INTO arquivo_original (processo_id, usuario_upload_id, nome_original, url_armazenamento, hash_sha256) VALUES (?, ?, 'original.zip', '/path/to/file', ?) RETURNING id",
            Long.class,
            p.getId(),
            usuarioId,
            hashUnico
        );

        Long etapaId = jdbcTemplate.queryForObject("SELECT id FROM etapa LIMIT 1", Long.class);

        Long arquivoProcessadoId = jdbcTemplate.queryForObject(
            "INSERT INTO arquivo_processado (processo_id, arquivo_original_id, etapa_geracao_id, nome_arquivo, url_armazenamento, versao) VALUES (?, ?, ?, 'processado.geojson', '/path/processed', 1) RETURNING id",
            Long.class,
            p.getId(),
            arquivoOriginalId,
            etapaId
        );

        jdbcTemplate.update(
            "INSERT INTO feicao_carga (arquivo_processado_id, codigo_imovel, geom) VALUES (?, 'IMOVEL_001', ST_GeomFromText('POLYGON((0 0, 0 1, 1 1, 1 0, 0 0))', 4674))",
            arquivoProcessadoId
        );

        mockMvc.perform(get("/publicacao/" + p.getId() + "/revisao")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumoAuditoria").exists())
                .andExpect(jsonPath("$.feicoesGeoJson.type").value("FeatureCollection"))
                .andExpect(jsonPath("$.feicoesGeoJson.features").isArray());
    }

    @Test
    @DisplayName("US08 - Tarefa 14: Deve listar camadas em GeoJSON aplicando filtros e Bounding Box")
    @WithMockUser(roles = "OPERADOR")
    void deveListarCamadasMapaGeoJsonComFiltros() throws Exception {
        Processo p = Processo.builder()
                .anoSafra("2026")
                .orgaoId(1L)
                .operadorId(1L)
                .conjuntoId(1L)
                .epsgOrigem("4326")
                .epsgDestino("31983")
                .build();
        p = processoRepository.saveAndFlush(p);

        Long usuarioId = jdbcTemplate.queryForObject("SELECT id FROM usuario LIMIT 1", Long.class);
        Long etapaId = jdbcTemplate.queryForObject("SELECT id FROM etapa LIMIT 1", Long.class);
        String hashUnico2 = UUID.randomUUID().toString().replace("-", "");

        Long arquivoOriginalId = jdbcTemplate.queryForObject(
            "INSERT INTO arquivo_original (processo_id, usuario_upload_id, nome_original, url_armazenamento, hash_sha256) VALUES (?, ?, 'original2.zip', '/path/to/file2', ?) RETURNING id",
            Long.class,
            p.getId(),
            usuarioId,
            hashUnico2
        );

        Long arquivoProcessadoId = jdbcTemplate.queryForObject(
            "INSERT INTO arquivo_processado (processo_id, arquivo_original_id, etapa_geracao_id, nome_arquivo, url_armazenamento, versao) VALUES (?, ?, ?, 'processado2.geojson', '/path/processed2', 1) RETURNING id",
            Long.class,
            p.getId(),
            arquivoOriginalId,
            etapaId
        );

        jdbcTemplate.update(
            "INSERT INTO feicao_carga (arquivo_processado_id, codigo_imovel, geom) VALUES (?, 'IMOVEL_002', ST_GeomFromText('POLYGON((0 0, 0 2, 2 2, 2 0, 0 0))', 4674))",
            arquivoProcessadoId
        );

        mockMvc.perform(get("/mapa/camadas")
                .param("conjuntoId", "1")
                .param("anoSafra", "2026")
                .param("bbox", "-1,-1,3,3")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("FeatureCollection"))
                .andExpect(jsonPath("$.features").isArray());
    }

    @Test
    @DisplayName("Deve filtrar estritamente /mapa/camadas por baseId e camadaId e aceitar size=500")
    @WithMockUser(roles = "ANALISTA")
    void deveFiltrarCamadasEstritamentePorBaseIdECamadaIdESuportarSize500() throws Exception {
        Long gestorId = garantirUsuarioAtivo();

        Long baseA = criarProcesso(1L, 1L, "2026", "PUBLICACAO", "CONCLUIDA");
        Long apA = criarArquivoProcessado(baseA, gestorId, 1);
        inserirFeicao(apA, "IMOVEL_BASE_A_1", "POLYGON((-49.1 -16.1, -49.1 -16.2, -49.2 -16.2, -49.2 -16.1, -49.1 -16.1))");
        inserirFeicao(apA, "IMOVEL_BASE_A_2", "POLYGON((-49.3 -16.1, -49.3 -16.2, -49.4 -16.2, -49.4 -16.1, -49.3 -16.1))");
        fecharVersao(baseA, gestorId);

        // Processo publicado com versao_fechada = TRUE e etapa/situacao nulos
        Long baseB = criarProcesso(1L, 1L, "2026", null, null);
        Long apB = criarArquivoProcessado(baseB, gestorId, 1);
        inserirFeicao(apB, "IMOVEL_BASE_B_1", "POLYGON((-49.5 -16.3, -49.5 -16.4, -49.6 -16.4, -49.6 -16.3, -49.5 -16.3))");
        fecharVersao(baseB, gestorId);

        // Terceira base publicada (com etapa/situacao preenchidas) no mesmo bbox para provar isolamento por baseId
        Long baseC = criarProcesso(1L, 1L, "2026", "PUBLICACAO", "CONCLUIDA");
        Long apC = criarArquivoProcessado(baseC, gestorId, 1);
        inserirFeicao(apC, "IMOVEL_BASE_C_1", "POLYGON((-49.4 -16.3, -49.4 -16.4, -49.5 -16.4, -49.5 -16.3, -49.4 -16.3))");
        fecharVersao(baseC, gestorId);

        Long naoPublicada = criarProcesso(1L, 1L, "2026", "TRATAMENTO", "EM_ANDAMENTO");
        Long apNaoPub = criarArquivoProcessado(naoPublicada, gestorId, 1);
        inserirFeicao(apNaoPub, "IMOVEL_NAO_PUB", "POLYGON((-49.7 -16.3, -49.7 -16.4, -49.8 -16.4, -49.8 -16.3, -49.7 -16.3))");

        String bbox = "-50,-17,-48,-15";

        mockMvc.perform(get("/mapa/camadas")
                .param("baseId", baseA.toString())
                .param("bbox", bbox)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("FeatureCollection"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.features", hasSize(2)))
                .andExpect(jsonPath("$.features[?(@.properties.processoId == " + baseA + ")]", hasSize(2)))
                .andExpect(jsonPath("$.features[?(@.properties.processoId == " + baseB + ")]").isEmpty())
                .andExpect(jsonPath("$.features[?(@.properties.processoId == " + baseC + ")]").isEmpty())
                .andExpect(jsonPath("$.features[?(@.properties.processoId == " + naoPublicada + ")]").isEmpty());

        mockMvc.perform(get("/mapa/camadas")
                .param("camadaId", baseB.toString())
                .param("size", "500")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(500))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.features", hasSize(1)))
                .andExpect(jsonPath("$.features[0].properties.processoId").value(baseB))
                .andExpect(jsonPath("$.features[0].properties.codigoImovel").value("IMOVEL_BASE_B_1"))
                .andExpect(jsonPath("$.features[?(@.properties.processoId == " + baseA + ")]").isEmpty())
                .andExpect(jsonPath("$.features[?(@.properties.processoId == " + baseC + ")]").isEmpty());

        mockMvc.perform(get("/mapa/camadas-geojson")
                .param("baseId", baseA.toString())
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.features[?(@.properties.processoId == " + baseB + ")]").isEmpty())
                .andExpect(jsonPath("$.features[?(@.properties.processoId == " + baseC + ")]").isEmpty());
    }

    @Test
    @DisplayName("Deve listar bases publicadas em /mapa/camadas-legado usando base_localizacao_cache com centroid e total_feicoes")
    @WithMockUser(roles = "ANALISTA")
    void deveListarLocaisBasesPublicadasAPartirDoCacheComCentroidETotalFeicoes() throws Exception {
        Long gestorId = garantirUsuarioAtivo();

        // Base 1: v1 com 1 feicao antiga, v2 (vigente) com 2 feicoes formando bbox [-49.4, -49.0] x [-16.2, -16.0]
        Long base1 = criarProcesso(1L, 1L, "2026", "PUBLICACAO", "CONCLUIDA");
        Long base1V1 = criarArquivoProcessado(base1, gestorId, 1);
        inserirFeicao(base1V1, "OLD_V1", "POLYGON((-55.0 -10.0, -55.0 -10.1, -55.1 -10.1, -55.1 -10.0, -55.0 -10.0))");
        Long base1V2 = criarArquivoProcessado(base1, gestorId, 2);
        inserirFeicao(base1V2, "B1_F1", "POLYGON((-49.0 -16.0, -49.0 -16.2, -49.2 -16.2, -49.2 -16.0, -49.0 -16.0))");
        inserirFeicao(base1V2, "B1_F2", "POLYGON((-49.2 -16.0, -49.2 -16.2, -49.4 -16.2, -49.4 -16.0, -49.2 -16.0))");
        fecharVersao(base1, gestorId);

        // Base 2: versao_fechada = TRUE sem etapa/situacao, 1 feicao em [-46.2, -46.0] x [-20.2, -20.0]
        Long base2 = criarProcesso(1L, 1L, "2025", null, null);
        Long base2V1 = criarArquivoProcessado(base2, gestorId, 1);
        inserirFeicao(base2V1, "B2_F1", "POLYGON((-46.0 -20.0, -46.0 -20.2, -46.2 -20.2, -46.2 -20.0, -46.0 -20.0))");
        fecharVersao(base2, gestorId);

        // Base 3: publicada sem feicoes -> fallback padrao (-15.78, -47.93, 0)
        Long baseVazia = criarProcesso(1L, 1L, "2024", "PUBLICACAO", "CONCLUIDA");
        fecharVersao(baseVazia, gestorId);

        // Base nao publicada: nao deve entrar no cache nem no endpoint
        Long naoPublicada = criarProcesso(1L, 1L, "2026", "TRATAMENTO", "EM_ANDAMENTO");
        Long naoPubV1 = criarArquivoProcessado(naoPublicada, gestorId, 1);
        inserirFeicao(naoPubV1, "NP_F1", "POLYGON((-48.0 -18.0, -48.0 -18.1, -48.1 -18.1, -48.1 -18.0, -48.0 -18.0))");

        Map<String, Object> cacheBase1 = jdbcTemplate.queryForMap(
                "SELECT latitude, longitude, total_feicoes FROM base_localizacao_cache WHERE processo_id = ?",
                base1);
        assertThat(((Number) cacheBase1.get("total_feicoes")).longValue()).isEqualTo(2L);
        assertThat(((Number) cacheBase1.get("latitude")).doubleValue()).isCloseTo(-16.1, within(0.01));
        assertThat(((Number) cacheBase1.get("longitude")).doubleValue()).isCloseTo(-49.2, within(0.01));

        Map<String, Object> cacheBase2 = jdbcTemplate.queryForMap(
                "SELECT latitude, longitude, total_feicoes FROM base_localizacao_cache WHERE processo_id = ?",
                base2);
        assertThat(((Number) cacheBase2.get("total_feicoes")).longValue()).isEqualTo(1L);
        assertThat(((Number) cacheBase2.get("latitude")).doubleValue()).isCloseTo(-20.1, within(0.01));
        assertThat(((Number) cacheBase2.get("longitude")).doubleValue()).isCloseTo(-46.1, within(0.01));

        Integer countNaoPublicadaNoCache = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM base_localizacao_cache WHERE processo_id = ?",
                Integer.class,
                naoPublicada);
        assertThat(countNaoPublicadaNoCache).isZero();

        MvcResult result = mockMvc.perform(get("/mapa/camadas-legado")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        List<Map<String, Object>> bases = objectMapper.readValue(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                new TypeReference<>() {});

        Map<String, Object> itemBase1 = encontrarBasePorId(bases, base1);
        Map<String, Object> itemBase2 = encontrarBasePorId(bases, base2);
        Map<String, Object> itemBaseVazia = encontrarBasePorId(bases, baseVazia);

        assertThat(itemBase1).isNotNull();
        assertThat(itemBase1.get("name")).isNotNull();
        assertThat(itemBase1.get("type")).isNotNull();
        assertThat(itemBase1.get("status")).isEqualTo("Concluída");
        assertThat(((Number) itemBase1.get("total_feicoes")).longValue()).isEqualTo(2L);
        assertThat(((Number) itemBase1.get("latitude")).doubleValue()).isCloseTo(-16.1, within(0.01));
        assertThat(((Number) itemBase1.get("longitude")).doubleValue()).isCloseTo(-49.2, within(0.01));

        assertThat(itemBase2).isNotNull();
        assertThat(itemBase2.get("status")).isEqualTo("Concluída");
        assertThat(((Number) itemBase2.get("total_feicoes")).longValue()).isEqualTo(1L);
        assertThat(((Number) itemBase2.get("latitude")).doubleValue()).isCloseTo(-20.1, within(0.01));
        assertThat(((Number) itemBase2.get("longitude")).doubleValue()).isCloseTo(-46.1, within(0.01));

        assertThat(itemBaseVazia).isNotNull();
        assertThat(((Number) itemBaseVazia.get("total_feicoes")).longValue()).isEqualTo(0L);
        assertThat(((Number) itemBaseVazia.get("latitude")).doubleValue()).isCloseTo(-15.78, within(0.01));
        assertThat(((Number) itemBaseVazia.get("longitude")).doubleValue()).isCloseTo(-47.93, within(0.01));

        assertThat(encontrarBasePorId(bases, naoPublicada)).isNull();
    }

    @Test
    @DisplayName("Deve atualizar base_localizacao_cache via triggers statement-level em feicao_carga, suportar fallback e calcular bounding box")
    @WithMockUser(roles = "ANALISTA")
    void deveAtualizarCacheEmFeicaoCargaSuportarFallbackEBoundingBox() throws Exception {
        Long gestorId = garantirUsuarioAtivo();

        Long processoId = criarProcesso(1L, 1L, "2026", "PUBLICACAO", "CONCLUIDA");
        Long arquivoProcessadoId = criarArquivoProcessado(processoId, gestorId, 1);

        // Processo ja em PUBLICACAO/CONCLUIDA com 0 feicoes deve existir no cache
        Long totalInicial = jdbcTemplate.queryForObject(
                "SELECT total_feicoes FROM base_localizacao_cache WHERE processo_id = ?",
                Long.class, processoId);
        assertThat(totalInicial).isEqualTo(0L);

        // Insercao de 2 feicoes no mesmo statement dispara trigger AFTER INSERT
        jdbcTemplate.update("""
                INSERT INTO feicao_carga (arquivo_processado_id, codigo_imovel, geom)
                VALUES
                    (?, 'TRIG_1', ST_GeomFromText('POLYGON((-51.0 -22.0, -51.0 -22.2, -51.2 -22.2, -51.2 -22.0, -51.0 -22.0))', 4674)),
                    (?, 'TRIG_2', ST_GeomFromText('POLYGON((-51.2 -22.0, -51.2 -22.2, -51.4 -22.2, -51.4 -22.0, -51.2 -22.0))', 4674))
                """, arquivoProcessadoId, arquivoProcessadoId);

        Map<String, Object> cacheAposInsert = jdbcTemplate.queryForMap(
                "SELECT latitude, longitude, total_feicoes FROM base_localizacao_cache WHERE processo_id = ?",
                processoId);
        assertThat(((Number) cacheAposInsert.get("total_feicoes")).longValue()).isEqualTo(2L);
        assertThat(((Number) cacheAposInsert.get("latitude")).doubleValue()).isCloseTo(-22.1, within(0.01));
        assertThat(((Number) cacheAposInsert.get("longitude")).doubleValue()).isCloseTo(-51.2, within(0.01));

        // Delecao de 1 feicao dispara trigger AFTER DELETE
        jdbcTemplate.update(
                "DELETE FROM feicao_carga WHERE arquivo_processado_id = ? AND codigo_imovel = 'TRIG_2'",
                arquivoProcessadoId);

        Map<String, Object> cacheAposDelete = jdbcTemplate.queryForMap(
                "SELECT latitude, longitude, total_feicoes FROM base_localizacao_cache WHERE processo_id = ?",
                processoId);
        assertThat(((Number) cacheAposDelete.get("total_feicoes")).longValue()).isEqualTo(1L);
        assertThat(((Number) cacheAposDelete.get("latitude")).doubleValue()).isCloseTo(-22.1, within(0.01));
        assertThat(((Number) cacheAposDelete.get("longitude")).doubleValue()).isCloseTo(-51.1, within(0.01));

        // Remove linha do cache para testar fallback do servico
        jdbcTemplate.update("DELETE FROM base_localizacao_cache WHERE processo_id = ?", processoId);

        MvcResult result = mockMvc.perform(get("/mapa/camadas-legado")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        List<Map<String, Object>> bases = objectMapper.readValue(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                new TypeReference<>() {});

        Map<String, Object> itemFallback = encontrarBasePorId(bases, processoId);
        assertThat(itemFallback).isNotNull();
        assertThat(((Number) itemFallback.get("total_feicoes")).longValue()).isEqualTo(1L);
        assertThat(((Number) itemFallback.get("latitude")).doubleValue()).isCloseTo(-22.1, within(0.01));
        assertThat(((Number) itemFallback.get("longitude")).doubleValue()).isCloseTo(-51.1, within(0.01));

        // Valida buscarBoundingBox diretamente no target bean (evita erro pre-existente em buscarOcorrenciasValidacao abortar a transacao)
        ProcessoDashboardRepository dashboardRepoTarget = AopTestUtils.getTargetObject(processoDashboardRepository);
        @SuppressWarnings("unchecked")
        List<List<Double>> mapCoordinates = (List<List<Double>>) ReflectionTestUtils.invokeMethod(
                dashboardRepoTarget, "buscarBoundingBox", processoId);
        assertThat(mapCoordinates).hasSize(4);
        assertThat(mapCoordinates.get(0).get(0)).isCloseTo(-22.2, within(0.01));
        assertThat(mapCoordinates.get(0).get(1)).isCloseTo(-51.2, within(0.01));
        assertThat(mapCoordinates.get(2).get(0)).isCloseTo(-22.0, within(0.01));
        assertThat(mapCoordinates.get(2).get(1)).isCloseTo(-51.0, within(0.01));

        Long processoSemFeicoes = criarProcesso(1L, 1L, "2026", "TRATAMENTO", "EM_ANDAMENTO");
        @SuppressWarnings("unchecked")
        List<List<Double>> bboxVazio = (List<List<Double>>) ReflectionTestUtils.invokeMethod(
                dashboardRepoTarget, "buscarBoundingBox", processoSemFeicoes);
        assertThat(bboxVazio).isEmpty();
    }

    private Long garantirUsuarioAtivo() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM usuario WHERE ativo = TRUE ORDER BY id LIMIT 1",
                Long.class);
    }

    private Long criarProcesso(Long conjuntoId, Long orgaoId, String anoSafra, String etapaNome, String situacaoNome) {
        Long etapaId = etapaNome == null ? null : jdbcTemplate.queryForObject(
                "SELECT id FROM etapa WHERE nome = ?", Long.class, etapaNome);
        Long situacaoId = situacaoNome == null ? null : jdbcTemplate.queryForObject(
                "SELECT id FROM situacao WHERE nome = ?", Long.class, situacaoNome);
        Long usuarioId = garantirUsuarioAtivo();

        return jdbcTemplate.queryForObject("""
                INSERT INTO processo (operador_id, conjunto_id, orgao_id, etapa_atual_id, situacao_atual_id, ano_safra, epsg_origem)
                VALUES (?, ?, ?, ?, ?, ?, '4674')
                RETURNING id
                """, Long.class, usuarioId, conjuntoId, orgaoId, etapaId, situacaoId, anoSafra);
    }

    private Long criarArquivoProcessado(Long processoId, Long usuarioId, int versao) {
        Long arquivoOriginalId = jdbcTemplate.queryForObject("""
                INSERT INTO arquivo_original (processo_id, usuario_upload_id, nome_original, url_armazenamento, hash_sha256)
                VALUES (?, ?, 'carga.zip', '/tmp/carga.zip', ?)
                RETURNING id
                """, Long.class, processoId, usuarioId, UUID.randomUUID().toString().replace("-", ""));

        Long etapaId = jdbcTemplate.queryForObject("SELECT id FROM etapa WHERE nome = 'TRATAMENTO'", Long.class);

        return jdbcTemplate.queryForObject("""
                INSERT INTO arquivo_processado (processo_id, arquivo_original_id, etapa_geracao_id, nome_arquivo, url_armazenamento, versao)
                VALUES (?, ?, ?, 'tratado.geojson', '/tmp/tratado.geojson', ?)
                RETURNING id
                """, Long.class, processoId, arquivoOriginalId, etapaId, versao);
    }

    private void inserirFeicao(Long arquivoProcessadoId, String codigoImovel, String wkt) {
        jdbcTemplate.update(
                "INSERT INTO feicao_carga (arquivo_processado_id, codigo_imovel, geom) VALUES (?, ?, ST_GeomFromText(?, 4674))",
                arquivoProcessadoId, codigoImovel, wkt);
    }

    private void fecharVersao(Long processoId, Long gestorId) {
        jdbcTemplate.update("""
                INSERT INTO historico_aprovacoes_gestor (processo_id, gestor_id, etapa_avaliada, decisao)
                VALUES (?, ?, 'PUBLICACAO', 'APROVADO')
                """, processoId, gestorId);
        jdbcTemplate.update("""
                UPDATE processo
                   SET versao_oficial = 'v1', data_publicacao = now(), gestor_publicacao_id = ?, versao_fechada = TRUE
                 WHERE id = ?
                """, gestorId, processoId);
    }

    private Map<String, Object> encontrarBasePorId(List<Map<String, Object>> bases, Long id) {
        String idTexto = id.toString();
        return bases.stream()
                .filter(b -> idTexto.equals(String.valueOf(b.get("id"))))
                .findFirst()
                .orElse(null);
    }
}