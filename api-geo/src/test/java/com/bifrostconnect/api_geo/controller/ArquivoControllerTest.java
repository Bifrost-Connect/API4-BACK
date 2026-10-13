package com.bifrostconnect.api_geo.controller;

import java.nio.file.Files;
import java.nio.file.Path;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;
import com.bifrostconnect.api_geo.service.ProcessoService;
import com.bifrostconnect.api_geo.service.ValidacaoService;

@SpringBootTest
@TestPropertySource(properties = "spring.flyway.clean-disabled=false")
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

    @Autowired
    private Flyway flyway; // ARQUITETURA: Motor de migração injetado

    private Long processoId;
    private Long processoEtapaId;
    
    // Flag para garantir que a recriação do banco ocorra apenas 1x por classe de teste
    private static boolean bancoRestaurado = false; 

    @BeforeEach
    void setUp() {
        // CURA AUTOMÁTICA DO BANCO DE DADOS:
        // Destrói o schema corrompido e roda todas as 18 migrations do zero.
        if (!bancoRestaurado) {
            flyway.clean();
            flyway.migrate();
            bancoRestaurado = true;
        }

        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        // Inserts apenas para chaves estrangeiras periféricas não criadas pelo Flyway
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

        Processo processo = new Processo();
        processo.setOrgaoId(1L);
        processo.setOperadorId(1L);
        processo.setConjuntoId(1L);
        processo.setSituacaoAtualId(1L); // 1 = EM_ANDAMENTO garantido pelo Flyway
        processo.setAnoSafra("2025");
        processo.setEpsgOrigem("4326");

        Processo processoSalvo = processoRepository.save(processo);
        this.processoId = processoSalvo.getId();

        this.processoEtapaId = jdbcTemplate.queryForObject("""
            INSERT INTO processo_etapa (processo_id, etapa_id, situacao_id, data_inicio) 
            VALUES (?, 1, 1, NOW())
            RETURNING id
        """, Long.class, this.processoId);

        processoSalvo.setEtapaAtualId(1L); // 1 = INGESTAO garantido pelo Flyway
        processoRepository.save(processoSalvo);
    }

    @AfterEach
    void tearDown() {
        // Limpeza segura entre testes individuais da mesma classe
        jdbcTemplate.execute("TRUNCATE TABLE processo, auditoria RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("Deve realizar o upload de arquivo na Zona Bruta com sucesso")
    void deveFazerUploadDeArquivo() throws Exception {
        String conteudo = "{\"type\": \"FeatureCollection\", \"features\": [], \"teste\": "
                + System.nanoTime() + "}";

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "malha_sidrolandia.geojson",
                "application/json",
                conteudo.getBytes()
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
    void deveMoverParaQuarentenaQuandoValidacaoFalhar() throws Exception {
        Long situacaoQuarentenaEsperada = 4L; 
        Path arquivoInvalido = Files.createTempFile("arquivo_invalido", ".txt");
        Files.writeString(arquivoInvalido, "Conteudo invalido para geojson");

        Processo processo = processoRepository.findById(processoId).orElseThrow();
        Processo processoResultado = processoService.processarArquivo(processo, "arquivo_invalido.txt", arquivoInvalido, 1L);

        assertEquals(situacaoQuarentenaEsperada, processoResultado.getSituacaoAtualId());
        Files.deleteIfExists(arquivoInvalido);
    }
}