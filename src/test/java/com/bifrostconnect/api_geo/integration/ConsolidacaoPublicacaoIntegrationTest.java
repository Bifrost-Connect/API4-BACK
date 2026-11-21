package com.bifrostconnect.api_geo.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 1. Removido o @Transactional da classe para evitar bloqueio de concorrência com o código da aplicação
@SpringBootTest
class ConsolidacaoPublicacaoIntegrationTest {

    private static final Path PASTA_PROCESSADA = Path.of("zona_processada_storage");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        jdbcTemplate.update("INSERT INTO orgao (id, nome, sigla, ativo) VALUES (1, 'Órgão', 'OT', true) ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.update("INSERT INTO conjunto (id, nome, ativo) VALUES (1, 'Conjunto', true) ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.update("INSERT INTO perfil (id, nome, descricao) VALUES (1, 'OPERADOR', 'Perfil') ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.update("INSERT INTO usuario (id, nome, email, senha_hash, perfil_id, ativo) VALUES (1, 'Op', 'op@test.com', 'hash', 1, true) ON CONFLICT (id) DO NOTHING");
    }

    // 2. Adicionado o método para simular o "rollback" limpando os dados de forma segura após o teste
    @AfterEach
    void tearDown() {
        // o CASCADE chega no log_exportacoes_relatorios (V36), que barra TRUNCATE por ser auditoria
        jdbcTemplate.execute("ALTER TABLE log_exportacoes_relatorios DISABLE TRIGGER trg_bloquear_truncate_log_exportacoes");
        try {
            jdbcTemplate.execute("TRUNCATE TABLE processo CASCADE");
        } finally {
            jdbcTemplate.execute("ALTER TABLE log_exportacoes_relatorios ENABLE TRIGGER trg_bloquear_truncate_log_exportacoes");
        }
    }

    @Test
    @DisplayName("US08 - Tarefa 12: aprovar o Tratamento consolida geometrias, hectares e linhagem num GeoJSON")
    @WithMockUser(roles = "GESTOR")
    void deveConsolidarPacoteAoAprovarTratamento() throws Exception {
        Long processoId = criarCarga("CALCULO_ANALITICO", "CONCLUIDA");
        Long copiaId = criarCopiaTratada(processoId);
        jdbcTemplate.update("""
            INSERT INTO resultados_calculos (processo_id, arquivo_processado_id, codigo_imovel, fonte_camada, codigo_alerta_app, hectares_sobrepostos)
            VALUES (?, ?, 'IMOVEL_T12', 'IBAMA_EMBARGO', 'EMB-1', 12.5)
            """, processoId, copiaId);

        String json = mockMvc.perform(post("/publicacao/{id}/aprovar-tratamento", processoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gestorId\": 1, \"parecer\": \"ok\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AGUARDANDO_REVISAO"))
                .andExpect(jsonPath("$.quantidadeFeicoes").value(1))
                .andExpect(jsonPath("$.hectaresSobrepostosTotal").value(12.5))
                .andReturn().getResponse().getContentAsString();

        JsonNode resposta = objectMapper.readTree(json);
        Path arquivoGerado = Path.of(resposta.get("arquivo").asText());

        try {
            // o arquivo gravado é um FeatureCollection com hectares e a linhagem junto
            byte[] bytesArquivo = Files.readAllBytes(arquivoGerado);
            String sha256Real = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytesArquivo));
            assertThat(resposta.get("hashSha256").asText()).isEqualTo(sha256Real);

            JsonNode pacote = objectMapper.readTree(bytesArquivo);
            assertThat(pacote.get("type").asText()).isEqualTo("FeatureCollection");
            JsonNode feicao = pacote.get("features").get(0);
            assertThat(feicao.get("geometry").get("type").asText()).isEqualTo("Polygon");
            assertThat(feicao.get("properties").get("areaHectares").asDouble()).isPositive();
            assertThat(feicao.get("properties").get("sobreposicoes")).hasSize(1);
            assertThat(pacote.get("linhagem").get("hashSha256").asText()).isNotBlank();
            assertThat(pacote.get("metadados").get("arquivoProcessadoOrigemId").asLong()).isEqualTo(copiaId);
            assertThat(arquivosDoProcessoNoStorage(processoId).stream().anyMatch(p -> p.getFileName().toString().endsWith(".tmp"))).isFalse();

            // aprovação ficou no histórico e a carga andou pra Publicação
            Integer aprovacoes = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM historico_aprovacoes_gestor WHERE processo_id = ? AND etapa_avaliada = 'TRATAMENTO' AND decisao = 'APROVADO'",
                    Integer.class, processoId);
            assertThat(aprovacoes).isEqualTo(1);

            String etapaAtual = jdbcTemplate.queryForObject(
                    "SELECT e.nome FROM processo p JOIN etapa e ON e.id = p.etapa_atual_id WHERE p.id = ?",
                    String.class, processoId);
            assertThat(etapaAtual).isEqualTo("PUBLICACAO");

            // segunda aprovação com pacote pendente dá conflito
            mockMvc.perform(post("/publicacao/{id}/aprovar-tratamento", processoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"gestorId\": 1}"))
                    .andExpect(status().isConflict());
        } finally {
            Files.deleteIfExists(arquivoGerado);
        }
    }

    @Test
    @DisplayName("US08 - Tarefa 12: carga em quarentena não pode ser consolidada")
    @WithMockUser(roles = "GESTOR")
    void naoDeveConsolidarCargaEmQuarentena() throws Exception {
        Long processoId = criarCarga("VALIDACAO", "EM_VALIDACAO");
        criarCopiaTratada(processoId);

        mockMvc.perform(post("/publicacao/{id}/aprovar-tratamento", processoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gestorId\": 1}"))
                .andExpect(status().isConflict());

        Integer pacotes = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM publicacao WHERE processo_id = ?", Integer.class, processoId);
        assertThat(pacotes).isZero();
    }

    @Test
    @DisplayName("Bug 1: aprovar PUBLICACAO via /carga/{id}/decisao-gestor fecha versão 1.0 sem disparar segundo UPDATE na trigger")
    @WithMockUser(roles = "GESTOR")
    void deveFecharVersaoEAtualizarSafraVigenteAoAprovarPublicacaoViaDecisaoGestor() throws Exception {
        Long processoId = criarCarga("PUBLICACAO", "EM_ANDAMENTO");
        criarCopiaTratada(processoId);

        mockMvc.perform(post("/carga/{id}/decisao-gestor", processoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gestorId\": 1, \"etapa\": \"PUBLICACAO\", \"decisao\": \"APROVADO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sucesso").value(true))
                .andExpect(jsonPath("$.etapa").value("PUBLICACAO"))
                .andExpect(jsonPath("$.decisao").value("APROVADO"));

        Map<String, Object> estado = jdbcTemplate.queryForMap("""
            SELECT p.versao_fechada, p.versao_oficial, p.safra_vigente, p.data_publicacao, p.gestor_publicacao_id,
                   e.nome AS etapa, s.nome AS situacao
            FROM processo p
            JOIN etapa e ON e.id = p.etapa_atual_id
            JOIN situacao s ON s.id = p.situacao_atual_id
            WHERE p.id = ?
            """, processoId);

        assertThat(estado.get("versao_fechada")).isEqualTo(true);
        assertThat(estado.get("versao_oficial")).isEqualTo("1.0");
        assertThat(estado.get("safra_vigente")).isEqualTo(true);
        assertThat(estado.get("data_publicacao")).isNotNull();
        assertThat(((Number) estado.get("gestor_publicacao_id")).longValue()).isEqualTo(1L);
        assertThat(estado.get("etapa")).isEqualTo("PUBLICACAO");
        assertThat(estado.get("situacao")).isEqualTo("CONCLUIDA");
    }

    @Test
    @DisplayName("Bug 1: fechar indicador via PATCH /indicadores/{id}/fechar fecha versão 1.0 sem segundo UPDATE")
    @WithMockUser(roles = "GESTOR")
    void deveFecharVersaoViaEndpointFecharIndicadorSemDispararSegundoUpdate() throws Exception {
        Long processoId = criarCarga("PUBLICACAO", "EM_ANDAMENTO");
        criarCopiaTratada(processoId);

        mockMvc.perform(patch("/indicadores/{id}/fechar", processoId)
                        .param("gestorId", "1"))
                .andExpect(status().isNoContent());

        // segunda chamada idempotente não pode disparar UPDATE na versão já fechada
        mockMvc.perform(patch("/indicadores/{id}/fechar", processoId)
                        .param("gestorId", "1"))
                .andExpect(status().isNoContent());

        Map<String, Object> estado = jdbcTemplate.queryForMap("""
            SELECT p.versao_fechada, p.versao_oficial, p.safra_vigente, p.data_publicacao, p.gestor_publicacao_id,
                   e.nome AS etapa, s.nome AS situacao
            FROM processo p
            JOIN etapa e ON e.id = p.etapa_atual_id
            JOIN situacao s ON s.id = p.situacao_atual_id
            WHERE p.id = ?
            """, processoId);

        assertThat(estado.get("versao_fechada")).isEqualTo(true);
        assertThat(estado.get("versao_oficial")).isEqualTo("1.0");
        assertThat(estado.get("safra_vigente")).isEqualTo(true);
        assertThat(estado.get("data_publicacao")).isNotNull();
        assertThat(((Number) estado.get("gestor_publicacao_id")).longValue()).isEqualTo(1L);
        assertThat(estado.get("etapa")).isEqualTo("PUBLICACAO");
        assertThat(estado.get("situacao")).isEqualTo("CONCLUIDA");
    }

    @Test
    @DisplayName("Bug 2: aprovar TRATAMENTO via /carga/{id}/decisao-gestor consolida GeoJSON em streaming e avança para PUBLICACAO")
    @WithMockUser(roles = "GESTOR")
    void deveAprovarTratamentoViaDecisaoGestorEConsolidarGeoJson() throws Exception {
        Long processoId = criarCarga("TRATAMENTO", "EM_ANDAMENTO");
        Long copiaId = criarCopiaTratada(processoId);
        jdbcTemplate.update("""
            INSERT INTO feicao_carga (arquivo_processado_id, codigo_imovel, geom)
            VALUES (?, 'IMOVEL_T12_SEM_SOBREPOSICAO', ST_GeomFromText('POLYGON((-54.4 -20.6, -54.4 -20.5, -54.3 -20.5, -54.3 -20.6, -54.4 -20.6))', 4674))
            """, copiaId);
        jdbcTemplate.update("""
            INSERT INTO resultados_calculos (processo_id, arquivo_processado_id, codigo_imovel, fonte_camada, codigo_alerta_app, hectares_sobrepostos)
            VALUES (?, ?, 'IMOVEL_T12', 'IBAMA_EMBARGO', 'EMB-2', 5.25)
            """, processoId, copiaId);

        try {
            mockMvc.perform(post("/carga/{id}/decisao-gestor", processoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"gestorId\": 1, \"etapa\": \"TRATAMENTO\", \"decisao\": \"APROVADO\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sucesso").value(true))
                    .andExpect(jsonPath("$.etapa").value("TRATAMENTO"))
                    .andExpect(jsonPath("$.decisao").value("APROVADO"));

            Map<String, Object> estado = jdbcTemplate.queryForMap("""
                SELECT e.nome AS etapa, s.nome AS situacao
                FROM processo p
                JOIN etapa e ON e.id = p.etapa_atual_id
                JOIN situacao s ON s.id = p.situacao_atual_id
                WHERE p.id = ?
                """, processoId);
            assertThat(estado.get("etapa")).isEqualTo("PUBLICACAO");
            assertThat(estado.get("situacao")).isEqualTo("EM_ANDAMENTO");

            Map<String, Object> pacoteRow = jdbcTemplate.queryForMap("""
                SELECT ap.url_armazenamento, ap.hash_sha256, pub.status
                FROM publicacao pub
                JOIN arquivo_processado ap ON ap.id = pub.arquivo_processado_id
                WHERE pub.processo_id = ?
                """, processoId);
            assertThat(pacoteRow.get("status")).isEqualTo("AGUARDANDO_REVISAO");

            Path arquivoGerado = Path.of((String) pacoteRow.get("url_armazenamento"));
            assertThat(Files.exists(arquivoGerado)).isTrue();
            byte[] bytes = Files.readAllBytes(arquivoGerado);
            String sha256Real = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertThat(pacoteRow.get("hash_sha256")).isEqualTo(sha256Real);

            JsonNode pacote = objectMapper.readTree(bytes);
            assertThat(pacote.get("type").asText()).isEqualTo("FeatureCollection");
            assertThat(pacote.get("features")).hasSize(2);
            assertThat(pacote.get("features").get(0).get("properties").get("sobreposicoes")).hasSize(1);
            assertThat(pacote.get("features").get(1).get("properties").get("sobreposicoes")).isEmpty();
            assertThat(pacote.get("metadados").get("quantidadeFeicoes").asLong()).isEqualTo(2L);
            assertThat(pacote.get("metadados").get("hectaresSobrepostosTotal").asDouble()).isEqualTo(5.25);
            assertThat(pacote.get("linhagem").get("processoId").asLong()).isEqualTo(processoId);
            assertThat(arquivosDoProcessoNoStorage(processoId).stream().anyMatch(p -> p.getFileName().toString().endsWith(".tmp"))).isFalse();
        } finally {
            limparArquivosDoProcessoNoStorage(processoId);
        }
    }

    @Test
    @DisplayName("Bug 2: erro de banco durante consolidação no savepoint mantém HTTP 200 na transição do TRATAMENTO e não deixa .tmp/.geojson")
    @WithMockUser(roles = "GESTOR")
    void devePreservarTransacaoELimparTemporariosQuandoConsolidacaoFalharNoSavepoint() throws Exception {
        Long processoId = criarCarga("TRATAMENTO", "EM_ANDAMENTO");
        Long copiaId = jdbcTemplate.queryForObject("""
            INSERT INTO arquivo_processado (processo_id, arquivo_original_id, etapa_geracao_id, nome_arquivo, url_armazenamento, versao)
            VALUES (?, (SELECT id FROM arquivo_original WHERE processo_id = ?), (SELECT id FROM etapa WHERE nome = 'TRATAMENTO'),
                    't12_invalido.geojson', '/tmp/t12_invalido.geojson', 1)
            RETURNING id
            """, Long.class, processoId, processoId);

        // CurvePolygon é válido na coluna geometry(Geometry, 4674), mas falha no cast ::geography / ST_AsGeoJSON do PostGIS
        jdbcTemplate.update("""
            INSERT INTO feicao_carga (arquivo_processado_id, codigo_imovel, geom)
            VALUES (?, 'IMOVEL_INVALIDO', ST_GeomFromText('CURVEPOLYGON(CIRCULARSTRING(0 0, 4 0, 4 4, 0 4, 0 0))', 4674))
            """, copiaId);

        try {
            mockMvc.perform(post("/carga/{id}/decisao-gestor", processoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"gestorId\": 1, \"etapa\": \"TRATAMENTO\", \"decisao\": \"APROVADO\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sucesso").value(true))
                    .andExpect(jsonPath("$.etapa").value("TRATAMENTO"))
                    .andExpect(jsonPath("$.decisao").value("APROVADO"));

            Map<String, Object> estado = jdbcTemplate.queryForMap("""
                SELECT e.nome AS etapa, s.nome AS situacao
                FROM processo p
                JOIN etapa e ON e.id = p.etapa_atual_id
                JOIN situacao s ON s.id = p.situacao_atual_id
                WHERE p.id = ?
                """, processoId);
            assertThat(estado.get("etapa")).isEqualTo("PUBLICACAO");
            assertThat(estado.get("situacao")).isEqualTo("EM_ANDAMENTO");

            Integer avisos = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM log_processamento WHERE processo_id = ? AND nivel = 'WARNING'",
                    Integer.class, processoId);
            assertThat(avisos).isGreaterThanOrEqualTo(1);

            Integer publicacoes = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM publicacao WHERE processo_id = ?",
                    Integer.class, processoId);
            assertThat(publicacoes).isZero();

            assertThat(arquivosDoProcessoNoStorage(processoId)).isEmpty();
        } finally {
            limparArquivosDoProcessoNoStorage(processoId);
        }
    }

    private List<Path> arquivosDoProcessoNoStorage(Long processoId) throws Exception {
        if (!Files.exists(PASTA_PROCESSADA)) {
            return List.of();
        }
        String prefixo = processoId + "_";
        try (Stream<Path> stream = Files.list(PASTA_PROCESSADA)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith(prefixo))
                    .toList();
        }
    }

    private void limparArquivosDoProcessoNoStorage(Long processoId) throws Exception {
        for (Path p : arquivosDoProcessoNoStorage(processoId)) {
            Files.deleteIfExists(p);
        }
    }

    private Long criarCarga(String etapa, String situacao) {
        Long processoId = jdbcTemplate.queryForObject("""
            INSERT INTO processo (operador_id, conjunto_id, orgao_id, etapa_atual_id, situacao_atual_id, ano_safra, epsg_origem)
            VALUES (1, 1, 1, (SELECT id FROM etapa WHERE nome = ?), (SELECT id FROM situacao WHERE nome = ?), '2026', '4674')
            RETURNING id
            """, Long.class, etapa, situacao);

        jdbcTemplate.update("""
            INSERT INTO processo_etapa (processo_id, etapa_id, situacao_id, data_inicio, mensagem)
            VALUES (?, (SELECT id FROM etapa WHERE nome = ?), (SELECT id FROM situacao WHERE nome = ?), NOW(), 'Carga mock criada para teste')
            """, processoId, etapa, situacao);

        jdbcTemplate.update("""
            INSERT INTO arquivo_original (processo_id, usuario_upload_id, nome_original, url_armazenamento, hash_sha256)
            VALUES (?, 1, 't12.geojson', '/tmp/t12.geojson', ?)
            """, processoId, UUID.randomUUID().toString().replace("-", ""));
        return processoId;
    }

    private Long criarCopiaTratada(Long processoId) {
        Long copiaId = jdbcTemplate.queryForObject("""
            INSERT INTO arquivo_processado (processo_id, arquivo_original_id, etapa_geracao_id, nome_arquivo, url_armazenamento, versao)
            VALUES (?, (SELECT id FROM arquivo_original WHERE processo_id = ?), (SELECT id FROM etapa WHERE nome = 'TRATAMENTO'),
                    't12_tratado.geojson', '/tmp/t12_tratado.geojson', 1)
            RETURNING id
            """, Long.class, processoId, processoId);

        jdbcTemplate.update("""
            INSERT INTO feicao_carga (arquivo_processado_id, codigo_imovel, geom)
            VALUES (?, 'IMOVEL_T12', ST_GeomFromText('POLYGON((-54.6 -20.6, -54.6 -20.5, -54.5 -20.5, -54.5 -20.6, -54.6 -20.6))', 4674))
            """, copiaId);
        return copiaId;
    }
}