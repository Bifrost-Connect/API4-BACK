package com.bifrostconnect.api_geo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.bifrostconnect.api_geo.dto.EmitirChaveApiRequest;
import com.bifrostconnect.api_geo.security.ApiKeyAuthService;
import com.bifrostconnect.api_geo.security.ApiKeyAuthService.ContextoApiKey;
import com.bifrostconnect.api_geo.security.ApiKeyAuthenticationFilter;
import com.bifrostconnect.api_geo.security.UsuarioSessaoService;
import com.bifrostconnect.api_geo.security.UsuarioSessaoService.EstadoUsuario;
import com.bifrostconnect.api_geo.service.ChaveApiCriptoService.ChaveGerada;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

// US12 - Tarefa 17: a chave em texto puro só pode existir na resposta da emissão.
// Nada que vai pro banco (INSERT, UPDATE, auditoria) e nada que sai no log pode conter ela.
// O JdbcTemplate é mock: toda chamada que "iria pro banco" fica registrada e é conferida no fim
class ChaveApiTextoPuroTest {

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChaveApiCriptoService cripto = new ChaveApiCriptoService();

    private JdbcTemplate jdbc;
    private ApiKeyAuthService authService;
    private ChaveApiService chaveApiService;

    // captura TUDO que for logado (root em TRACE), de qualquer classe, durante o teste
    private ListAppender<ILoggingEvent> logs;
    private Logger root;
    private Level nivelOriginal;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        UsuarioSessaoService usuarios = mock(UsuarioSessaoService.class);
        when(usuarios.buscarPorLogin(anyString())).thenReturn(Optional.of(
                new EstadoUsuario(7L, "Integrador", "integrador@teste.local", "hash",
                        "INTEGRADOR_EXTERNO", true, 1, false)));

        authService = new ApiKeyAuthService(jdbc, objectMapper);
        chaveApiService = new ChaveApiService(jdbc, cripto, usuarios, authService, objectMapper);

        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        nivelOriginal = root.getLevel();
        root.setLevel(Level.TRACE);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
        root.setLevel(nivelOriginal);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Tarefa 17: o gerador devolve hash SHA-256 hex de 64 chars, diferente da chave e reproduzível")
    void geradorProduzHashSemTextoPuro() {
        ChaveGerada gerada = cripto.gerar();

        assertThat(gerada.chaveAberta()).startsWith(ChaveApiCriptoService.PREFIXO_PADRAO);
        assertThat(gerada.hashSha256()).matches(SHA256_HEX);
        assertThat(gerada.hashSha256()).doesNotContain(gerada.chaveAberta());
        assertThat(gerada.hashSha256()).isEqualTo(cripto.hashSha256(gerada.chaveAberta()));

        // o prefixo visível é curto demais pra reconstruir a chave
        assertThat(gerada.prefixoVisivel()).hasSize(12);
        assertThat(gerada.chaveAberta()).hasSizeGreaterThan(40);

        // toString mascarado: se alguém fizer log.info("{}", gerada) por engano, a chave não vaza
        assertThat(gerada.toString())
                .doesNotContain(gerada.chaveAberta())
                .doesNotContain(gerada.hashSha256());
    }

    @Test
    @DisplayName("Tarefa 17: a emissão grava só o hash e o prefixo; a chave aberta aparece apenas na resposta")
    void emissaoNuncaGravaTextoPuro() {
        simularInsertEMetadados();

        Map<String, Object> resposta = chaveApiService.emitir(
                new EmitirChaveApiRequest("Parceiro X", List.of("CATALOGO"), null), "integrador@teste.local");

        String chaveAberta = (String) resposta.get("chave");
        assertThat(chaveAberta).startsWith(ChaveApiCriptoService.PREFIXO_PADRAO);

        // pega o INSERT em chaves_api e confere coluna por coluna
        Object[] valoresInsert = argumentosDoInsert();
        // (usuario_id, nome_parceiro, prefixo_chave, hash_chave_sha256, escopos, data_expiracao)
        assertThat(valoresInsert[3]).isEqualTo(cripto.hashSha256(chaveAberta));
        assertThat((String) valoresInsert[3]).matches(SHA256_HEX);
        assertThat(valoresInsert[2]).isEqualTo(chaveAberta.substring(0, 12));

        assertNadaFoiProBancoCom(chaveAberta);
        assertNadaFoiLogadoCom(chaveAberta);
    }

    @Test
    @DisplayName("Tarefa 17: autenticar, registrar uso e auditoria e recusar chave nunca levam o texto puro ao banco nem ao log")
    void consumoNaoVazaTextoPuro() throws Exception {
        ChaveGerada gerada = cripto.gerar();
        String chaveAberta = gerada.chaveAberta();

        ContextoApiKey contexto = new ContextoApiKey(
                10L, 7L, "Parceiro X", Set.of("CATALOGO"), Instant.now().plusSeconds(3600));
        doReturn(List.of(contexto)).when(jdbc).query(
                contains("c.status = 'ATIVA'"), any(RowMapper.class), eq(gerada.hashSha256()));

        // chamada aceita: passa pelo filtro inteiro (autenticação + rate limit + UPDATE + auditoria)
        ApiKeyAuthenticationFilter filtro = new ApiKeyAuthenticationFilter(authService);
        MockHttpServletRequest ok = requisicaoExterna(chaveAberta);
        MockHttpServletResponse respostaOk = new MockHttpServletResponse();
        filtro.doFilter(ok, respostaOk, new MockFilterChain());
        assertThat(respostaOk.getStatus()).isEqualTo(200);

        // chamada recusada (outra chave, que não existe): o log de recusa só pode ter o prefixo
        String chaveInexistente = cripto.gerar().chaveAberta();
        MockHttpServletResponse respostaRecusada = new MockHttpServletResponse();
        filtro.doFilter(requisicaoExterna(chaveInexistente), respostaRecusada, new MockFilterChain());
        assertThat(respostaRecusada.getStatus()).isEqualTo(401);
        assertThat(respostaRecusada.getContentAsString()).doesNotContain(chaveInexistente);

        // a busca no banco é sempre pelo hash
        assertThat(argumentosDeTodasAsChamadas()).contains(gerada.hashSha256());

        assertNadaFoiProBancoCom(chaveAberta);
        assertNadaFoiProBancoCom(chaveInexistente);
        assertNadaFoiLogadoCom(chaveAberta);
        assertNadaFoiLogadoCom(chaveInexistente);

        // e o log de recusa existe, mas mascarado
        assertThat(logs.list)
                .anySatisfy(e -> assertThat(e.getFormattedMessage())
                        .contains("API Key recusada")
                        .contains(chaveInexistente.substring(0, 12) + "****"));
    }

    // ------------------------------------------------------------------

    private void simularInsertEMetadados() {
        when(jdbc.queryForObject(contains("INSERT INTO chaves_api"), any(Object[].class), any(int[].class),
                eq(Long.class))).thenReturn(99L);

        Map<String, Object> metadados = new LinkedHashMap<>();
        metadados.put("id", 99L);
        metadados.put("status", "ATIVA");
        doReturn(new ArrayList<>(List.of(metadados))).when(jdbc).query(
                contains("FROM chaves_api c"), any(RowMapper.class), eq(99L));
    }

    private Object[] argumentosDoInsert() {
        return mockingDetails(jdbc).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("queryForObject"))
                .filter(i -> String.valueOf(i.getRawArguments()[0]).contains("INSERT INTO chaves_api"))
                .map(i -> (Object[]) i.getRawArguments()[1])
                .findFirst()
                .orElseThrow(() -> new AssertionError("INSERT em chaves_api não foi executado"));
    }

    private MockHttpServletRequest requisicaoExterna(String chave) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/externo/catalogo");
        req.setServletPath("/api");
        req.addHeader("X-API-Key", chave);
        return req;
    }

    // todo argumento de toda chamada feita no JdbcTemplate (SQL e parâmetros), arrays abertos
    private List<String> argumentosDeTodasAsChamadas() {
        List<String> valores = new ArrayList<>();
        for (Invocation invocacao : mockingDetails(jdbc).getInvocations()) {
            for (Object arg : invocacao.getRawArguments()) {
                achatar(arg, valores);
            }
        }
        return valores;
    }

    private void achatar(Object arg, List<String> destino) {
        if (arg instanceof Object[] array) {
            for (Object item : array) {
                achatar(item, destino);
            }
        } else if (arg != null) {
            destino.add(String.valueOf(arg));
        }
    }

    private void assertNadaFoiProBancoCom(String chaveAberta) {
        assertThat(mockingDetails(jdbc).getInvocations()).isNotEmpty();
        assertThat(argumentosDeTodasAsChamadas())
                .as("nenhum SQL/parâmetro enviado ao banco pode conter a chave em texto puro")
                .noneMatch(valor -> valor.contains(chaveAberta));
    }

    private void assertNadaFoiLogadoCom(String chaveAberta) {
        for (ILoggingEvent evento : logs.list) {
            assertThat(evento.getFormattedMessage())
                    .as("log de %s", evento.getLoggerName())
                    .doesNotContain(chaveAberta);

            IThrowableProxy erro = evento.getThrowableProxy();
            while (erro != null) {
                assertThat(String.valueOf(erro.getMessage())).doesNotContain(chaveAberta);
                erro = erro.getCause();
            }
        }
    }
}
