package com.bifrostconnect.api_geo.integration;

import com.bifrostconnect.api_geo.controller.ProcessoController;
import com.bifrostconnect.api_geo.repository.ArquivoOriginalRepository;
import com.bifrostconnect.api_geo.service.ProcessoDashboardService;
import com.bifrostconnect.api_geo.service.ProcessoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProcessoController.class)
public class VersaoFechadaIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProcessoService processoService;

    @MockitoBean
    private ProcessoDashboardService processoDashboardService;

    @MockitoBean
    private ArquivoOriginalRepository arquivoOriginalRepository;

    @Test
    @DisplayName("Tarefa 9: Deve recusar com status de conflito (409) ao tentar atualizar uma versão/processo fechado")
    @WithMockUser(roles = "GESTOR")
    void deveRecusarAtualizacaoEmVersaoFechada() throws Exception {
        Long idProcessoFechado = 999L;

        doThrow(new IllegalStateException("Não é permitido alterar um processo fechado."))
                .when(processoService)
                .alocarEditorEAtualizarSituacao(eq(idProcessoFechado), anyLong());

        mockMvc.perform(put("/processos/{id}/editor", idProcessoFechado)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"editorId\": 1}"))
                .andExpect(status().isConflict());
    }
}
