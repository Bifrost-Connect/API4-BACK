package com.bifrostconnect.api_geo.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;

@SpringBootTest
@Transactional
class ArquivoControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ProcessoRepository processoRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long processoId;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        // 1. Insere o Órgão com ID 1
        jdbcTemplate.update("""
            INSERT INTO orgao (id, nome, sigla, ativo) 
            VALUES (1, 'Órgão Teste', 'OT', true) 
            ON CONFLICT (id) DO NOTHING;
        """);

        // 2. Insere o Conjunto com ID 1
        jdbcTemplate.update("""
            INSERT INTO conjunto (id, nome, ativo) 
            VALUES (1, 'Conjunto Teste', true) 
            ON CONFLICT (id) DO NOTHING;
        """);

        // 3. Insere o Perfil com ID 1
        jdbcTemplate.update("""
            INSERT INTO perfil (id, nome, descricao) 
            VALUES (1, 'OPERADOR', 'Responsavel pela entrada das cargas') 
            ON CONFLICT (id) DO NOTHING;
        """);

        // 4. Insere o Usuário com ID 1
        jdbcTemplate.update("""
            INSERT INTO usuario (id, nome, email, senha_hash, perfil_id, ativo) 
            VALUES (1, 'Operador Teste', 'teste@bifrostconnect.com', 'hash_exemplo', 1, true) 
            ON CONFLICT (id) DO NOTHING;
        """);

        // 5. Agora sim, cria o processo com as dependências satisfeitas
        Processo processo = new Processo();
        processo.setOrgaoId(1L);
        processo.setOperadorId(1L);
        processo.setConjuntoId(1L);
        processo.setAnoSafra("2025");
        processo.setEpsgOrigem("4326");

        Processo processoSalvo = processoRepository.save(processo);
        this.processoId = processoSalvo.getId();
    }

    @Test
    @DisplayName("Deve realizar o upload de arquivo na Zona Bruta com sucesso")
    void deveFazerUploadDeArquivo() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "malha_sidrolandia.geojson",
                "application/json",
                "{\"type\": \"FeatureCollection\", \"features\": []}".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/arquivos/upload")
                        .file(file)
                        .param("processoId", processoId.toString())
                        .param("usuarioId", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sucesso").value(true))
                .andExpect(jsonPath("$.mensagem").value("Carga realizada na Zona Bruta com sucesso!"));
    }
}