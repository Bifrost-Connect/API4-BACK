package com.bifrostconnect.api_geo.controller;

import com.bifrostconnect.api_geo.dto.EvolucaoResponseDTO;
import com.bifrostconnect.api_geo.security.RbacService;
import com.bifrostconnect.api_geo.service.ComparacaoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ComparacaoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ComparacaoService comparacaoService;

    // Engana o RbacInterceptor para não barrar as rotas
    @MockBean
    private RbacService rbacService;

    @BeforeEach
    void setUp() {
        when(rbacService.requirePermission(any())).thenReturn(true);
    }

    @Test
    @WithMockUser(username = "admin@teste.local", authorities = {"COMPARAR_SAFRAS", "ROLE_ADMINISTRADOR"})
    void deveListarSafrasElegiveis() throws Exception {
        when(comparacaoService.listarSafrasElegiveis(anyString(), any(Long.class)))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/analise/comparacao/safras")
                        .param("conjunto", "1")
                        .param("idRegiao", "1"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin@teste.local", authorities = {"COMPARAR_SAFRAS", "ROLE_ADMINISTRADOR"})
    void deveCalcularEvolucaoGlobal() throws Exception {
        when(comparacaoService.calcularEvolucao(anyLong(), anyLong(), any(), any()))
                .thenReturn(new EvolucaoResponseDTO(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, false, ""));

        mockMvc.perform(post("/analise/comparacao/evolucao")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"safraAId\": 1, \"safraBId\": 2}"))
                .andExpect(status().isOk());
    }
}