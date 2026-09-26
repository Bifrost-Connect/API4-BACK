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

        // 1. Carga do domínio basilar
        jdbcTemplate.update("""
            INSERT INTO etapa (id, nome, ordem, descricao) 
            VALUES (1, 'CARGA_E_VALIDACAO', 1, 'Etapa de entrada de dados') 
            ON CONFLICT (id) DO NOTHING;
        """);

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

        // 2. Persiste o processo
        Processo processo = new Processo();
        processo.setOrgaoId(1L);
        processo.setOperadorId(1L);
        processo.setConjuntoId(1L);
        processo.setSituacaoAtualId(1L);
        processo.setAnoSafra("2025");
        processo.setEpsgOrigem("4326");

        Processo processoSalvo = processoRepository.save(processo);
        this.processoId = processoSalvo.getId();

        // 3. Insere a entrada da tabela processo_etapa (necessária para satisfazer FK de log_processamento)
        jdbcTemplate.update("""
            INSERT INTO processo_etapa (id, processo_id, etapa_id, situacao_id, data_inicio) 
            VALUES (1, ?, 1, 1, NOW()) 
            ON CONFLICT (id) DO NOTHING;
        """, this.processoId);

        // Associa o ID da etapa criada
        processoSalvo.setEtapaAtualId(1L);
        processoRepository.save(processoSalvo);
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
        Long situacaoQuarentenaEsperada = 4L; // ID 4 = FALHOU

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