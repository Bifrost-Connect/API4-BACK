package com.bifrostconnect.api_geo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.bifrostconnect.api_geo.security.ApiKeyAuthService.ContextoApiKey;
import com.bifrostconnect.api_geo.security.ApiKeyAuthService.MotivoRecusa;
import com.fasterxml.jackson.databind.ObjectMapper;

// US12 - Tarefa 17: chave vencida é recusada pelo middleware (ApiKeyAuthenticationFilter) sozinha,
// sem ninguém precisar mudar o status no banco. O relógio é controlado pelo teste, sem sleep
class ApiKeyExpiracaoMiddlewareTest {

    private static final String CHAVE = "grp_live_chave-de-teste-do-middleware-0123456789abcdef";
    private static final Instant AGORA = Instant.parse("2026-11-19T12:00:00Z");

    private JdbcTemplate jdbc;
    private RelogioAjustavel relogio;
    private ApiKeyAuthService authService;
    private ApiKeyAuthenticationFilter filtro;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        relogio = new RelogioAjustavel(AGORA);
        authService = new ApiKeyAuthService(jdbc, new ObjectMapper(), relogio);
        filtro = new ApiKeyAuthenticationFilter(authService);
    }

    @AfterEach
    void limparContexto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Tarefa 17: a consulta de autenticação só aceita chave ATIVA e ainda dentro da validade")
    void consultaDoBancoFiltraChaveVencida() {
        authService.validar(CHAVE);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).query(sql.capture(), any(RowMapper.class), any(Object.class));

        String consultaAutenticacao = sql.getAllValues().get(0);
        assertThat(consultaAutenticacao)
                .contains("c.status = 'ATIVA'")
                .contains("c.data_expiracao IS NULL OR c.data_expiracao > CURRENT_TIMESTAMP");
    }

    @Test
    @DisplayName("Tarefa 17: chave vencida no banco -> 401 API_KEY_EXPIRADA, sem chegar no controller nem gravar uso")
    void chaveVencidaNoBancoRecebe401() throws Exception {
        // a consulta principal não acha nada (o WHERE já tira a vencida) e o diagnóstico diz o porquê
        doReturn(List.of(MotivoRecusa.EXPIRADA)).when(jdbc)
                .query(contains("AS vencida"), any(RowMapper.class), any(Object.class));

        MockFilterChain cadeia = new MockFilterChain();
        MockHttpServletResponse resposta = chamar(cadeia);

        assertThat(resposta.getStatus()).isEqualTo(401);
        assertThat(resposta.getHeader("WWW-Authenticate")).startsWith("ApiKey");
        assertThat(resposta.getContentAsString())
                .contains("\"codigo\":\"API_KEY_EXPIRADA\"")
                .contains("\"status\":401");
        assertThat(cadeia.getRequest()).as("o controller não pode ser chamado").isNull();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Tarefa 17: chave que vence enquanto está no cache é recusada na hora, sem esperar o TTL")
    void chaveQueVenceNoCacheERecusada() throws Exception {
        ContextoApiKey contexto = new ContextoApiKey(
                1L, 2L, "Parceiro", Set.of("CATALOGO"), AGORA.plusSeconds(30));
        doReturn(List.of(contexto)).when(jdbc)
                .query(contains("c.status = 'ATIVA'"), any(RowMapper.class), any(Object.class));

        // 1) ainda válida: passa e entra no cache
        MockFilterChain primeira = new MockFilterChain();
        assertThat(chamar(primeira).getStatus()).isEqualTo(200);
        assertThat(primeira.getRequest()).isNotNull();

        // 2) o relógio passa da data de expiração (TTL do cache de 60s ainda não acabou)
        relogio.avancar(Duration.ofSeconds(31));
        SecurityContextHolder.clearContext();

        MockFilterChain segunda = new MockFilterChain();
        MockHttpServletResponse resposta = chamar(segunda);

        assertThat(resposta.getStatus()).isEqualTo(401);
        assertThat(resposta.getContentAsString()).contains("API_KEY_EXPIRADA");
        assertThat(segunda.getRequest()).isNull();

        // a recusa saiu do cache: o banco foi consultado só na primeira chamada
        verify(jdbc, times(1)).query(contains("c.status = 'ATIVA'"), any(RowMapper.class), any(Object.class));
    }

    @Test
    @DisplayName("Tarefa 17: exatamente no instante da expiração a chave já não vale")
    void instanteDaExpiracaoJaRecusa() {
        ContextoApiKey contexto = new ContextoApiKey(
                1L, 2L, "Parceiro", Set.of("CATALOGO"), AGORA.plusSeconds(10));
        doReturn(List.of(contexto)).when(jdbc)
                .query(contains("c.status = 'ATIVA'"), any(RowMapper.class), any(Object.class));

        assertThat(authService.validar(CHAVE).autenticada()).isTrue();

        relogio.avancar(Duration.ofSeconds(10));
        var resultado = authService.validar(CHAVE);

        assertThat(resultado.autenticada()).isFalse();
        assertThat(resultado.motivo()).isEqualTo(MotivoRecusa.EXPIRADA);
    }

    // ---------- Tarefa 16: os outros 401 do middleware ----------

    @Test
    @DisplayName("Tarefa 16: sem header -> 401 API_KEY_AUSENTE (nem consulta o banco)")
    void semHeaderRecebe401Ausente() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/externo/catalogo");
        req.setServletPath("/api");
        MockHttpServletResponse resposta = new MockHttpServletResponse();
        MockFilterChain cadeia = new MockFilterChain();

        filtro.doFilter(req, resposta, cadeia);

        assertThat(resposta.getStatus()).isEqualTo(401);
        assertThat(resposta.getContentAsString()).contains("API_KEY_AUSENTE");
        assertThat(cadeia.getRequest()).isNull();
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(Object.class));
    }

    @Test
    @DisplayName("Tarefa 16: chave revogada -> 401 API_KEY_REVOGADA; inexistente -> 401 API_KEY_INVALIDA")
    void revogadaEInexistenteRecebem401() throws Exception {
        doReturn(List.of(MotivoRecusa.REVOGADA)).when(jdbc)
                .query(contains("AS vencida"), any(RowMapper.class), any(Object.class));
        assertThat(chamar(new MockFilterChain()).getContentAsString()).contains("API_KEY_REVOGADA");

        doReturn(List.of()).when(jdbc)
                .query(contains("AS vencida"), any(RowMapper.class), any(Object.class));
        MockHttpServletResponse inexistente = chamar(new MockFilterChain());
        assertThat(inexistente.getStatus()).isEqualTo(401);
        assertThat(inexistente.getContentAsString()).contains("API_KEY_INVALIDA");
    }

    @Test
    @DisplayName("Tarefa 16: na 61ª chamada do minuto -> 429 com Retry-After")
    void estouroDoLimiteRecebe429ComRetryAfter() throws Exception {
        ContextoApiKey contexto = new ContextoApiKey(5L, 2L, "Parceiro", Set.of("CATALOGO"), null);
        doReturn(List.of(contexto)).when(jdbc)
                .query(contains("c.status = 'ATIVA'"), any(RowMapper.class), any(Object.class));

        for (int i = 0; i < ApiKeyAuthService.LIMITE_REQUISICOES_POR_MINUTO; i++) {
            assertThat(chamar(new MockFilterChain()).getStatus()).isEqualTo(200);
            SecurityContextHolder.clearContext();
        }

        relogio.avancar(Duration.ofSeconds(20));
        MockHttpServletResponse resposta = chamar(new MockFilterChain());

        assertThat(resposta.getStatus()).isEqualTo(429);
        assertThat(resposta.getHeader("Retry-After")).isEqualTo("40");
        assertThat(resposta.getContentAsString()).contains("LIMITE_REQUISICOES_EXCEDIDO");

        // virou o minuto: libera de novo
        relogio.avancar(Duration.ofSeconds(40));
        SecurityContextHolder.clearContext();
        assertThat(chamar(new MockFilterChain()).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Tarefa 16: com spring.mvc.servlet.path=/api o filtro roda (antes era pulado e a rota ficava sem checagem da chave)")
    void filtroRodaComServletPathApi() {
        MockHttpServletRequest externo = new MockHttpServletRequest("GET", "/api/v1/externo/catalogo");
        externo.setServletPath("/api");
        assertThat(filtro.shouldNotFilter(externo)).isFalse();

        MockHttpServletRequest interno = new MockHttpServletRequest("GET", "/api/catalogo/bases");
        interno.setServletPath("/api");
        assertThat(filtro.shouldNotFilter(interno)).isTrue();
    }

    // ------------------------------------------------------------------

    private MockHttpServletResponse chamar(MockFilterChain cadeia) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/externo/catalogo");
        req.setServletPath("/api");
        req.addHeader("X-API-Key", CHAVE);
        MockHttpServletResponse resposta = new MockHttpServletResponse();
        filtro.doFilter(req, resposta, cadeia);
        return resposta;
    }

    static class RelogioAjustavel extends Clock {
        private Instant agora;

        RelogioAjustavel(Instant inicio) {
            this.agora = inicio;
        }

        void avancar(Duration duracao) {
            agora = agora.plus(duracao);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }
}
