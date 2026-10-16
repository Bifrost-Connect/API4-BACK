package com.bifrostconnect.api_geo.integration;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
public class LinhagemIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ProcessoRepository processoRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();

        jdbcTemplate.update("INSERT INTO orgao (id, nome, sigla, ativo) VALUES (1, 'Órgão', 'OT', true) ON CONFLICT (id) DO NOTHING;");
        jdbcTemplate.update("INSERT INTO conjunto (id, nome, ativo) VALUES (1, 'Conjunto', true) ON CONFLICT (id) DO NOTHING;");
        jdbcTemplate.update("INSERT INTO perfil (id, nome, descricao) VALUES (1, 'OPERADOR', 'Perfil') ON CONFLICT (id) DO NOTHING;");
        jdbcTemplate.update("INSERT INTO usuario (id, nome, email, senha_hash, perfil_id, ativo) VALUES (1, 'Op', 'op@test.com', 'hash', 1, true) ON CONFLICT (id) DO NOTHING;");
        jdbcTemplate.update("INSERT INTO situacao (id, nome) VALUES (1, 'EM_ANDAMENTO') ON CONFLICT (id) DO NOTHING;");
        jdbcTemplate.update("INSERT INTO situacao (id, nome) VALUES (999, 'FECHADO') ON CONFLICT (id) DO NOTHING;");
        jdbcTemplate.update("INSERT INTO etapa (id, nome, ordem, descricao) VALUES (5, 'VALIDACAO', 1, 'Etapa') ON CONFLICT (id) DO NOTHING;");
    }

    @Test
    @DisplayName("Tarefa 9: Deve retornar a linhagem completa contendo o Hash SHA-256 exato da ingestão")
    @WithMockUser(roles = "AUDITOR")
    void deveRetornarLinhagemComHashExato() throws Exception {
        String hashUnico = UUID.randomUUID().toString().replace("-", "").toUpperCase();

        Processo p = Processo.builder()
                .anoSafra("2026")
                .orgaoId(1L)
                .operadorId(1L)
                .conjuntoId(1L)
                .epsgOrigem("4326")
                .epsgDestino("31983")
                .situacaoAtualId(1L)
                .etapaAtualId(5L)
                .build();
        p = processoRepository.saveAndFlush(p);

        jdbcTemplate.update(
            "INSERT INTO arquivo_original (processo_id, usuario_upload_id, hash_sha256, nome_original, url_armazenamento) VALUES (?, 1, ?, 'teste.geojson', 'http://storage/teste.geojson')",
            p.getId(), hashUnico
        );

        mockMvc.perform(get("/api/indicadores/" + p.getId() + "/linhagem"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indicadorId").value(p.getId()))
                .andExpect(jsonPath("$.hashSha256").value(hashUnico));
    }

    @Test
    @DisplayName("Tarefa 8: Deve retornar Status 404 (Not Found) quando indicador não existir")
    @WithMockUser(roles = "AUDITOR")
    void deveRetornar404ParaIndicadorInexistente() throws Exception {
        mockMvc.perform(get("/api/indicadores/99999/linhagem"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Tarefa 8: Deve retornar 403 (Forbidden) se Auditor tentar fazer um POST")
    @WithMockUser(roles = "AUDITOR")
    void deveBloquearMetodosDeEscritaParaAuditor() throws Exception {
        mockMvc.perform(post("/processos/1/processar")
                .param("nomeArquivo", "teste.geojson")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Tarefa 9: Deve recusar alteração (PATCH) em versão/processo fechado retornando 409 Conflict")
    @WithMockUser(roles = "ADMINISTRADOR")
    void deveRecusarAlteracaoEmVersaoFechada() throws Exception {
        Processo p = Processo.builder()
                .anoSafra("2026")
                .orgaoId(1L)
                .operadorId(1L)
                .conjuntoId(1L)
                .epsgOrigem("4326")
                .epsgDestino("31983")
                .situacaoAtualId(999L)
                .etapaAtualId(5L)
                .build();
        p = processoRepository.saveAndFlush(p);

        String jsonBody = "{\"anoSafra\":\"2025/2026\",\"epsgOrigem\":\"4326\",\"epsgDestino\":\"31983\"}";

        mockMvc.perform(patch("/processos/" + p.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody))
                .andExpect(status().isConflict());
    }
}