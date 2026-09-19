package com.bifrostconnect.api_geo.controller;

import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class ArquivoControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ProcessoRepository processoRepository;

    private Long processoId;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        Processo processo = new Processo();
        processo.setOrgaoId(1L);
        processo.setOperadorId(1L);
        processo.setConjuntoId(1L);
        processo.setAno("2025/2026");
        processo.setEpsg("EPSG:4326");

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