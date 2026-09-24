package com.bifrostconnect.api_geo.integration;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CargaIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final RestTemplate restTemplate = new RestTemplate();

    @BeforeEach
    public void setUp() {
        // 1. Insere o Órgão com ID 1
        jdbcTemplate.update("""
            INSERT INTO orgao (id, nome, sigla, ativo) 
            VALUES (1, 'Órgão Teste', 'OT', true) 
            ON CONFLICT (id) DO NOTHING;
        """);

        // 2. Insere o Conjunto com ID 1
        jdbcTemplate.update("""
            INSERT INTO conjunto (id, nome, ativo) 
            VALUES (1, 'Conjunto Teste', true) 
            ON CONFLICT (id) DO NOTHING;
        """);

        // 3. Insere o Perfil com ID 1
        jdbcTemplate.update("""
            INSERT INTO perfil (id, nome, descricao) 
            VALUES (1, 'OPERADOR', 'Responsavel pela entrada das cargas') 
            ON CONFLICT (id) DO NOTHING;
        """);

        // 4. Insere o Usuário com ID 1
        jdbcTemplate.update("""
            INSERT INTO usuario (id, nome, email, senha_hash, perfil_id, ativo) 
            VALUES (1, 'Operador Teste', 'teste@bifrostconnect.com', 'hash_exemplo', 1, true) 
            ON CONFLICT (id) DO NOTHING;
        """);
    }

    @AfterEach
    public void tearDown() {
        // Limpa os dados inseridos para não afetar outros testes
        jdbcTemplate.update("DELETE FROM processo WHERE operador_id = 1;");
        jdbcTemplate.update("DELETE FROM usuario WHERE id = 1;");
        jdbcTemplate.update("DELETE FROM perfil WHERE id = 1;");
        jdbcTemplate.update("DELETE FROM conjunto WHERE id = 1;");
        jdbcTemplate.update("DELETE FROM orgao WHERE id = 1;");
    }

    @Test
    public void deveCriarMetadadosEEncontrarNaListagem() {
        String jsonRequest = """
                {
                    "orgaoId": 1,
                    "conjuntoId": 1,
                    "operadorId": 1,
                    "anoSafra": "2024",
                    "epsgOrigem": "4326"
                }
                """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(jsonRequest, headers);

        String urlPost = "http://localhost:" + port + "/carga/metadados";
        
        try {
            ResponseEntity<String> postResponse = restTemplate.postForEntity(urlPost, request, String.class);
            assertNotNull(postResponse.getBody());

            Integer idGerado = JsonPath.read(postResponse.getBody(), "$.id");
            assertNotNull(idGerado);

            String urlGet = "http://localhost:" + port + "/processos/" + idGerado;
            ResponseEntity<String> getResponse = restTemplate.getForEntity(urlGet, String.class);
            
            assertEquals(200, getResponse.getStatusCode().value());
            
            // Alterado para "$.ano" conforme retornado pela API
            String anoRetornado = JsonPath.read(getResponse.getBody(), "$.ano");
            assertEquals("2024", anoRetornado);

        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            System.err.println("RESPOSTA DE ERRO DO SERVIDOR: " + e.getResponseBodyAsString());
            throw e;
        }
    }
}