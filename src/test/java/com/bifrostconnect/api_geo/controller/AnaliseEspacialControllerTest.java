package com.bifrostconnect.api_geo.controller;

import com.bifrostconnect.api_geo.dto.EvolucaoResponseDTO;
import com.bifrostconnect.api_geo.service.ComparacaoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AnaliseEspacialController.class)
@AutoConfigureMockMvc(addFilters = false) // Desativa temporariamente o Spring Security para o teste isolado
class AnaliseEspacialControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ComparacaoService comparacaoService;

    @Test
    @WithMockUser
    void deveRetornarEvolucaoGlobal() throws Exception {
        when(comparacaoService.calcularEvolucao(eq(1L), eq(2L), any(), any()))
                .thenReturn(new EvolucaoResponseDTO(
                        1500.5, 1750.0, 249.5, 16.63, 300.0, 50.5, 1450.0, false,
                        "{\"type\":\"FeatureCollection\",\"features\":[]}"));

        String requestBody = """
                {
                  "safraAId": 1,
                  "safraBId": 2
                }
                """;

        mockMvc.perform(post("/analise/comparacao/evolucao")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areaSafraAHectares").value(1500.5))
                .andExpect(jsonPath("$.saldoVariacaoHectares").value(249.5));
    }

    @Test
    @WithMockUser
    void deveRetornarComparacaoPorImovel() throws Exception {
        mockMvc.perform(get("/analise/comparacao/imovel/SP-123456")
                .param("safraAId", "1")
                .param("safraBId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identificadorCar").value("SP-123456"))
                .andExpect(jsonPath("$.statusEvolucao").value("AUMENTO"));
    }
}