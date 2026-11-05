package com.bifrostconnect.api_geo.controller;

import com.bifrostconnect.api_geo.service.MotorGeomatematicoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AnaliseEspacialController.class)
@AutoConfigureMockMvc(addFilters = false) // Desativa temporariamente o Spring Security para o teste isolado
class AnaliseEspacialControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MotorGeomatematicoService motorGeomatematicoService;

    @Test
    @WithMockUser
    void deveRetornarEvolucaoGlobal() throws Exception {
        String requestBody = """
                {
                  "safraAId": 1,
                  "safraBId": 2
                }
                """;

        mockMvc.perform(post("/api/analise/comparacao/evolucao")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areaSafraAHectares").value(1500.5))
                .andExpect(jsonPath("$.saldoVariacaoHectares").value(249.5));
    }

    @Test
    @WithMockUser
    void deveRetornarComparacaoPorImovel() throws Exception {
        mockMvc.perform(get("/api/analise/comparacao/imovel/SP-123456")
                .param("safraAId", "1")
                .param("safraBId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identificadorCar").value("SP-123456"))
                .andExpect(jsonPath("$.statusEvolucao").value("AUMENTO"));
    }
}