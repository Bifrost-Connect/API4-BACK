package com.bifrostconnect.api_geo.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
class RbacSegurancaIntegrationTest {

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
    @DisplayName("Tarefa 14: rota protegida sem credencial devolve 401")
    void semCredencialDeveDar401() throws Exception {
        mockMvc.perform(get("/dominios/filtros")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/processos/metricas")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/usuarios")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Tarefa 14: token adulterado devolve 401")
    void tokenAdulteradoDeveDar401() throws Exception {
        String token = login(criarUsuario("ANALISTA"));
        String adulterado = token.substring(0, token.length() - 3) + "abc";

        mockMvc.perform(comToken(get("/dominios/filtros"), adulterado))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Tarefa 14: Basic do front atual continua funcionando e também passa pelo RBAC")
    void basicDoFrontContinuaFuncionando() throws Exception {
        mockMvc.perform(get("/dominios/filtros").with(httpBasic("analista", "Troca123")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/carga/metadados").with(httpBasic("analista", "Troca123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/dominios/filtros").with(httpBasic("analista", "senha-errada")))
                .andExpect(status().isUnauthorized());
    }

    @Nested
    @DisplayName("Tarefa 15: Administrador")
    class Administrador {

        @Test
        @WithMockUser(roles = "ADMINISTRADOR")
        @DisplayName("não sobe carga, não aprova, não fecha versão e não mexe em dado territorial")
        void administradorRecebe403() throws Exception {
            mockMvc.perform(post("/carga/metadados").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/carga/1/decisao-gestor").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/publicacao/1/aprovar-tratamento").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch("/api/indicadores/1/fechar").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/carga/referencias/importar"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "ADMINISTRADOR")
        @DisplayName("continua administrando usuários")
        void administradorGerenciaUsuarios() throws Exception {
            mockMvc.perform(get("/admin/usuarios")).andExpect(status().isOk());
            mockMvc.perform(get("/admin/perfis-permissoes")).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("Tarefa 15: Auditor")
    class Auditor {

        @Test
        @WithMockUser(roles = "AUDITOR")
        @DisplayName("não faz mutação nem baixa arquivo/relatório")
        void auditorRecebe403() throws Exception {
            mockMvc.perform(patch("/carga/1/cancelar").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/carga/1/quarentena/notificar-operador"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/carga/download/1"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/carga/1/rejeicoes/exportar"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/indicadores/1/exportar"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/carga/1/decisao-gestor").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "AUDITOR")
        @DisplayName("continua consultando")
        void auditorConsulta() throws Exception {
            mockMvc.perform(get("/processos/metricas")).andExpect(status().isOk());
            mockMvc.perform(get("/dominios/filtros")).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("Tarefa 15: Operador")
    class Operador {

        @Test
        @WithMockUser(roles = "OPERADOR")
        @DisplayName("sem exceção SoD não edita geometria, não aprova e não publica")
        void operadorRecebe403() throws Exception {
            mockMvc.perform(post("/carga/1/decisao-gestor").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/publicacao/1/aprovar-tratamento").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch("/api/indicadores/1/fechar").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/carga/referencias/importar"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("com excecao_sod_autorizada passa pela autorização e grava ALERTA_SOD na auditoria")
        void operadorComExcecaoSodGeraAlerta() throws Exception {
            Long operadorId = criarUsuario("OPERADOR");
            jdbc.update("UPDATE usuario SET excecao_sod_autorizada = TRUE, justificativa_sod = 'Plantão sem gestor disponível' WHERE id = ?", operadorId);
            String token = login(operadorId);

            int alertasAntes = contarAlertasSod(operadorId);

            int status = mockMvc.perform(comToken(post("/publicacao/{id}/aprovar-tratamento", Long.MAX_VALUE), token)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getStatus();

            assertNotEquals(403, status);
            assertNotEquals(401, status);
            assertEquals(alertasAntes + 1, contarAlertasSod(operadorId));
        }
    }

    @Nested
    @DisplayName("Tarefa 15: Analista")
    class Analista {

        @Test
        @WithMockUser(roles = "ANALISTA")
        @DisplayName("não acessa quarentena, não exclui base e não publica")
        void analistaRecebe403() throws Exception {
            mockMvc.perform(get("/carga/1/quarentena/exportar"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch("/carga/1/cancelar").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch("/api/indicadores/1/fechar").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/carga/1/decisao-gestor").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/processos/1/log-espacial"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("Tarefa 14: perfil que vale é o do banco, não o que veio no token")
    void perfilAtualDoBancoPrevalece() throws Exception {
        Long usuarioId = criarUsuario("ANALISTA");
        String token = login(usuarioId);

        jdbc.update("UPDATE usuario SET perfil_id = (SELECT id FROM perfil WHERE nome = 'AUDITOR') WHERE id = ?", usuarioId);

        mockMvc.perform(comToken(get("/processos/metricas"), token)).andExpect(status().isOk());
        mockMvc.perform(comToken(get("/admin/usuarios"), token)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Tarefa 16: colaborador inativado perde o token na hora (401)")
    void inativacaoRevogaToken() throws Exception {
        Long analistaId = criarUsuario("ANALISTA");
        String tokenAnalista = login(analistaId);
        String tokenAdmin = login(criarUsuario("ADMINISTRADOR"));

        mockMvc.perform(comToken(get("/dominios/filtros"), tokenAnalista)).andExpect(status().isOk());

        mockMvc.perform(comToken(patch("/admin/usuarios/{id}/status", analistaId), tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ativo\": false}"))
                .andExpect(status().isOk());

        mockMvc.perform(comToken(get("/dominios/filtros"), tokenAnalista))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.erro").exists());
    }

    @Test
    @DisplayName("Tarefa 16: troca de perfil derruba o token emitido antes (401) e exige login novo")
    void trocaDePerfilRevogaToken() throws Exception {
        Long usuarioId = criarUsuario("OPERADOR");
        String tokenAntigo = login(usuarioId);
        String tokenAdmin = login(criarUsuario("ADMINISTRADOR"));

        mockMvc.perform(comToken(put("/admin/usuarios/{id}/permissoes", usuarioId), tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perfilId\": " + perfilId("ANALISTA") + "}"))
                .andExpect(status().isOk());

        mockMvc.perform(comToken(get("/dominios/filtros"), tokenAntigo)).andExpect(status().isUnauthorized());

        mockMvc.perform(comToken(get("/dominios/filtros"), login(usuarioId))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Tarefa 16: usuário inativo não consegue nem logar")
    void usuarioInativoNaoLoga() throws Exception {
        Long usuarioId = criarUsuario("ANALISTA");
        jdbc.update("UPDATE usuario SET ativo = FALSE WHERE id = ?", usuarioId);

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email(usuarioId) + "\", \"senha\": \"" + SENHA + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Tarefa 17: Administrador não altera o próprio cargo nem o próprio status (403)")
    void adminNaoAlteraASiMesmo() throws Exception {
        Long adminId = criarUsuario("ADMINISTRADOR");
        criarUsuario("ADMINISTRADOR");
        String token = login(adminId);

        mockMvc.perform(comToken(patch("/admin/usuarios/{id}/status", adminId), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ativo\": false}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(comToken(put("/admin/usuarios/{id}/permissoes", adminId), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perfilId\": " + perfilId("GESTOR") + "}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin-fora-do-banco", roles = "ADMINISTRADOR")
    @DisplayName("Tarefa 17: último Administrador ativo não pode ser inativado nem trocar de cargo (409)")
    void ultimoAdminProtegido() throws Exception {
        Long ultimoAdmin = criarUsuario("ADMINISTRADOR");
        jdbc.update("""
                UPDATE usuario SET ativo = FALSE
                 WHERE perfil_id = (SELECT id FROM perfil WHERE nome = 'ADMINISTRADOR') AND id <> ?
                """, ultimoAdmin);

        mockMvc.perform(patch("/admin/usuarios/{id}/status", ultimoAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ativo\": false}"))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/admin/usuarios/{id}/permissoes", ultimoAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perfilId\": " + perfilId("GESTOR") + "}"))
                .andExpect(status().isConflict());

        criarUsuario("ADMINISTRADOR");
        mockMvc.perform(put("/admin/usuarios/{id}/permissoes", ultimoAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perfilId\": " + perfilId("GESTOR") + "}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin-fora-do-banco", roles = "ADMINISTRADOR")
    @DisplayName("Tarefa 15: exceção SoD exige justificativa de pelo menos 10 caracteres e fica gravada no usuário")
    void excecaoSodGravaNoUsuario() throws Exception {
        Long operadorId = criarUsuario("OPERADOR");
        String url = "/admin/usuarios/{id}/permissoes";
        String perfilOperador = perfilId("OPERADOR").toString();

        mockMvc.perform(put(url, operadorId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perfilId\": " + perfilOperador + ", \"excecaoSoD\": true, \"justificativa\": \"curta\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(url, operadorId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perfilId\": " + perfilOperador + ", \"excecaoSoD\": true, \"justificativa\": \"Cobertura de férias do gestor\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excecao_sod_autorizada").value(true));
    }

    private Long criarUsuario(String perfil) {
        String sufixo = String.valueOf(ThreadLocalRandom.current().nextInt(100_000_000, 999_999_999));
        return jdbc.queryForObject("""
                INSERT INTO usuario (nome, email, senha_hash, perfil_id, ativo)
                VALUES (?, ?, ?, (SELECT id FROM perfil WHERE nome = ?), TRUE)
                RETURNING id
                """, Long.class, "Teste " + perfil, "rbac." + sufixo + "@teste.com", passwordEncoder.encode(SENHA), perfil);
    }

    private String email(Long usuarioId) {
        return jdbc.queryForObject("SELECT email FROM usuario WHERE id = ?", String.class, usuarioId);
    }

    private Long perfilId(String nome) {
        return jdbc.queryForObject("SELECT id FROM perfil WHERE nome = ?", Long.class, nome);
    }

    private int contarAlertasSod(Long usuarioId) {
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auditoria WHERE usuario_id = ? AND acao = 'ALERTA_SOD'", Integer.class, usuarioId);
        return total == null ? 0 : total;
    }

    private String login(Long usuarioId) throws Exception {
        String resposta = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email(usuarioId) + "\", \"senha\": \"" + SENHA + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(resposta);
        return json.get("token").asText();
    }

    private MockHttpServletRequestBuilder comToken(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }
}