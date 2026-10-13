package com.bifrostconnect.api_geo.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@TestPropertySource(properties = "spring.flyway.clean-disabled=false")
public class CargaIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway; // ARQUITETURA: Motor de migração injetado

    private Long processoTesteId;
    private static boolean bancoRestaurado = false;

    @BeforeEach
    public void setUp() {
        // CURA AUTOMÁTICA DO BANCO DE DADOS
        if (!bancoRestaurado) {
            flyway.clean();
            flyway.migrate();
            bancoRestaurado = true;
        }

        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        jdbcTemplate.update("""
            INSERT INTO orgao (id, nome, sigla, ativo) 
            VALUES (1, 'Órgão Teste', 'OT', true) 
            ON CONFLICT (id) DO NOTHING;
        """);

        jdbcTemplate.update("""
            INSERT INTO conjunto (id, nome, ativo) 
            VALUES (1, 'Conjunto Teste', true) 
            ON CONFLICT (id) DO NOTHING;
        """);

        jdbcTemplate.update("""
            INSERT INTO perfil (id, nome, descricao) 
            VALUES (1, 'OPERADOR', 'Responsavel pela entrada das cargas') 
            ON CONFLICT (id) DO NOTHING;
        """);

        jdbcTemplate.update("""
            INSERT INTO usuario (id, nome, email, senha_hash, perfil_id, ativo) 
            VALUES (1, 'Operador Teste', 'teste@bifrostconnect.com', 'hash_exemplo', 1, true) 
            ON CONFLICT (id) DO NOTHING;
        """);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE processo, auditoria RESTART IDENTITY CASCADE");
    }

    @Test
    public void deveCriarMetadadosEEncontrarNaListagem() throws Exception {
        String jsonRequest = """
            {
                "orgaoId": 1,
                "conjuntoId": 1,
                "operadorId": 1,
                "anoSafra": "2024",
                "epsgOrigem": "4326"
            }
        """;

        MvcResult resultPost = mockMvc.perform(post("/carga/metadados")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.etapaAtualId").isNumber())
                .andExpect(jsonPath("$.situacaoAtualId").isNumber())
                .andReturn();

        String responseContent = resultPost.getResponse().getContentAsString();
        Integer idCriado = JsonPath.read(responseContent, "$.id");
        this.processoTesteId = idCriado.longValue();

        mockMvc.perform(get("/processos"))
                .andExpect(status().isOk());
    }
}
