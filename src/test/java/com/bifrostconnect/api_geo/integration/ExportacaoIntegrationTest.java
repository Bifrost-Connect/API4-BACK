package com.bifrostconnect.api_geo.integration;

import com.bifrostconnect.api_geo.service.ExportacaoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// US11 - Tarefas 12, 13 e 14: exportação em streaming, preview, histórico e auditoria.
// Roda contra o banco local (V36 a V38 e V43 aplicadas); tudo volta no rollback do @Transactional
@SpringBootTest
@Transactional
class ExportacaoIntegrationTest {

    private static final String POLIGONO = "POLYGON((-9.8 -9.8, -9.8 -9.2, -9.2 -9.2, -9.2 -9.8, -9.8 -9.8))";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    private Long conjuntoId;
    private Long gestorId;
    private String gestorEmail;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String sufixo = String.valueOf(ThreadLocalRandom.current().nextInt(100_000_000, 999_999_999));

        jdbcTemplate.update("INSERT INTO orgao (id, nome, sigla, ativo) VALUES (1, 'Órgão', 'OT', true) ON CONFLICT (id) DO NOTHING");
        conjuntoId = jdbcTemplate.queryForObject(
                "INSERT INTO conjunto (nome, ativo) VALUES (?, true) RETURNING id", Long.class, "Conjunto Exportação " + sufixo);

        gestorEmail = "gestor.exportacao." + sufixo + "@test.com";
        gestorId = jdbcTemplate.queryForObject("""
                INSERT INTO usuario (nome, email, senha_hash, perfil_id, ativo)
                VALUES ('Gestor Exportação', ?, 'hash', (SELECT id FROM perfil WHERE nome = 'GESTOR'), TRUE)
                RETURNING id
                """, Long.class, gestorEmail);
    }

    // ---------- Tarefa 12: streaming ----------

    @Test
    @DisplayName("Tarefa 12: GeoJSON sai como anexo, em vários lotes, com todas as feições e sem repetir nenhuma")
    void deveExportarGeoJsonEmLotes() throws Exception {
        // passa do tamanho do lote pra garantir que o cursor emenda um lote no outro
        int quantidade = ExportacaoService.TAMANHO_LOTE + 205;
        Long processo = criarVersaoPublicada(quantidade, 1);

        MvcResult resultado = mockMvc.perform(get("/exportacoes/resultados")
                        .param("processo_id", processo.toString())
                        .param("formato", "geojson")
                        .with(gestor()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment; filename=\"exportacao_processo_" + processo)))
                .andExpect(header().string("X-Total-Registros", String.valueOf(quantidade)))
                .andReturn();

        JsonNode geojson = objectMapper.readTree(resultado.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("FeatureCollection", geojson.get("type").asText());
        assertEquals(quantidade, geojson.get("features").size());
        assertEquals(quantidade, geojson.get("metadados").get("total_registros").asInt());

        long distintos = 0;
        long anterior = 0;
        for (JsonNode feature : geojson.get("features")) {
            long id = feature.get("properties").get("feicao_id").asLong();
            assertTrue(id > anterior, "feições fora de ordem ou repetidas");
            anterior = id;
            distintos++;
            assertEquals("Polygon", feature.get("geometry").get("type").asText());
        }
        assertEquals(quantidade, distintos);
    }

    @Test
    @DisplayName("Tarefa 12: CSV com BOM, separador ';' e filtro de sobreposição")
    void deveExportarCsvFiltrado() throws Exception {
        Long processo = criarVersaoPublicada(5, 2);

        MvcResult resultado = mockMvc.perform(get("/exportacoes/resultados")
                        .param("processo_id", processo.toString())
                        .param("formato", "csv")
                        .param("com_sobreposicao", "true")
                        .with(gestor()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("X-Total-Registros", "2"))
                .andReturn();

        byte[] bytes = resultado.getResponse().getContentAsByteArray();
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);

        String[] linhas = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8).split("\r\n");
        assertTrue(linhas[0].startsWith("processo_id;feicao_id;codigo_imovel;"));
        assertEquals(3, linhas.length, "cabeçalho + 2 imóveis com sobreposição");
        assertTrue(linhas[1].contains("FUNAI_TERRA_INDIGENA"));
    }

    @Test
    @DisplayName("Tarefa 12: filtro por fonte da camada usa o JSON de sobreposições da view")
    void deveFiltrarPorFonteDaCamada() throws Exception {
        Long processo = criarVersaoPublicada(4, 1);

        mockMvc.perform(get("/exportacoes/resultados")
                        .param("processo_id", processo.toString())
                        .param("fonte_camada", "FUNAI_TERRA_INDIGENA")
                        .with(gestor()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Registros", "1"));

        mockMvc.perform(get("/exportacoes/resultados")
                        .param("processo_id", processo.toString())
                        .param("fonte_camada", "ICMBIO_UC")
                        .with(gestor()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Registros", "0"))
                .andExpect(jsonPath("$.features.length()").value(0));
    }

    @Test
    @DisplayName("Tarefa 12: /versoes/{id}/exportar e /indicadores/{id}/exportar são a mesma exportação")
    void rotasDaUs07EntregamOMesmoArquivo() throws Exception {
        Long processo = criarVersaoPublicada(3, 0);

        mockMvc.perform(get("/versoes/" + processo + "/exportar").with(gestor()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Registros", "3"))
                .andExpect(jsonPath("$.features.length()").value(3));

        mockMvc.perform(get("/indicadores/" + processo + "/exportar").param("formato", "csv").with(gestor()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Registros", "3"));

        assertEquals(2, contarLogs(processo));
    }

    @Test
    @DisplayName("Tarefa 12: versão não publicada não exporta e não deixa log")
    void naoExportaVersaoAberta() throws Exception {
        Long processo = criarProcesso();

        mockMvc.perform(get("/exportacoes/resultados").param("processo_id", processo.toString()).with(gestor()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro", containsString("publicada")));

        assertEquals(0, contarLogs(processo));
    }

    @Test
    @DisplayName("Tarefa 12: sem processo_id, formato inválido ou processo inexistente devolvem erro em JSON")
    void validaParametros() throws Exception {
        Long processo = criarVersaoPublicada(1, 0);

        mockMvc.perform(get("/exportacoes/resultados").with(gestor()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro", containsString("processo_id")));

        mockMvc.perform(get("/exportacoes/resultados")
                        .param("processo_id", processo.toString())
                        .param("formato", "xlsx")
                        .with(gestor()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro", containsString("Formato inválido")));

        mockMvc.perform(get("/exportacoes/resultados").param("processo_id", "999999999").with(gestor()))
                .andExpect(status().isNotFound());

        assertEquals(0, contarLogs(processo));
    }

    // ---------- Tarefa 14: auditoria ----------

    @Test
    @DisplayName("Tarefa 14: cada exportação grava no log_exportacoes_relatorios e no EXPORTACAO_GERADA da auditoria")
    void exportacaoFicaAuditada() throws Exception {
        Long processo = criarVersaoPublicada(4, 1);

        mockMvc.perform(get("/exportacoes/resultados")
                        .param("processo_id", processo.toString())
                        .param("formato", "csv")
                        .param("hectares_min", "1")
                        .with(gestor()))
                .andExpect(status().isOk());

        Map<String, Object> log = jdbcTemplate.queryForMap(
                "SELECT usuario_id, formato, total_registros_exportados, filtros_utilizados::text AS filtros "
                        + "FROM log_exportacoes_relatorios WHERE processo_id = ?", processo);
        assertEquals(gestorId, ((Number) log.get("usuario_id")).longValue());
        assertEquals("CSV", log.get("formato"));
        assertEquals(1, ((Number) log.get("total_registros_exportados")).intValue());
        assertTrue(log.get("filtros").toString().contains("hectares_min"));

        Integer auditoria = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auditoria WHERE acao = 'EXPORTACAO_GERADA' AND processo_id = ? AND usuario_id = ?",
                Integer.class, processo, gestorId);
        assertEquals(1, auditoria);
    }

    @Test
    @DisplayName("Tarefa 14: usuário de memória (sem linha em usuario) precisa informar o X-Gestor-ID")
    @WithMockUser(username = "gestor-de-memoria", roles = "GESTOR")
    void usuarioDeMemoriaUsaCabecalho() throws Exception {
        Long processo = criarVersaoPublicada(2, 0);

        mockMvc.perform(get("/exportacoes/resultados").param("processo_id", processo.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro", containsString("auditada")));

        mockMvc.perform(get("/exportacoes/resultados")
                        .param("processo_id", processo.toString())
                        .header("X-Gestor-ID", gestorId))
                .andExpect(status().isOk());

        assertEquals(1, contarLogs(processo));
    }

    // ---------- Tarefa 13: preview e histórico ----------

    @Test
    @DisplayName("Tarefa 13: preview traz a contagem e 10 linhas de amostra, sem geometria")
    void previewTrazContagemEAmostra() throws Exception {
        Long processo = criarVersaoPublicada(15, 3);

        mockMvc.perform(get("/exportacoes/preview").param("processo_id", processo.toString()).with(gestor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_registros").value(15))
                .andExpect(jsonPath("$.tamanho_amostra").value(10))
                .andExpect(jsonPath("$.amostra.length()").value(10))
                .andExpect(jsonPath("$.amostra[0].codigo_imovel").value("CAR-0001"))
                .andExpect(jsonPath("$.amostra[0].sobreposicoes[0].fonte_camada").value("FUNAI_TERRA_INDIGENA"))
                .andExpect(jsonPath("$.amostra[0].geometria_geojson").doesNotExist());

        mockMvc.perform(get("/exportacoes/preview")
                        .param("processo_id", processo.toString())
                        .param("com_sobreposicao", "true")
                        .with(gestor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_registros").value(3))
                .andExpect(jsonPath("$.amostra.length()").value(3));

        mockMvc.perform(get("/exportacoes/preview")
                        .param("processo_id", processo.toString())
                        .param("codigo_imovel", "CAR-0001")
                        .with(gestor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_registros").value(1))
                .andExpect(jsonPath("$.amostra.length()").value(1));

        jdbcTemplate.update("DELETE FROM base_localizacao_cache WHERE processo_id = ?", processo);
        mockMvc.perform(get("/exportacoes/preview").param("processo_id", processo.toString()).with(gestor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_registros").value(15))
                .andExpect(jsonPath("$.tamanho_amostra").value(10));

        // preview não é download: não entra no histórico
        assertEquals(0, contarLogs(processo));
    }

    @Test
    @DisplayName("Tarefa 13: histórico lista os downloads do mais recente pro mais antigo, com filtros")
    void historicoListaDownloads() throws Exception {
        Long processo = criarVersaoPublicada(2, 0);

        mockMvc.perform(get("/exportacoes/resultados").param("processo_id", processo.toString()).param("formato", "csv").with(gestor()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/exportacoes/resultados").param("processo_id", processo.toString()).with(gestor()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/exportacoes/historico").param("processo_id", processo.toString()).with(gestor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_items").value(2))
                .andExpect(jsonPath("$.items[0].formato").value("GEOJSON"))
                .andExpect(jsonPath("$.items[1].formato").value("CSV"))
                .andExpect(jsonPath("$.items[0].usuario_email").value(gestorEmail))
                .andExpect(jsonPath("$.items[0].total_registros_exportados").value(2))
                .andExpect(jsonPath("$.items[0].filtros_utilizados.processo_id").value(processo));

        mockMvc.perform(get("/exportacoes/historico")
                        .param("processo_id", processo.toString())
                        .param("formato", "csv")
                        .with(gestor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_items").value(1));

        mockMvc.perform(get("/exportacoes/historico").param("size", "500").with(gestor()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Tarefa 13: Analista exporta, mas não vê o histórico de todo mundo")
    @WithMockUser(username = "analista-de-memoria", roles = "ANALISTA")
    void analistaNaoVeHistorico() throws Exception {
        mockMvc.perform(get("/exportacoes/historico")).andExpect(status().isForbidden());
    }

    // ---------- apoio ----------

    private RequestPostProcessor gestor() {
        return user(gestorEmail).roles("GESTOR");
    }

    private int contarLogs(Long processo) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM log_exportacoes_relatorios WHERE processo_id = ?", Integer.class, processo);
    }

    private Long criarProcesso() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO processo (operador_id, conjunto_id, orgao_id, etapa_atual_id, situacao_atual_id, ano_safra, epsg_origem)
                VALUES (?, ?, 1,
                        (SELECT id FROM etapa WHERE nome = 'PUBLICACAO'),
                        (SELECT id FROM situacao WHERE nome = 'CONCLUIDA'),
                        '2026', '4674')
                RETURNING id
                """, Long.class, gestorId, conjuntoId);
    }

    // versão publicada com N imóveis (CAR-0001, CAR-0002...); os primeiros comSobreposicao imóveis
    // ganham uma sobreposição com a FUNAI de 12,5 ha
    private Long criarVersaoPublicada(int imoveis, int comSobreposicao) {
        Long processo = criarProcesso();

        Long arquivoOriginal = jdbcTemplate.queryForObject("""
                INSERT INTO arquivo_original (processo_id, usuario_upload_id, nome_original, url_armazenamento, hash_sha256)
                VALUES (?, ?, 'exportacao.geojson', '/tmp/exportacao.geojson', ?)
                RETURNING id
                """, Long.class, processo, gestorId, UUID.randomUUID().toString().replace("-", ""));

        Long arquivo = jdbcTemplate.queryForObject("""
                INSERT INTO arquivo_processado (processo_id, arquivo_original_id, etapa_geracao_id, nome_arquivo, url_armazenamento, versao)
                VALUES (?, ?, (SELECT id FROM etapa WHERE nome = 'TRATAMENTO'), 'exportacao_tratado.geojson', '/tmp/exportacao_tratado.geojson', 1)
                RETURNING id
                """, Long.class, processo, arquivoOriginal);

        jdbcTemplate.update("""
                INSERT INTO feicao_carga (arquivo_processado_id, codigo_imovel, geom)
                SELECT ?, 'CAR-' || lpad(n::text, 4, '0'), ST_GeomFromText(?, 4674)
                  FROM generate_series(1, ?) n
                """, arquivo, POLIGONO, imoveis);

        jdbcTemplate.update("""
                INSERT INTO resultados_calculos (processo_id, arquivo_processado_id, codigo_imovel,
                                                 fonte_camada, codigo_alerta_app, hectares_sobrepostos)
                SELECT ?, ?, 'CAR-' || lpad(n::text, 4, '0'), 'FUNAI_TERRA_INDIGENA', 'ALERTA-' || n, 12.5
                  FROM generate_series(1, ?) n
                """, processo, arquivo, comSobreposicao);

        jdbcTemplate.update("""
                INSERT INTO historico_aprovacoes_gestor (processo_id, gestor_id, etapa_avaliada, decisao)
                VALUES (?, ?, 'PUBLICACAO', 'APROVADO')
                """, processo, gestorId);
        jdbcTemplate.update("""
                UPDATE processo
                   SET versao_oficial = 'v1', data_publicacao = now(), gestor_publicacao_id = ?, versao_fechada = TRUE
                 WHERE id = ?
                """, gestorId, processo);

        Integer naView = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vw_relatorio_exportacao_oficial WHERE processo_id = ?", Integer.class, processo);
        assertEquals(imoveis, naView, "a view de exportação devia enxergar todos os imóveis da versão publicada");
        return processo;
    }
}
