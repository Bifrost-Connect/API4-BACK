package com.bifrostconnect.api_geo.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class LinhagemIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Tarefa 9: Deve retornar a linhagem completa contendo o Hash SHA-256 exato da ingestão")
    @WithMockUser(roles = "AUDITOR")
    @Sql(statements = {
        // Insere um cenário válido no banco em memória H2/PostgreSQL para o teste preenchendo as colunas obrigatórias
        "INSERT INTO processo (id, orgao_id, operador_id, conjunto_id, situacao_atual_id, etapa_atual_id, data_criacao, ano_safra) VALUES (999, 1, 1, 1, 5, 5, CURRENT_TIMESTAMP, '2026') ON CONFLICT DO NOTHING;",
        "INSERT INTO arquivo_original (id, processo_id, hash_sha256, nome_original) VALUES (999, 999, 'A1B2C3D4E5F6G7H8I9J0', 'teste.geojson') ON CONFLICT DO NOTHING;"
    })
    void deveRetornarLinhagemComHashExato() throws Exception {
        
        mockMvc.perform(get("/api/v1/indicadores/999/linhagem"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indicadorId").value(999))
                .andExpect(jsonPath("$.nomeArquivoOriginal").value("teste.geojson"))
                // Valida a garantia principal: o Hash SHA-256 (Tarefa 9)
                .andExpect(jsonPath("$.hashSha256").value("A1B2C3D4E5F6G7H8I9J0"));
    }

    @Test
    @DisplayName("Tarefa 8: Deve retornar Status 404 (Not Found) quando indicador não existir")
    @WithMockUser(roles = "AUDITOR")
    void deveRetornar404ParaIndicadorInexistente() throws Exception {
        
        mockMvc.perform(get("/api/v1/indicadores/99999/linhagem"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Tarefa 8: Deve retornar 403 (Forbidden) se Auditor tentar fazer um POST")
    @WithMockUser(roles = "AUDITOR") // Perfil Auditor simulado
    void deveBloquearMetodosDeEscritaParaAuditor() throws Exception {
        
        // Tenta acionar uma rota de processamento restrita usando POST
        mockMvc.perform(post("/processos/1/processar")
                .param("nomeArquivo", "teste.geojson")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                // Espera ser barrado pelo SecurityConfig
                .andExpect(status().isForbidden());
    }
}