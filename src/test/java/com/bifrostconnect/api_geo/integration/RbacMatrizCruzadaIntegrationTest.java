package com.bifrostconnect.api_geo.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.bifrostconnect.api_geo.security.JwtService;
import com.bifrostconnect.api_geo.security.MatrizPermissoes;
import com.bifrostconnect.api_geo.security.Permissao;
import com.bifrostconnect.api_geo.security.RequirePermission;
import com.bifrostconnect.api_geo.security.RotaPublica;
import com.bifrostconnect.api_geo.security.UsuarioSessaoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "seguranca.jwt.segredo=" + RbacMatrizCruzadaIntegrationTest.SEGREDO)
@Transactional
class RbacMatrizCruzadaIntegrationTest {

    static final String SEGREDO = "segredo-dos-testes-cruzados-de-seguranca";

    private static final String SENHA = "Senha@Forte123";
    private static final List<String> PERFIS = List.of(
            "ADMINISTRADOR", "OPERADOR", "AUDITOR", "GESTOR", "ANALISTA", "INTEGRADOR_EXTERNO");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping rotas;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UsuarioSessaoService usuarioSessaoService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("Tarefa 18: 403 grava ACESSO_NEGADO_403 com usuário, endpoint, método e IP")
    void acessoNegadoVaiPraAuditoria() throws Exception {
        Long analista = criarUsuario("ANALISTA");

        mockMvc.perform(comToken(post("/carga/1/decisao-gestor"), login(analista))
                        .with(r -> {
                            r.setRemoteAddr("10.20.30.40");
                            return r;
                        }))
                .andExpect(status().isForbidden());

        Map<String, Object> evento = jdbc.queryForMap(
                "SELECT descricao, detalhes, ip FROM auditoria WHERE usuario_id = ? AND acao = 'ACESSO_NEGADO_403'",
                analista);
        String detalhes = evento.get("detalhes").toString();

        assertTrue(detalhes.contains("metodo=POST"), detalhes);
        assertTrue(detalhes.contains("endpoint=/carga/1/decisao-gestor"), detalhes);
        assertTrue(detalhes.contains("usuario=" + email(analista)), detalhes);
        assertTrue(evento.get("descricao").toString().contains(Permissao.CARGA_APROVAR.name()));
        assertEquals("10.20.30.40", evento.get("ip"));
    }

    @Test
    @DisplayName("Tarefa 18: usuário fora da tabela usuario também fica registrado, pelo login")
    void usuarioDeMemoriaTambemERegistrado() throws Exception {
        String login = "fora-do-banco-" + ThreadLocalRandom.current().nextInt(100_000_000, 999_999_999);

        mockMvc.perform(post("/carga/metadados")
                        .with(user(login).roles("ANALISTA")))
                .andExpect(status().isForbidden());

        Map<String, Object> evento = jdbc.queryForMap(
                "SELECT usuario_id, detalhes FROM auditoria WHERE acao = 'ACESSO_NEGADO_403' AND detalhes LIKE ?",
                "%usuario=" + login);
        assertNull(evento.get("usuario_id"));
    }

    @Test
    @DisplayName("Tarefa 18: rota permitida e 401 não geram ACESSO_NEGADO_403")
    void soOForbiddenEhRegistrado() throws Exception {
        Long analista = criarUsuario("ANALISTA");
        int antes = totalAcessosNegados();

        mockMvc.perform(comToken(get("/dominios/filtros"), login(analista))).andExpect(status().isOk());
        mockMvc.perform(post("/carga/metadados")).andExpect(status().isUnauthorized());
        mockMvc.perform(comToken(post("/carga/metadados"), "token-que-nao-vale"))
                .andExpect(status().isUnauthorized());

        assertEquals(antes, totalAcessosNegados());
    }

    @Test
    @DisplayName("Tarefa 18: a tentativa barrada aparece nos logs de segurança do Administrador")
    void tentativaApareceNosLogsDeSeguranca() throws Exception {
        Long analista = criarUsuario("ANALISTA");
        mockMvc.perform(comToken(get("/admin/usuarios"), login(analista))).andExpect(status().isForbidden());

        mockMvc.perform(comToken(get("/admin/logs-seguranca"), login(criarUsuario("ADMINISTRADOR")))
                        .param("usuarioId", analista.toString())
                        .param("acao", "ACESSO_NEGADO_403"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].acao").value("ACESSO_NEGADO_403"));
    }

    @Test
    @DisplayName("Tarefa 18: token vencido devolve 401")
    void tokenVencidoDeveDar401() throws Exception {
        Long analista = criarUsuario("ANALISTA");
        JwtService jaVencido = new JwtService(SEGREDO, -1, objectMapper);
        String token = jaVencido.gerar(usuarioSessaoService.buscarPorId(analista).orElseThrow()).token();

        mockMvc.perform(comToken(get("/dominios/filtros"), login(analista))).andExpect(status().isOk());
        mockMvc.perform(comToken(get("/dominios/filtros"), token)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Tarefa 18: e-mail já cadastrado devolve 409")
    void emailDuplicadoDeveDar409() throws Exception {
        Long operador = criarUsuario("OPERADOR");
        String corpo = objectMapper.writeValueAsString(Map.of(
                "nome", "Duplicado",
                "email", email(operador),
                "senha", SENHA,
                "perfilId", perfilId("OPERADOR")));

        mockMvc.perform(comToken(post("/admin/usuarios"), login(criarUsuario("ADMINISTRADOR")))
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isConflict());
    }

    @ParameterizedTest(name = "{0} em {1} {2} → 403")
    @DisplayName("Tarefa 19: investidas nas rotas críticas de cada módulo")
    @CsvSource({
            "ANALISTA,           POST,  /carga/metadados",
            "ANALISTA,           POST,  /carga/upload",
            "ANALISTA,           PATCH, /carga/1/cancelar",
            "ANALISTA,           GET,   /carga/1/quarentena/exportar",
            "ANALISTA,           GET,   /carga/download/1",
            "ANALISTA,           PATCH, /api/indicadores/1/fechar",
            "OPERADOR,           PATCH, /api/indicadores/1/fechar",
            "OPERADOR,           POST,  /publicacao/1/aprovar-tratamento",
            "OPERADOR,           POST,  /carga/1/decisao-gestor",
            "OPERADOR,           POST,  /carga/referencias/importar",
            "ADMINISTRADOR,      POST,  /carga/1/decisao-gestor",
            "ADMINISTRADOR,      POST,  /carga/metadados",
            "ADMINISTRADOR,      PATCH, /api/indicadores/1/fechar",
            "ADMINISTRADOR,      POST,  /publicacao/1/aprovar-tratamento",
            "AUDITOR,            GET,   /api/indicadores/1/exportar",
            "AUDITOR,            GET,   /carga/download/1",
            "AUDITOR,            GET,   /carga/1/quarentena/exportar",
            "AUDITOR,            POST,  /carga/1/decisao-gestor",
            "AUDITOR,            PATCH, /carga/1/cancelar",
            "GESTOR,             POST,  /carga/upload",
            "GESTOR,             GET,   /admin/usuarios",
            "INTEGRADOR_EXTERNO, GET,   /processos/metricas",
            "INTEGRADOR_EXTERNO, POST,  /carga/metadados"
    })
    void rotasCriticasBarramPerfilTrocado(String perfil, String metodo, String rota) throws Exception {
        Long usuario = criarUsuario(perfil);

        mockMvc.perform(comToken(request(HttpMethod.valueOf(metodo), rota), login(usuario))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        assertEquals(1, acessosNegados(usuario));
    }

    @Test
    @DisplayName("Tarefa 19: nenhuma rota do sistema fica sem permissão declarada")
    void todaRotaDeclaraPermissao() {
        List<String> semPermissao = new ArrayList<>();

        rotasDoSistema().forEach((info, metodo) -> {
            if (exigida(metodo) == null && anotacao(metodo, RotaPublica.class) == null) {
                semPermissao.add(info.toString());
            }
        });

        assertTrue(semPermissao.isEmpty(), "Rotas sem @RequirePermission: " + semPermissao);
    }

    @Test
    @DisplayName("Tarefa 19: todo perfil sem a permissão recebe 403 em todas as rotas, e cada tentativa é registrada")
    void matrizCompletaDeRotasPorPerfil() throws Exception {
        Map<String, Long> usuarios = new LinkedHashMap<>();
        Map<String, String> tokens = new LinkedHashMap<>();
        for (String perfil : PERFIS) {
            Long id = criarUsuario(perfil);
            usuarios.put(perfil, id);
            tokens.put(perfil, login(id));
        }

        List<String> furos = new ArrayList<>();
        int negadas = 0;

        for (Map.Entry<RequestMappingInfo, HandlerMethod> rota : rotasDoSistema().entrySet()) {
            Permissao permissao = exigida(rota.getValue());
            if (permissao == null) {
                continue;
            }

            for (String padrao : rota.getKey().getPatternValues()) {
                String url = padrao.replaceAll("\\{[^}]*}", "1");

                for (RequestMethod metodo : metodos(rota.getKey())) {
                    for (String perfil : PERFIS) {
                        if (MatrizPermissoes.perfilTem(perfil, permissao)) {
                            continue;
                        }

                        int resposta = mockMvc.perform(comToken(request(HttpMethod.valueOf(metodo.name()), url), tokens.get(perfil))
                                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andReturn().getResponse().getStatus();

                        negadas++;
                        if (resposta != 403) {
                            furos.add(perfil + " em " + metodo + " " + url + " recebeu " + resposta);
                        }
                    }
                }
            }
        }

        assertTrue(furos.isEmpty(), "Investidas que passaram: " + furos);
        assertTrue(negadas > 100, "Matriz percorreu poucas combinações: " + negadas);

        int registradas = usuarios.values().stream().mapToInt(this::acessosNegados).sum();
        assertEquals(negadas, registradas);
    }

    @Test
    @DisplayName("Tarefa 20: colaborador inativado perde o acesso na requisição seguinte, com o mesmo JWT")
    void colaboradorDesligadoPerdeAcessoNaHora() throws Exception {
        Long colaborador = criarUsuario("OPERADOR");
        String tokenDoColaborador = login(colaborador);
        String tokenDoAdmin = login(criarUsuario("ADMINISTRADOR"));

        mockMvc.perform(comToken(get("/dominios/filtros"), tokenDoColaborador)).andExpect(status().isOk());

        mudarStatus(colaborador, false, tokenDoAdmin);

        mockMvc.perform(comToken(get("/dominios/filtros"), tokenDoColaborador))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(comToken(post("/carga/metadados"), tokenDoColaborador)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        assertEquals(0, acessosNegados(colaborador));

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email(colaborador) + "\", \"senha\": \"" + SENHA + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/dominios/filtros").with(httpBasic(email(colaborador), SENHA)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Tarefa 20: reativar o colaborador não ressuscita o JWT antigo")
    void reativarNaoRessuscitaTokenAntigo() throws Exception {
        Long colaborador = criarUsuario("OPERADOR");
        String tokenAntigo = login(colaborador);
        String tokenDoAdmin = login(criarUsuario("ADMINISTRADOR"));

        mudarStatus(colaborador, false, tokenDoAdmin);
        mudarStatus(colaborador, true, tokenDoAdmin);

        mockMvc.perform(comToken(get("/dominios/filtros"), tokenAntigo)).andExpect(status().isUnauthorized());

        String tokenNovo = login(colaborador);
        assertNotNull(tokenNovo);
        mockMvc.perform(comToken(get("/dominios/filtros"), tokenNovo)).andExpect(status().isOk());
    }

    private Map<RequestMappingInfo, HandlerMethod> rotasDoSistema() {
        Map<RequestMappingInfo, HandlerMethod> doSistema = new LinkedHashMap<>();
        rotas.getHandlerMethods().forEach((info, metodo) -> {
            if (metodo.getBeanType().getPackageName().startsWith("com.bifrostconnect.api_geo")) {
                doSistema.put(info, metodo);
            }
        });
        return doSistema;
    }

    private Set<RequestMethod> metodos(RequestMappingInfo info) {
        Set<RequestMethod> declarados = info.getMethodsCondition().getMethods();
        return declarados.isEmpty() ? Set.of(RequestMethod.GET) : declarados;
    }

    private Permissao exigida(HandlerMethod metodo) {
        RequirePermission anotacao = anotacao(metodo, RequirePermission.class);
        return anotacao == null ? null : anotacao.value();
    }

    private <A extends java.lang.annotation.Annotation> A anotacao(HandlerMethod metodo, Class<A> tipo) {
        A doMetodo = AnnotatedElementUtils.findMergedAnnotation(metodo.getMethod(), tipo);
        return doMetodo != null ? doMetodo : AnnotatedElementUtils.findMergedAnnotation(metodo.getBeanType(), tipo);
    }

    private void mudarStatus(Long usuarioId, boolean ativo, String tokenDoAdmin) throws Exception {
        mockMvc.perform(comToken(patch("/admin/usuarios/{id}/status", usuarioId), tokenDoAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ativo\": " + ativo + "}"))
                .andExpect(status().isOk());
    }

    private Long criarUsuario(String perfil) {
        String sufixo = String.valueOf(ThreadLocalRandom.current().nextInt(100_000_000, 999_999_999));
        return jdbc.queryForObject("""
                INSERT INTO usuario (nome, email, senha_hash, perfil_id, ativo)
                VALUES (?, ?, ?, (SELECT id FROM perfil WHERE nome = ?), TRUE)
                RETURNING id
                """, Long.class, "Teste " + perfil, "cruzado." + sufixo + "@teste.com", passwordEncoder.encode(SENHA), perfil);
    }

    private String email(Long usuarioId) {
        return jdbc.queryForObject("SELECT email FROM usuario WHERE id = ?", String.class, usuarioId);
    }

    private Long perfilId(String nome) {
        return jdbc.queryForObject("SELECT id FROM perfil WHERE nome = ?", Long.class, nome);
    }

    private int acessosNegados(Long usuarioId) {
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auditoria WHERE usuario_id = ? AND acao = 'ACESSO_NEGADO_403'", Integer.class, usuarioId);
        return total == null ? 0 : total;
    }

    private int totalAcessosNegados() {
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auditoria WHERE acao = 'ACESSO_NEGADO_403'", Integer.class);
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