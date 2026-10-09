package com.bifrostconnect.api_geo.integration;

import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class VersaoFechadaIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProcessoRepository processoRepository;

    @BeforeEach
    void setUp() {
        // Garante que o registro com ID 999 (simulando versão fechada com situacaoAtualId = 999L) 
        // exista no banco de testes preenchendo os campos obrigatórios (como dataCriacao)
        if (!processoRepository.existsById(999L)) {
            Processo processo = new Processo();
            processo.setId(999L);
            processo.setSituacaoAtualId(999L);
            processo.setDataCriacao(LocalDateTime.now());
            processo.setAnoSafra("2026");
            processoRepository.save(processo);
        }
    }

    @Test
    @DisplayName("Tarefa 9: Deve recusar com status de conflito (409) ao tentar atualizar uma versão/processo fechado")
    @WithMockUser(roles = "GESTOR")
    void deveRecusarAtualizacaoEmVersaoFechada() throws Exception {
        Long idProcessoFechado = 999L; 

        // Utiliza uma rota de edição existente (ex: PUT /processos/{id}/editor) para acionar o bloqueio de fechado
        mockMvc.perform(put("/processos/{id}/editor", idProcessoFechado)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"editorId\": 1}"))
                .andExpect(status().isConflict());
    }
}