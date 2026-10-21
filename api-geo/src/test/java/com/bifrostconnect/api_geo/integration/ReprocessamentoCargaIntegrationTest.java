package com.bifrostconnect.api_geo.integration;

import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
public class ReprocessamentoCargaIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ProcessoRepository processoRepository;

    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("Tarefa 18: Operador deve conseguir enviar novo arquivo corrigido e reiniciar reprocessamento com sucesso")
    @WithMockUser(roles = "OPERADOR")
    void deveReprocessarCargaComSucesso() throws Exception {
        Processo p = Processo.builder()
                .anoSafra("2026")
                .orgaoId(1L)
                .operadorId(1L)
                .conjuntoId(1L)
                .epsgOrigem("4326")
                .epsgDestino("31983")
                .build();
        p = processoRepository.saveAndFlush(p);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "arquivo_corrigido.geojson",
                MediaType.APPLICATION_JSON_VALUE,
                "{\"type\": \"FeatureCollection\", \"features\": []}".getBytes()
        );

        mockMvc.perform(multipart("/api/carga/" + p.getId() + "/reprocessar")
                .file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nomeOriginal").value("arquivo_corrigido.geojson"))
                .andExpect(jsonPath("$.hashSha256").isNotEmpty());
    }
}