package com.bifrostconnect.api_geo.controller;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;
import com.bifrostconnect.api_geo.service.ProcessoService;
import com.bifrostconnect.api_geo.service.ValidacaoService;

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

    @Autowired
    private ValidacaoService validacaoService;

    @Autowired
    private ProcessoService processoService;

    private Long processoId;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        // Insere todas as etapas exigidas pelo fluxo de processamento
        jdbcTemplate.update("INSERT INTO etapa (id, nome, ordem, descricao) VALUES (1, 'CARGA_E_VALIDACAO', 1, 'Carga') ON CONFLICT (id) DO NOTHING;");
        jdbcTemplate.update("INSERT INTO etapa (id, nome, ordem, descricao) VALUES (2, 'TRATAMENTO', 2, 'Tratamento') ON CONFLICT (id) DO NOTHING;");
        jdbcTemplate.update("INSERT INTO etapa (id, nome, ordem, descricao) VALUES (3, 'VALIDACAO', 3, 'Validacao') ON CONFLICT (id) DO NOTHING;");
        jdbcTemplate.update("INSERT INTO etapa (id, nome, ordem, descricao) VALUES (4, 'CALCULO_ANALITICO', 4, 'Calculo') ON CONFLICT (id) DO NOTHING;");

        // Insere todas as situações exigidas
        jdbcTemplate.update("INSERT INTO situacao (id, nome) VALUES (1, 'EM_ANDAMENTO') ON CONFLICT (id) DO NOTHING;");
        jdbcTemplate.update("INSERT INTO situacao (id, nome) VALUES (2, 'CONCLUIDA') ON CONFLICT (id) DO NOTHING;");
        jdbcTemplate.update("INSERT INTO situacao (id, nome) VALUES (3, 'FALHOU') ON CONFLICT (id) DO NOTHING;");
        jdbcTemplate.update("INSERT INTO situacao (id, nome) VALUES (4, 'QUARENTENA') ON CONFLICT (id) DO NOTHING;");
        jdbcTemplate.update("INSERT INTO situacao (id, nome) VALUES (999, 'FECHADO') ON CONFLICT (id) DO NOTHING;");

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

        // Inserção direta via JdbcTemplate garantindo existência física imediata no banco e evitando o erro de FK
        this.processoId = jdbcTemplate.queryForObject(
            """
            INSERT INTO processo (ano_safra, epsg_origem, epsg_destino, orgao_id, operador_id, conjunto_id, situacao_atual_id, etapa_atual_id, data_criacao)
            VALUES ('2025', '4326', '31983', 1, 1, 1, 1, 1, NOW())
            RETURNING id;
            """,
            Long.class
        );
    }

    @Test
    @DisplayName("Deve realizar o upload de arquivo na Zona Bruta com sucesso")
    @WithMockUser(username = "operador", roles = "OPERADOR")
    void deveFazerUploadDeArquivo() throws Exception {
        String conteudoUnico = "{\"type\": \"FeatureCollection\", \"features\": [], \"nonce\": " + System.currentTimeMillis() + "}";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "malha_sidrolandia.geojson",
                "application/json",
                conteudoUnico.getBytes()
        );

        mockMvc.perform(multipart("/api/v1/arquivos/upload")
                        .file(file)
                        .param("processoId", processoId.toString())
                        .param("usuarioId", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sucesso").value(true))
                .andExpect(jsonPath("$.mensagem").value("Carga realizada na Zona Bruta com sucesso!"))
                .andExpect(jsonPath("$.hash_sha256").exists())
                .andExpect(jsonPath("$.id_arquivo").exists());
    }

    @Test
    @DisplayName("Tarefa 1 (Motor Espacial): Deve ler GeoJSON e validar motor espacial sem sobreposição")
    @WithMockUser(username = "operador", roles = "OPERADOR")
    void deveValidarMotorEspacialComSucesso() throws Exception {
        String geoJsonConteudo = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "properties": { "nome": "Polígono Teste" },
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [
                      [
                        [-46.633308, -23.550520],
                        [-46.633308, -23.551520],
                        [-46.632308, -23.551520],
                        [-46.632308, -23.550520],
                        [-46.633308, -23.550520]
                      ]
                    ]
                  }
                }
              ]
            }
        """;

        Path arquivoTemp = Files.createTempFile("teste_motor_espacial", ".geojson");
        Files.writeString(arquivoTemp, geoJsonConteudo);

        Processo processo = processoRepository.findById(processoId).orElseThrow();

        boolean valido = validacaoService.executarValidacoes(processo, "teste_motor_espacial.geojson", arquivoTemp);

        assertTrue(valido, "A validação do motor espacial deve passar sem sobreposições prévias.");

        Files.deleteIfExists(arquivoTemp);  
    }

    @Test
    @DisplayName("Tarefa 2 (Gatilho Quarentena): Deve alterar situacaoAtualId para 4 (FALHOU/Quarentena) ao falhar validação")
    @WithMockUser(username = "operador", roles = "OPERADOR")
    void deveMoverParaQuarentenaQuandoValidacaoFalhar() throws Exception {
        Long situacaoQuarentenaEsperada = 4L;

        Path arquivoInvalido = Files.createTempFile("arquivo_invalido", ".txt");
        Files.writeString(arquivoInvalido, "Conteudo invalido para geojson");

        Processo processo = processoRepository.findById(processoId).orElseThrow();

        Processo processoResultado = processoService.processarArquivo(
                processo, 
                "arquivo_invalido.txt", 
                arquivoInvalido, 
                1L
        );

        assertEquals(situacaoQuarentenaEsperada, processoResultado.getSituacaoAtualId());

        Files.deleteIfExists(arquivoInvalido);
    }
}