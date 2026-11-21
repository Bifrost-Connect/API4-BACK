package com.bifrostconnect.api_geo.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.bifrostconnect.api_geo.service.ChaveApiCriptoService;
import com.bifrostconnect.api_geo.service.ChaveApiCriptoService.ChaveGerada;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// US12 - Tarefa 18 (e as respostas da Tarefa 16) de ponta a ponta: login de verdade (JWT), emissão
// pela rota, consumo com X-API-Key e revogação, tudo pela cadeia completa do Spring Security.
// As URLs são as reais (/api/...), com o servlet path /api igual em produção. Volta tudo no rollback
@SpringBootTest(properties = "seguranca.jwt.segredo=" + RbacMatrizCruzadaIntegrationTest.SEGREDO)
@Transactional
class RevogacaoImediataApiKeyIntegrationTest {

    private static final String SENHA = "Senha@Forte123";
    private static final String CATALOGO = "/api/v1/externo/catalogo";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChaveApiCriptoService cripto;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        for (String perfil : new String[] {"ADMINISTRADOR", "INTEGRADOR_EXTERNO"}) {
            jdbc.update("INSERT INTO perfil (nome) SELECT ? WHERE NOT EXISTS (SELECT 1 FROM perfil WHERE nome = ?)",
                    perfil, perfil);
        }
    }

    @Test
    @DisplayName("Tarefa 18: emitir -> GET catálogo 200 -> PATCH revogar -> mesma chamada na hora dá 401")
    void revogacaoBloqueiaNaHora() throws Exception {
        String integrador = login(criarUsuario("INTEGRADOR_EXTERNO"));

        // 1. emite a chave
        JsonNode emitida = emitir(integrador, "Parceiro Revogação", "CATALOGO");
        long chaveId = emitida.get("id").asLong();
        String apiKey = emitida.get("chave").asText();

        // 2. consumo externo -> 200 (a segunda chamada já sai do cache em memória)
        mockMvc.perform(api(get(CATALOGO)).header("X-API-Key", apiKey))
                .andExpect(status().isOk());
        mockMvc.perform(api(get(CATALOGO)).header("X-API-Key", apiKey))
                .andExpect(status().isOk());

        // 3. revoga
        mockMvc.perform(comToken(api(patch("/api/integracoes/chaves/{id}/revogar", chaveId)), integrador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOGADA"))
                .andExpect(jsonPath("$.revogada_agora").value(true));

        // 4. repete IMEDIATAMENTE a mesma chamada -> 401, sem esperar o TTL do cache
        mockMvc.perform(api(get(CATALOGO)).header("X-API-Key", apiKey))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("ApiKey")))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.codigo").value("API_KEY_REVOGADA"))
                .andExpect(jsonPath("$.caminho").value(CATALOGO));

        // e continua bloqueada nas próximas
        mockMvc.perform(api(get(CATALOGO)).header("X-API-Key", apiKey))
                .andExpect(status().isUnauthorized());

        // Tarefa 17 no banco de verdade: a chave em texto não aparece em nenhuma coluna nem na auditoria
        Integer linhasComTextoPuro = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM chaves_api c WHERE row_to_json(c)::text LIKE '%' || ? || '%')
                     + (SELECT count(*) FROM auditoria a WHERE row_to_json(a)::text LIKE '%' || ? || '%')
                """, Integer.class, apiKey, apiKey);
        assertThat(linhasComTextoPuro).isZero();
        assertThat(jdbc.queryForObject("SELECT hash_chave_sha256 FROM chaves_api WHERE id = ?", String.class, chaveId))
                .isEqualTo(cripto.hashSha256(apiKey));
    }

    @Test
    @DisplayName("Tarefa 16: integrador não revoga nem lista chave de outro usuário (403); o Administrador revoga")
    void integradorNaoRevogaChaveDeOutro() throws Exception {
        String dono = login(criarUsuario("INTEGRADOR_EXTERNO"));
        String outroIntegrador = login(criarUsuario("INTEGRADOR_EXTERNO"));
        String admin = login(criarUsuario("ADMINISTRADOR"));

        JsonNode emitida = emitir(dono, "Parceiro do Dono", "CATALOGO");
        long chaveId = emitida.get("id").asLong();
        String apiKey = emitida.get("chave").asText();

        // outro integrador -> 403 no formato padrão, e a chave continua valendo
        mockMvc.perform(comToken(api(patch("/api/integracoes/chaves/{id}/revogar", chaveId)), outroIntegrador))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.codigo").value("CHAVE_DE_OUTRO_USUARIO"));

        assertThat(jdbc.queryForObject("SELECT status FROM chaves_api WHERE id = ?", String.class, chaveId))
                .isEqualTo("ATIVA");
        mockMvc.perform(api(get(CATALOGO)).header("X-API-Key", apiKey))
                .andExpect(status().isOk());

        // a listagem do outro integrador não mostra a chave do dono
        mockMvc.perform(comToken(api(get("/api/integracoes/chaves")), outroIntegrador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + chaveId + ")]").doesNotExist());
        mockMvc.perform(comToken(api(get("/api/integracoes/chaves")), dono))
                .andExpect(jsonPath("$.content[?(@.id == " + chaveId + ")]").exists());

        // Administrador pode revogar a chave de qualquer um
        mockMvc.perform(comToken(api(patch("/api/integracoes/chaves/{id}/revogar", chaveId)), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOGADA"));

        mockMvc.perform(api(get(CATALOGO)).header("X-API-Key", apiKey))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Tarefa 16: sem header, inexistente e expirada -> 401 com código; sem escopo -> 403 ESCOPO_INSUFICIENTE")
    void respostasPadronizadasDaApiExterna() throws Exception {
        Long integradorId = criarUsuario("INTEGRADOR_EXTERNO");
        String integrador = login(integradorId);

        mockMvc.perform(api(get(CATALOGO)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("API_KEY_AUSENTE"));

        mockMvc.perform(api(get(CATALOGO)).header("X-API-Key", cripto.gerar().chaveAberta()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("API_KEY_INVALIDA"));

        // a rota de emissão não aceita data passada, então a chave vencida entra direto no banco
        ChaveGerada vencida = cripto.gerar();
        jdbc.update("""
                INSERT INTO chaves_api (usuario_id, nome_parceiro, prefixo_chave, hash_chave_sha256, escopos, status, data_expiracao)
                VALUES (?, 'Parceiro Vencido', ?, ?, '["CATALOGO"]'::jsonb, 'ATIVA', ?)
                """, integradorId, vencida.prefixoVisivel(), vencida.hashSha256(),
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)));

        mockMvc.perform(api(get(CATALOGO)).header("X-API-Key", vencida.chaveAberta()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("API_KEY_EXPIRADA"));

        // chave só com CATALOGO tentando o GeoJSON -> 403 (o escopo é checado antes de olhar a base)
        String soCatalogo = emitir(integrador, "Parceiro Só Catálogo", "CATALOGO").get("chave").asText();
        mockMvc.perform(api(get("/api/v1/externo/bases/{id}/geojson", Long.MAX_VALUE)).header("X-API-Key", soCatalogo))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.codigo").value("ESCOPO_INSUFICIENTE"))
                .andExpect(jsonPath("$.erro").isNotEmpty());
    }

    // ------------------------------------------------------------------

    // igual em produção: spring.mvc.servlet.path=/api
    private MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder request) {
        return request.servletPath("/api");
    }

    private JsonNode emitir(String token, String parceiro, String escopo) throws Exception {
        String resposta = mockMvc.perform(comToken(api(post("/api/integracoes/chaves")), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nomeParceiro\": \"" + parceiro + "\", \"escopos\": [\"" + escopo + "\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resposta);
    }

    private Long criarUsuario(String perfil) {
        String sufixo = String.valueOf(ThreadLocalRandom.current().nextInt(100_000_000, 999_999_999));
        return jdbc.queryForObject("""
                INSERT INTO usuario (nome, email, senha_hash, perfil_id, ativo)
                VALUES (?, ?, ?, (SELECT id FROM perfil WHERE nome = ? LIMIT 1), TRUE)
                RETURNING id
                """, Long.class, "Teste " + perfil, "chaves." + sufixo + "@teste.com", passwordEncoder.encode(SENHA), perfil);
    }

    private String login(Long usuarioId) throws Exception {
        String email = jdbc.queryForObject("SELECT email FROM usuario WHERE id = ?", String.class, usuarioId);
        String resposta = mockMvc.perform(api(post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"senha\": \"" + SENHA + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resposta).get("token").asText();
    }

    private MockHttpServletRequestBuilder comToken(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }
}
