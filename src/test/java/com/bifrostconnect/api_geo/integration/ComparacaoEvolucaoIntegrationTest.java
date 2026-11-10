package com.bifrostconnect.api_geo.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import com.bifrostconnect.api_geo.dto.EvolucaoResponseDTO;
import com.bifrostconnect.api_geo.service.ComparacaoService;
import com.fasterxml.jackson.databind.ObjectMapper;

// US10 - Tarefas 17, 18 e 19: comparação de evolução entre duas safras no banco local.
// Roda em transação com rollback, igual o DadosPublicadosMapaIntegrationTest
@SpringBootTest
@Transactional
class ComparacaoEvolucaoIntegrationTest {

    // faixa fina de latitude perto de Brasília; dentro dela a área geodésica cresce
    // praticamente linear com a largura em longitude, então dá pra montar os
    // polígonos já no tamanho em hectares que o cenário pede
    private static final double LAT_SUL = -15.010;
    private static final double LAT_NORTE = -15.000;
    private static final double LON_INICIO = -47.000;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    private long gestorId;
    private long conjuntoId;
    private long orgaoId;
    // quantos graus de longitude dão 1 ha dentro da faixa
    private double grausPorHectare;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

        String s = UUID.randomUUID().toString().substring(0, 8);

        gestorId = jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, perfil_id, ativo) "
                        + "VALUES (?, ?, 'hash', (SELECT id FROM perfil WHERE nome = 'ADMINISTRADOR'), TRUE) "
                        + "RETURNING id",
                Long.class, "Gestor " + s, "gestor." + s + "@teste.local");

        conjuntoId = criarConjunto("Alertas " + s);
        orgaoId = jdbc.queryForObject("SELECT min(id) FROM orgao", Long.class);

        double metrosQuadrados = jdbc.queryForObject(
                "SELECT ST_Area(ST_MakeEnvelope(?, ?, ?, ?, 4674)::geography)",
                Double.class, LON_INICIO, LAT_SUL, LON_INICIO + 0.01, LAT_NORTE);

        grausPorHectare = 0.01 / (metrosQuadrados / 10000.0);
    }

    // ---------- Tarefa 19 ----------

    @Test
    @DisplayName("US10 - Tarefa 19: 2023 com 40 ha e 2025 com 70 ha (35 novos, 5 regenerados) dá +30 ha / +75%")
    void deveCalcularSaldoLiquidoAumentoEReducao() throws Exception {
        Cenario c = cenarioPadrao();

        mockMvc.perform(post("/analise/comparacao/evolucao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(c.safra2023(), c.safra2025(), c.regiao())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areaSafraAHectares").value(closeTo(40.0, 0.001)))
                .andExpect(jsonPath("$.areaSafraBHectares").value(closeTo(70.0, 0.001)))
                .andExpect(jsonPath("$.saldoVariacaoHectares").value(closeTo(30.0, 0.001)))
                .andExpect(jsonPath("$.percentualVariacao").value(closeTo(75.0, 0.01)))
                .andExpect(jsonPath("$.areaAumentoHectares").value(closeTo(35.0, 0.001)))
                .andExpect(jsonPath("$.areaReducaoHectares").value(closeTo(5.0, 0.001)))
                .andExpect(jsonPath("$.areaMantidaHectares").value(closeTo(35.0, 0.001)))
                .andExpect(jsonPath("$.simplificada").value(false))
                .andExpect(jsonPath("$.geoJsonClassificado", containsString("NOVO_ALERTA")))
                .andExpect(jsonPath("$.geoJsonClassificado", containsString("REDUCAO_ALERTA")))
                .andExpect(jsonPath("$.geoJsonClassificado", containsString("MANTIDO")));
    }

    @Test
    @DisplayName("US10 - Tarefa 19: sem região informada a conta é a mesma (janela = extensão das duas cargas)")
    void deveCalcularMesmoSaldoSemRegiao() {
        Cenario c = cenarioPadrao();

        EvolucaoResponseDTO r = servico(50_000, 500_000).calcularEvolucao(c.safra2023(), c.safra2025(), null, null);

        assertThat(r.saldoVariacaoHectares()).isCloseTo(30.0, within(0.001));
        assertThat(r.percentualVariacao()).isCloseTo(75.0, within(0.01));
    }

    // ---------- Tarefa 17 ----------

    @Test
    @DisplayName("US10 - Tarefa 17: mesma carga nos dois lados é 400")
    void mesmaCargaEh400() throws Exception {
        Cenario c = cenarioPadrao();

        mockMvc.perform(post("/analise/comparacao/evolucao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(c.safra2023(), c.safra2023(), c.regiao())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("US10 - Tarefa 17: conjuntos temáticos diferentes é 400")
    void conjuntosDiferentesEh400() throws Exception {
        Cenario c = cenarioPadrao();
        long outroConjunto = criarConjunto("Outro " + UUID.randomUUID().toString().substring(0, 8));
        long cargaOutroConjunto = criarCarga(outroConjunto, "2025", true, faixa("CAR-X", 5, 75));

        mockMvc.perform(post("/analise/comparacao/evolucao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(c.safra2023(), cargaOutroConjunto, c.regiao())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro", containsString("conjuntos temáticos diferentes")));
    }

    @Test
    @DisplayName("US10 - Tarefa 17: carga não publicada (versao_fechada = FALSE) é 403, na evolução e nas camadas")
    void cargaNaoPublicadaEh403() throws Exception {
        Cenario c = cenarioPadrao();
        long rascunho = criarCarga(conjuntoId, "2025", false, faixa("CAR-R", 5, 75));

        mockMvc.perform(post("/analise/comparacao/evolucao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(c.safra2023(), rascunho, c.regiao())))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/analise/comparacao/camadas")
                        .param("id_carga_a", String.valueOf(c.safra2023()))
                        .param("id_carga_b", String.valueOf(rascunho))
                        .param("id_regiao", String.valueOf(c.regiao())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("US10 - Tarefa 17: safras sem sobreposição geográfica na região é 422")
    void semSobreposicaoNaRegiaoEh422() throws Exception {
        Cenario c = cenarioPadrao();
        // mesma safra 2025, mas em outro lugar (~3° a oeste): fora da região do cenário
        long longe = criarCarga(conjuntoId, "2025", true,
                new Feicao("CAR-LONGE", envelope(-50.0, LAT_SUL, -49.99, LAT_NORTE)));

        mockMvc.perform(post("/analise/comparacao/evolucao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(c.safra2023(), longe, c.regiao())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.erro", containsString("sobreposição geográfica")));

        // sem região a janela pega as duas, mas as coberturas continuam sem se encostar
        mockMvc.perform(post("/analise/comparacao/evolucao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(c.safra2023(), longe, null)))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- Tarefa 18 ----------

    @Test
    @DisplayName("US10 - Tarefa 18: recorte acima do limite seguro é 413 orientando a escolher município/zoom")
    void recorteGrandeDemaisEh413() {
        Cenario c = cenarioPadrao();

        assertThatThrownBy(() -> servico(2, 5).calcularEvolucao(c.safra2023(), c.safra2025(), c.regiao(), null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                    assertThat(e.getReason()).contains("município").contains("zoom");
                });

        assertThatThrownBy(() -> servico(2, 5).compararCamadas(c.safra2023(), c.safra2025(), c.regiao(), null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @Test
    @DisplayName("US10 - Tarefa 18: acima do limite de simplificação o GeoJSON vem simplificado e as áreas não mudam")
    void recorteVolumosoVemSimplificadoSemMexerNasAreas() {
        Cenario c = cenarioPadrao();
        ComparacaoService service = servico(1, 500_000);

        EvolucaoResponseDTO r = service.calcularEvolucao(c.safra2023(), c.safra2025(), c.regiao(), null);

        assertThat(r.simplificada()).isTrue();
        assertThat(r.saldoVariacaoHectares()).isCloseTo(30.0, within(0.001));
        assertThat(r.areaAumentoHectares()).isCloseTo(35.0, within(0.001));
        assertThat(r.areaReducaoHectares()).isCloseTo(5.0, within(0.001));

        assertThat(service.compararCamadas(c.safra2023(), c.safra2025(), c.regiao(), null))
                .containsEntry("simplificada", true);
    }

    // ---------- cenário ----------

    private record Feicao(String codigo, String wkt) {}

    private record Cenario(long safra2023, long safra2025, long regiao) {}

    // Safra 2023: 40 ha em [0, 40] ha da faixa.
    // Safra 2025: [5, 40] (35 ha que continuam) + [40, 75] (35 ha novos) = 70 ha.
    // O pedaço [0, 5] some em 2025: são os 5 ha regenerados
    private Cenario cenarioPadrao() {
        long safra2023 = criarCarga(conjuntoId, "2023", true, faixa("CAR-001", 0, 40));
        long safra2025 = criarCarga(conjuntoId, "2025", true,
                faixa("CAR-001", 5, 40),
                faixa("CAR-002", 40, 75));

        long regiao = jdbc.queryForObject(
                "INSERT INTO camada_referencia (fonte, nome_camada, identificador_origem, geom) "
                        + "VALUES ('IBGE_MUNICIPIO', 'Município teste US10', 'T19', ST_GeomFromText(?, 4674)) RETURNING id",
                Long.class, envelope(LON_INICIO - 0.05, LAT_SUL - 0.05, LON_INICIO + 0.05, LAT_NORTE + 0.05));

        return new Cenario(safra2023, safra2025, regiao);
    }

    private Feicao faixa(String codigo, double haInicio, double haFim) {
        return new Feicao(codigo, envelope(
                LON_INICIO + haInicio * grausPorHectare, LAT_SUL,
                LON_INICIO + haFim * grausPorHectare, LAT_NORTE));
    }

    private static String envelope(double minLon, double minLat, double maxLon, double maxLat) {
        return String.format(Locale.ROOT,
                "POLYGON((%s %s, %s %s, %s %s, %s %s, %s %s))",
                minLon, minLat, maxLon, minLat, maxLon, maxLat, minLon, maxLat, minLon, minLat);
    }

    private long criarConjunto(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO conjunto (nome, ativo) VALUES (?, TRUE) RETURNING id", Long.class, nome);
    }

    private long criarCarga(long conjunto, String anoSafra, boolean publicada, Feicao... feicoes) {
        long processo = jdbc.queryForObject(
                "INSERT INTO processo (operador_id, conjunto_id, orgao_id, ano_safra, epsg_origem) "
                        + "VALUES (?, ?, ?, ?, '4674') RETURNING id",
                Long.class, gestorId, conjunto, orgaoId, anoSafra);

        long original = jdbc.queryForObject(
                "INSERT INTO arquivo_original (processo_id, usuario_upload_id, nome_original, url_armazenamento, hash_sha256) "
                        + "VALUES (?, ?, 'safra.zip', 'teste/safra.zip', ?) RETURNING id",
                Long.class, processo, gestorId, (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""));

        long copia = jdbc.queryForObject(
                "INSERT INTO arquivo_processado (arquivo_original_id, processo_id, etapa_geracao_id, "
                        + "nome_arquivo, url_armazenamento, versao) "
                        + "VALUES (?, ?, (SELECT id FROM etapa WHERE nome = 'TRATAMENTO'), 'tratado.geojson', 'teste/tratado.geojson', 1) "
                        + "RETURNING id",
                Long.class, original, processo);

        for (Feicao f : feicoes) {
            jdbc.update(
                    "INSERT INTO feicao_carga (arquivo_processado_id, codigo_imovel, geom) "
                            + "VALUES (?, ?, ST_GeomFromText(?, 4674))",
                    copia, f.codigo(), f.wkt());
        }

        if (publicada) {
            jdbc.update(
                    "INSERT INTO historico_aprovacoes_gestor (processo_id, gestor_id, etapa_avaliada, decisao) "
                            + "VALUES (?, ?, 'PUBLICACAO', 'APROVADO')",
                    processo, gestorId);
            jdbc.update(
                    "UPDATE processo SET versao_oficial = '1.0', versao_fechada = TRUE, data_publicacao = now(), "
                            + "gestor_publicacao_id = ? WHERE id = ?",
                    gestorId, processo);
        }

        return processo;
    }

    // instância com limites baixos pros testes da Tarefa 18; usa o mesmo JdbcTemplate,
    // então enxerga o que foi inserido nessa transação
    private ComparacaoService servico(long limiteSimplificacao, long limiteMaximo) {
        return new ComparacaoService(jdbc, objectMapper, limiteSimplificacao, limiteMaximo, 0.0001);
    }

    private String corpo(long safraA, long safraB, Long regiao) {
        return "{\"safraAId\": " + safraA + ", \"safraBId\": " + safraB
                + (regiao == null ? "" : ", \"idRegiao\": " + regiao) + "}";
    }
}
