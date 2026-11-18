package com.bifrostconnect.api_geo.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import java.util.Optional;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.bifrostconnect.api_geo.security.UsuarioSessaoService;
import com.bifrostconnect.api_geo.security.UsuarioSessaoService.EstadoUsuario;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@Transactional
class ExternoApiKeyIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;
    @MockBean
    private UsuarioSessaoService usuarioSessaoService;
    private long adminId;
    private long basePublicadaId;
    private long baseRascunhoId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        jdbc.execute("INSERT INTO perfil (nome) VALUES ('ADMINISTRADOR') ON CONFLICT DO NOTHING");
        jdbc.execute("""
                INSERT INTO usuario (nome, email, senha_hash, perfil_id, ativo)
                VALUES ('Admin Teste', 'admin@teste.local', 'hash',
                        (SELECT id FROM perfil WHERE nome = 'ADMINISTRADOR' LIMIT 1), TRUE)
                ON CONFLICT (email) DO UPDATE SET ativo = TRUE
                """);

        adminId = jdbc.queryForObject("SELECT id FROM usuario WHERE email = 'admin@teste.local'", Long.class);

        when(usuarioSessaoService.buscarPorLogin(anyString())).thenReturn(
                Optional.of(new EstadoUsuario(
                        adminId,
                        "Admin Teste",
                        "admin@teste.local",
                        "hash",
                        "ADMINISTRADOR",
                        true,
                        1,
                        false)));

        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        long conjuntoId = jdbc.queryForObject(
                "INSERT INTO conjunto (nome, ativo) VALUES (?, TRUE) RETURNING id",
                Long.class, "Conjunto Externo " + sufixo);
        long orgaoId = jdbc.queryForObject("SELECT min(id) FROM orgao", Long.class);

        basePublicadaId = criarBase(conjuntoId, orgaoId, "2025", true);
        baseRascunhoId = criarBase(conjuntoId, orgaoId, "2026", false);
    }

    @Test
    @WithMockUser(username = "admin@teste.local", roles = "ADMINISTRADOR")
    @DisplayName("US12 - Tarefas 12, 13, 14 e 15: fluxo completo da API Key externa")
    void deveValidarCicloCompletoApiKeyExterna() throws Exception {
        // 1. Sem header X-API-Key -> 401
        mockMvc.perform(get("/v1/externo/catalogo"))
                .andExpect(status().isUnauthorized());

        // 2. Emite uma chave com escopo restrito apenas a CATALOGO
        MvcResult emissaoRes = mockMvc.perform(post("/integracoes/chaves")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "nomeParceiro": "Parceiro INPE",
                                  "escopos": ["CATALOGO"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> criada = objectMapper.readValue(
                emissaoRes.getResponse().getContentAsString(), Map.class);
        Long chaveId = ((Number) criada.get("id")).longValue();
        String apiKey = (String) criada.get("chave");

        // 3. Acessa /catalogo -> 200 OK e lista apenas a base publicada
        mockMvc.perform(get("/v1/externo/catalogo")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + basePublicadaId + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + baseRascunhoId + ")]").doesNotExist());

        // 4. Verifica Tarefa 14: ultimo_uso_em preenchido e auditoria gravada
        Object ultimoUso = jdbc.queryForObject(
                "SELECT ultimo_uso_em FROM chaves_api WHERE id = ?", Object.class, chaveId);
        assertThat(ultimoUso).isNotNull();

        Integer logsAuditoria = jdbc.queryForObject(
                "SELECT count(*) FROM auditoria WHERE usuario_id = ? AND acao = 'CONSUMO_API_EXTERNA'",
                Integer.class, adminId);
        assertThat(logsAuditoria).isGreaterThanOrEqualTo(1);

        // 5. Tenta acessar /geojson sem ter o escopo GEOJSON -> 403 Forbidden
        mockMvc.perform(get("/v1/externo/bases/{id}/geojson", basePublicadaId)
                        .header("X-API-Key", apiKey))
                .andExpect(status().isForbidden());

        // 6. Emite uma segunda chave com escopo LEITURA (acesso total de leitura)
        MvcResult emissaoFull = mockMvc.perform(post("/integracoes/chaves")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "nomeParceiro": "Parceiro Full",
                                  "escopos": ["LEITURA"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> criadaFull = objectMapper.readValue(
                emissaoFull.getResponse().getContentAsString(), Map.class);
        Long chaveFullId = ((Number) criadaFull.get("id")).longValue();
        String apiKeyFull = (String) criadaFull.get("chave");

        // 7. Consulta métricas e geojson da base publicada -> 200 OK
        mockMvc.perform(get("/v1/externo/bases/{id}/metricas", basePublicadaId)
                        .header("X-API-Key", apiKeyFull))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metricas.total_feicoes").value(1));

        mockMvc.perform(get("/v1/externo/bases/{id}/geojson", basePublicadaId)
                        .header("X-API-Key", apiKeyFull))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("FeatureCollection"));

        // 8. Tenta consultar base não publicada (versao_fechada = FALSE) -> 403 Forbidden
        mockMvc.perform(get("/v1/externo/bases/{id}/metricas", baseRascunhoId)
                        .header("X-API-Key", apiKeyFull))
                .andExpect(status().isForbidden());

        // 9. Tarefa 15: Revoga a chave e testa imediatamente -> 401 Unauthorized sem delay de cache
        mockMvc.perform(patch("/integracoes/chaves/{id}/revogar", chaveFullId)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOGADA"));

        mockMvc.perform(get("/v1/externo/catalogo")
                        .header("X-API-Key", apiKeyFull))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "admin@teste.local", roles = "ADMINISTRADOR")
    @DisplayName("US12 - Tarefa 14: Rate Limiting bloqueia na 61ª requisição no mesmo minuto com 429")
    void deveBloquearQuandoExceder60RequisicoesPorMinuto() throws Exception {
        MvcResult emissaoRes = mockMvc.perform(post("/integracoes/chaves")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "nomeParceiro": "Parceiro Loop",
                                  "escopos": ["CATALOGO"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> criada = objectMapper.readValue(
                emissaoRes.getResponse().getContentAsString(), Map.class);
        String apiKey = (String) criada.get("chave");

        for (int i = 1; i <= 60; i++) {
            mockMvc.perform(get("/v1/externo/catalogo")
                            .header("X-API-Key", apiKey))
                    .andExpect(status().isOk());
        }

        // 61ª chamada no mesmo minuto -> 429 Too Many Requests
        mockMvc.perform(get("/v1/externo/catalogo")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isTooManyRequests());
    }

    private long criarBase(long conjuntoId, long orgaoId, String anoSafra, boolean publicada) {
        long processoId = jdbc.queryForObject("""
                INSERT INTO processo (operador_id, conjunto_id, orgao_id, ano_safra, epsg_origem)
                VALUES (?, ?, ?, ?, '4674')
                RETURNING id
                """,
                Long.class, adminId, conjuntoId, orgaoId, anoSafra);

        long originalId = jdbc.queryForObject("""
                INSERT INTO arquivo_original (processo_id, usuario_upload_id, nome_original, url_armazenamento, hash_sha256)
                VALUES (?, ?, 'base.zip', 'teste/base.zip', ?)
                RETURNING id
                """,
                Long.class, processoId, adminId,
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));

        long processadoId = jdbc.queryForObject("""
                INSERT INTO arquivo_processado (arquivo_original_id, processo_id, etapa_geracao_id, nome_arquivo, url_armazenamento, versao)
                VALUES (?, ?, (SELECT id FROM etapa WHERE nome = 'TRATAMENTO'), 'tratado.geojson', 'teste/tratado.geojson', 1)
                RETURNING id
                """,
                Long.class, originalId, processoId);

        jdbc.update("""
                INSERT INTO feicao_carga (arquivo_processado_id, codigo_imovel, geom)
                VALUES (?, 'CAR-EXT-01', ST_GeomFromText('POLYGON((-47.0 -15.01, -46.99 -15.01, -46.99 -15.0, -47.0 -15.0, -47.0 -15.01))', 4674))
                """, processadoId);

        if (publicada) {
            jdbc.update("""
                    INSERT INTO historico_aprovacoes_gestor (processo_id, gestor_id, etapa_avaliada, decisao)
                    VALUES (?, ?, 'PUBLICACAO', 'APROVADO')
                    """, processoId, adminId);

            jdbc.update("""
                    UPDATE processo
                       SET versao_oficial = '1.0',
                           versao_fechada = TRUE,
                           data_publicacao = now(),
                           gestor_publicacao_id = ?
                     WHERE id = ?
                    """, adminId, processoId);
        }

        return processoId;
    }
}