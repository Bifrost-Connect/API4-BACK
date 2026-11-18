package com.bifrostconnect.api_geo.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@Transactional
class PermissaoUsuarioCustomizadaIntegrationTest {

    private static final String SENHA = "Senha@Forte123";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("Conceder CARGA_ENVIAR a ANALISTA revoga token antigo, atualiza login e libera POST /carga/metadados")
    void concederPermissaoEspecificaParaAnalista() throws Exception {
        Long analistaId = criarUsuario("ANALISTA");
        Long adminId = criarUsuario("ADMINISTRADOR");
        String tokenAntigo = tokenDo(login(email(analistaId), SENHA));
        String tokenAdmin = tokenDo(login(email(adminId), SENHA));

        mockMvc.perform(comToken(post("/carga/metadados"), tokenAntigo)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        String bodyPermissoes = objectMapper.writeValueAsString(Map.of(
                "permissoesCustomizadas", List.of("ANALISE_EXECUTAR", "CARGA_ENVIAR")));

        mockMvc.perform(comToken(put("/admin/usuarios/{id}/permissoes", analistaId), tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(bodyPermissoes))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perfil").value("ANALISTA"))
                .andExpect(jsonPath("$.permissoes_customizadas[0]").value("ANALISE_EXECUTAR"))
                .andExpect(jsonPath("$.permissoes_customizadas[1]").value("CARGA_ENVIAR"));

        // Token antigo deve ser invalidado imediatamente (401)
        mockMvc.perform(comToken(get("/dominios/filtros"), tokenAntigo))
                .andExpect(status().isUnauthorized());

        JsonNode loginNovo = login(email(analistaId), SENHA);
        String tokenNovo = tokenDo(loginNovo);
        Set<String> permissoes = extrairSet(loginNovo.get("usuario").get("permissoes"));
        Set<String> customizadas = extrairSet(loginNovo.get("usuario").get("permissoes_customizadas"));

        assertTrue(permissoes.contains("CARGA_ENVIAR"), "Login novo deve incluir CARGA_ENVIAR: " + permissoes);
        assertTrue(customizadas.contains("CARGA_ENVIAR"));
        assertTrue(customizadas.contains("ANALISE_EXECUTAR"));
        assertFalse(loginNovo.get("usuario").get("excecao_sod_autorizada").asBoolean());

        int statusCarga = mockMvc.perform(comToken(post("/carga/metadados"), tokenNovo)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();
        assertNotEquals(401, statusCarga);
        assertNotEquals(403, statusCarga);

        mockMvc.perform(comToken(get("/admin/usuarios"), tokenAdmin).param("email", email(analistaId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].permissoes_customizadas[0]").value("ANALISE_EXECUTAR"))
                .andExpect(jsonPath("$.content[0].permissoes_customizadas[1]").value("CARGA_ENVIAR"));
    }

    @Test
    @DisplayName("Revogar COMPARAR_SAFRAS de ANALISTA bloqueia POST /analise/comparacao/evolucao com 403")
    void revogarPermissaoPadraoDoAnalista() throws Exception {
        Long analistaId = criarUsuario("ANALISTA");
        String tokenAdmin = tokenDo(login(email(criarUsuario("ADMINISTRADOR")), SENHA));

        String tokenInicial = tokenDo(login(email(analistaId), SENHA));
        int statusAntes = mockMvc.perform(comToken(post("/analise/comparacao/evolucao"), tokenInicial)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();
        assertNotEquals(401, statusAntes);
        assertNotEquals(403, statusAntes);

        String bodySemComparar = objectMapper.writeValueAsString(Map.of(
                "permissoesCustomizadas", List.of("MAPA_CONSULTAR", "CATALOGO_CONSULTAR")));

        mockMvc.perform(comToken(put("/admin/usuarios/{id}/permissoes", analistaId), tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(bodySemComparar))
                .andExpect(status().isOk());

        JsonNode loginAtualizado = login(email(analistaId), SENHA);
        Set<String> permissoes = extrairSet(loginAtualizado.get("usuario").get("permissoes"));
        assertFalse(permissoes.contains("COMPARAR_SAFRAS"));

        mockMvc.perform(comToken(post("/analise/comparacao/evolucao"), tokenDo(loginAtualizado))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Restaurar padrões (resetarPermissoes=true ou permissoesCustomizadas=null) devolve permissões do perfil")
    void restaurarPermissoesPadraoDoPerfil() throws Exception {
        Long analistaId = criarUsuario("ANALISTA");
        String tokenAdmin = tokenDo(login(email(criarUsuario("ADMINISTRADOR")), SENHA));

        mockMvc.perform(comToken(put("/admin/usuarios/{id}/permissoes", analistaId), tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("permissoesCustomizadas", List.of("CARGA_ENVIAR")))))
                .andExpect(status().isOk());

        mockMvc.perform(comToken(put("/admin/usuarios/{id}/permissoes", analistaId), tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resetarPermissoes\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissoes_customizadas").isEmpty());

        JsonNode loginRestaurado = login(email(analistaId), SENHA);
        assertTrue(loginRestaurado.get("usuario").get("permissoes_customizadas").isNull());

        Set<String> permissoes = extrairSet(loginRestaurado.get("usuario").get("permissoes"));
        assertTrue(permissoes.contains("COMPARAR_SAFRAS"));
        assertFalse(permissoes.contains("CARGA_ENVIAR"));

        String tokenRestaurado = tokenDo(loginRestaurado);
        int statusComparar = mockMvc.perform(comToken(post("/analise/comparacao/evolucao"), tokenRestaurado)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();
        assertNotEquals(401, statusComparar);
        assertNotEquals(403, statusComparar);

        mockMvc.perform(comToken(post("/carga/metadados"), tokenRestaurado)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        // Também valida restauração enviando permissoesCustomizadas: null e troca de perfil limpando override
        mockMvc.perform(comToken(put("/admin/usuarios/{id}/permissoes", analistaId), tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("permissoesCustomizadas", List.of("CARGA_ENVIAR")))))
                .andExpect(status().isOk());

        mockMvc.perform(comToken(put("/admin/usuarios/{id}/permissoes", analistaId), tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissoesCustomizadas\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissoes_customizadas").isEmpty());

        JsonNode loginNullReset = login(email(analistaId), SENHA);
        assertTrue(loginNullReset.get("usuario").get("permissoes_customizadas").isNull());
        assertFalse(extrairSet(loginNullReset.get("usuario").get("permissoes")).contains("CARGA_ENVIAR"));

        // Troca de perfil sem permissoesCustomizadas no body também limpa override anterior
        mockMvc.perform(comToken(put("/admin/usuarios/{id}/permissoes", analistaId), tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("permissoesCustomizadas", List.of("CARGA_ENVIAR")))))
                .andExpect(status().isOk());

        Long auditorPerfilId = jdbc.queryForObject("SELECT id FROM perfil WHERE nome = 'AUDITOR'", Long.class);
        mockMvc.perform(comToken(put("/admin/usuarios/{id}/permissoes", analistaId), tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perfilId\": " + auditorPerfilId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perfil").value("AUDITOR"))
                .andExpect(jsonPath("$.permissoes_customizadas").isEmpty());
    }

    @Test
    @DisplayName("AUDITOR permanece estritamente somente leitura mesmo com permissões de escrita customizadas")
    void auditorPermaneceSomenteLeituraMesmoComPermissoesCustomizadas() throws Exception {
        Long auditorId = criarUsuario("AUDITOR");
        String tokenAdmin = tokenDo(login(email(criarUsuario("ADMINISTRADOR")), SENHA));

        String bodyEscalada = objectMapper.writeValueAsString(Map.of(
                "permissoesCustomizadas", List.of("CARGA_ENVIAR", "CARGA_APROVAR", "ARQUIVO_BAIXAR", "CARGA_CONSULTAR")));

        mockMvc.perform(comToken(put("/admin/usuarios/{id}/permissoes", auditorId), tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(bodyEscalada))
                .andExpect(status().isOk());

        JsonNode loginAuditor = login(email(auditorId), SENHA);
        Set<String> permissoes = extrairSet(loginAuditor.get("usuario").get("permissoes"));
        assertFalse(permissoes.contains("CARGA_ENVIAR"));
        assertFalse(permissoes.contains("CARGA_APROVAR"));
        assertFalse(permissoes.contains("ARQUIVO_BAIXAR"));
        assertTrue(permissoes.contains("CARGA_CONSULTAR"));

        String tokenAuditor = tokenDo(loginAuditor);
        mockMvc.perform(comToken(get("/processos/metricas"), tokenAuditor)).andExpect(status().isOk());
        mockMvc.perform(comToken(post("/carga/metadados"), tokenAuditor)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(comToken(post("/carga/1/decisao-gestor"), tokenAuditor)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(comToken(get("/carga/download/1"), tokenAuditor))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Mapeamento MODULO:SUBPERMISSAO traduz códigos do front e não libera ARQUIVO_BAIXAR via BASES:VISUALIZAR")
    void mapeamentoModuloSubpermissaoERegraArquivoBaixar() throws Exception {
        Long analistaId = criarUsuario("ANALISTA");
        String tokenAdmin = tokenDo(login(email(criarUsuario("ADMINISTRADOR")), SENHA));

        mockMvc.perform(comToken(put("/admin/usuarios/{id}/permissoes", analistaId), tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "permissoesCustomizadas", List.of("INGESTAO:UPLOAD", "BASES:VISUALIZAR")))))
                .andExpect(status().isOk());

        JsonNode login1 = login(email(analistaId), SENHA);
        Set<String> permissoes1 = extrairSet(login1.get("usuario").get("permissoes"));
        assertTrue(permissoes1.contains("CARGA_ENVIAR"));
        assertFalse(permissoes1.contains("ARQUIVO_BAIXAR"));

        String token1 = tokenDo(login1);
        int statusMetadados = mockMvc.perform(comToken(post("/carga/metadados"), token1)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();
        assertNotEquals(403, statusMetadados);
        mockMvc.perform(comToken(get("/carga/download/1"), token1)).andExpect(status().isForbidden());

        mockMvc.perform(comToken(put("/admin/usuarios/{id}/permissoes", analistaId), tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "permissoesCustomizadas", List.of("BASES:BAIXAR_ARQUIVO")))))
                .andExpect(status().isOk());

        JsonNode login2 = login(email(analistaId), SENHA);
        Set<String> permissoes2 = extrairSet(login2.get("usuario").get("permissoes"));
        assertTrue(permissoes2.contains("ARQUIVO_BAIXAR"));
        int statusDownload = mockMvc.perform(comToken(get("/carga/download/1"), tokenDo(login2)))
                .andReturn().getResponse().getStatus();
        assertNotEquals(401, statusDownload);
        assertNotEquals(403, statusDownload);
    }

    @Test
    @DisplayName("OPERADOR com permissão customizada de aprovação e excecaoSoD grava ALERTA_SOD")
    void operadorComPermissaoCustomizadaSodGeraAlertaSod() throws Exception {
        Long operadorId = criarUsuario("OPERADOR");
        String tokenAdmin = tokenDo(login(email(criarUsuario("ADMINISTRADOR")), SENHA));

        mockMvc.perform(comToken(put("/admin/usuarios/{id}/permissoes", operadorId), tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "permissoesCustomizadas", List.of("BASES:HOMOLOGAR"),
                                "excecaoSoD", true,
                                "justificativa", "curta"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(comToken(put("/admin/usuarios/{id}/permissoes", operadorId), tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "permissoesCustomizadas", List.of("BASES:HOMOLOGAR", "CARGA_CONSULTAR"),
                                "excecaoSoD", true,
                                "justificativa", "Cobertura temporária de homologação"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excecao_sod_autorizada").value(true));

        String tokenOperador = tokenDo(login(email(operadorId), SENHA));
        Integer alertasAntes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auditoria WHERE usuario_id = ? AND acao = 'ALERTA_SOD'", Integer.class, operadorId);

        int statusAprovar = mockMvc.perform(comToken(post("/publicacao/{id}/aprovar-tratamento", Long.MAX_VALUE), tokenOperador)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();

        assertNotEquals(401, statusAprovar);
        assertNotEquals(403, statusAprovar);

        Integer alertasDepois = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auditoria WHERE usuario_id = ? AND acao = 'ALERTA_SOD'", Integer.class, operadorId);
        assertEquals((alertasAntes == null ? 0 : alertasAntes) + 1, alertasDepois);
    }

    @Test
    @WithMockUser(username = "admin-fora-do-banco", roles = "ADMINISTRADOR")
    @DisplayName("Último administrador ativo não pode perder USUARIO_GERENCIAR via permissões customizadas (409)")
    void ultimoAdminProtegidoQuandoCustomizacaoRemoveUsuarioGerenciar() throws Exception {
        Long ultimoAdmin = criarUsuario("ADMINISTRADOR");
        jdbc.update("""
                UPDATE usuario SET ativo = FALSE
                 WHERE perfil_id = (SELECT id FROM perfil WHERE nome = 'ADMINISTRADOR') AND id <> ?
                """, ultimoAdmin);

        mockMvc.perform(put("/admin/usuarios/{id}/permissoes", ultimoAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "permissoesCustomizadas", List.of("GOVERNANCA_CONSULTAR")))))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/admin/usuarios/{id}/permissoes", ultimoAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "permissoesCustomizadas", List.of("USUARIOS:VISUALIZAR", "GOVERNANCA_CONSULTAR")))))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "Demo {0} ({1}) autentica com Troca123")
    @DisplayName("Usuários canônicos de demonstração autenticam em POST /auth/login")
    @CsvSource({
            "operador@georural.com.br,   OPERADOR",
            "auditor@georural.com.br,    AUDITOR",
            "analista@georural.com.br,   ANALISTA",
            "admin@georural.com.br,      ADMINISTRADOR",
            "gestor@georural.com.br,     GESTOR",
            "integrador@georural.com.br, INTEGRADOR_EXTERNO"
    })
    void usuariosDemoCanonicosFazemLoginComSucesso(String emailDemo, String perfilEsperado) throws Exception {
        JsonNode resposta = login(emailDemo, "Troca123");
        assertFalse(tokenDo(resposta).isBlank());
        assertEquals(perfilEsperado, resposta.get("usuario").get("perfil").asText());
        assertFalse(extrairSet(resposta.get("usuario").get("permissoes")).isEmpty());
    }

    @Test
    @DisplayName("GET /admin/logs-seguranca retorna usuario_nome e usuario_email junto aos campos de auditoria")
    void logsSegurancaRetornamNomeEEmailDoUsuario() throws Exception {
        Long analistaId = criarUsuario("ANALISTA");
        String tokenAnalista = tokenDo(login(email(analistaId), SENHA));
        String tokenAdmin = tokenDo(login(email(criarUsuario("ADMINISTRADOR")), SENHA));

        mockMvc.perform(comToken(get("/admin/usuarios"), tokenAnalista)).andExpect(status().isForbidden());

        mockMvc.perform(comToken(get("/admin/logs-seguranca"), tokenAdmin)
                        .param("usuarioId", analistaId.toString())
                        .param("acao", "ACESSO_NEGADO_403"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].acao").value("ACESSO_NEGADO_403"))
                .andExpect(jsonPath("$.content[0].usuario_nome").value("Teste ANALISTA"))
                .andExpect(jsonPath("$.content[0].usuario_email").value(email(analistaId)));
    }

    @ParameterizedTest(name = "Subpermissão {0} concede CHAVE_API_GERENCIAR para ANALISTA, mas não para AUDITOR")
    @DisplayName("Subpermissões API:* liberam CHAVE_API_GERENCIAR exceto para AUDITOR")
    @CsvSource({
            "API:VISUALIZAR_CHAVES",
            "API:GERAR_CHAVE",
            "API:REVOGAR_CHAVE"
    })
    void subpermissoesApiChaveGerenciarRespeitamTravaDoAuditor(String subpermissao) throws Exception {
        Long analistaId = criarUsuario("ANALISTA");
        Long auditorId = criarUsuario("AUDITOR");
        String tokenAdmin = tokenDo(login(email(criarUsuario("ADMINISTRADOR")), SENHA));

        mockMvc.perform(comToken(put("/admin/usuarios/{id}/permissoes", analistaId), tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "permissoesCustomizadas", List.of(subpermissao)))))
                .andExpect(status().isOk());

        JsonNode loginAnalista = login(email(analistaId), SENHA);
        assertTrue(extrairSet(loginAnalista.get("usuario").get("permissoes")).contains("CHAVE_API_GERENCIAR"));
        mockMvc.perform(comToken(get("/admin/chaves-api"), tokenDo(loginAnalista)))
                .andExpect(status().isOk());

        mockMvc.perform(comToken(put("/admin/usuarios/{id}/permissoes", auditorId), tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "permissoesCustomizadas", List.of(subpermissao)))))
                .andExpect(status().isOk());

        JsonNode loginAuditor = login(email(auditorId), SENHA);
        assertFalse(extrairSet(loginAuditor.get("usuario").get("permissoes")).contains("CHAVE_API_GERENCIAR"));
        mockMvc.perform(comToken(get("/admin/chaves-api"), tokenDo(loginAuditor)))
                .andExpect(status().isForbidden());
    }

    private Long criarUsuario(String perfil) {
        String sufixo = String.valueOf(ThreadLocalRandom.current().nextInt(100_000_000, 999_999_999));
        return jdbc.queryForObject("""
                INSERT INTO usuario (nome, email, senha_hash, perfil_id, ativo)
                VALUES (?, ?, ?, (SELECT id FROM perfil WHERE nome = ?), TRUE)
                RETURNING id
                """, Long.class, "Teste " + perfil, "custom." + sufixo + "@teste.com", passwordEncoder.encode(SENHA), perfil);
    }

    private String email(Long usuarioId) {
        return jdbc.queryForObject("SELECT email FROM usuario WHERE id = ?", String.class, usuarioId);
    }

    private JsonNode login(String email, String senha) throws Exception {
        String resposta = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "senha", senha))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resposta);
    }

    private String tokenDo(JsonNode loginJson) {
        return loginJson.get("token").asText();
    }

    private Set<String> extrairSet(JsonNode arrayNode) {
        Set<String> set = new HashSet<>();
        if (arrayNode != null && arrayNode.isArray()) {
            arrayNode.forEach(item -> set.add(item.asText()));
        }
        return set;
    }

    private MockHttpServletRequestBuilder comToken(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }
}
