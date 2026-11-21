package com.bifrostconnect.api_geo.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.bifrostconnect.api_geo.config.SecurityConfig;
import com.bifrostconnect.api_geo.dto.AirflowIngestaoStatusResponse;
import com.bifrostconnect.api_geo.dto.ConectorFonteDadosResponse;
import com.bifrostconnect.api_geo.exception.GlobalExceptionHandler;
import com.bifrostconnect.api_geo.security.AcessoNegadoHandler;
import com.bifrostconnect.api_geo.security.ApiKeyAuthService;
import com.bifrostconnect.api_geo.security.JwtService;
import com.bifrostconnect.api_geo.security.RbacInterceptor;
import com.bifrostconnect.api_geo.security.RbacService;
import com.bifrostconnect.api_geo.security.RbacWebConfig;
import com.bifrostconnect.api_geo.security.UsuarioSessaoService;
import com.bifrostconnect.api_geo.security.UsuarioSessaoService.EstadoUsuario;
import com.bifrostconnect.api_geo.service.ConectorIngestaoService;

@WebMvcTest(controllers = ConectorIngestaoController.class)
@Import({
        SecurityConfig.class,
        RbacWebConfig.class,
        RbacInterceptor.class,
        RbacService.class,
        AcessoNegadoHandler.class,
        GlobalExceptionHandler.class
})
@TestPropertySource(properties = {
        "airflow.internal-token=segredo-interno-airflow-teste"
})
class ConectorIngestaoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConectorIngestaoService conectorService;

    @MockBean
    private UsuarioSessaoService usuarioSessaoService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private ApiKeyAuthService apiKeyAuthService;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        when(usuarioSessaoService.buscarPorLogin("operador")).thenReturn(Optional.of(
                new EstadoUsuario(1L, "Operador", "operador@geo.local", "h", "OPERADOR", true, 1, false)));
        when(usuarioSessaoService.permissoesEfetivas(any())).thenCallRealMethod();
        when(usuarioSessaoService.resolverPermissoes(any(), any())).thenCallRealMethod();
    }

    @Test
    @DisplayName("Rotas protegidas de conectores sem autenticação devolvem 401")
    void deveNegarAcessoSemAutenticacao() throws Exception {
        mockMvc.perform(get("/conectores")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/conectores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/conectores/testar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "analista", roles = "ANALISTA")
    @DisplayName("ANALISTA sem CARGA_CONSULTAR recebe 403 ao consultar conectores")
    void analistaRecebe403AoConsultar() throws Exception {
        mockMvc.perform(get("/conectores")).andExpect(status().isForbidden());
        mockMvc.perform(get("/conectores/templates")).andExpect(status().isForbidden());
        mockMvc.perform(get("/conectores/execucoes")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "auditor", roles = "AUDITOR")
    @DisplayName("AUDITOR pode consultar conectores (200), mas recebe 403 em mutações (CARGA_ENVIAR)")
    void auditorConsultaMasNaoModifica() throws Exception {
        when(conectorService.listarConectores()).thenReturn(List.of(exemploResponse(1L)));
        when(conectorService.listarTemplatesPublicos()).thenReturn(List.of(Map.of("codigo", "SICAR_WFS")));
        when(conectorService.buscarConectorPorId(1L)).thenReturn(exemploResponse(1L));
        when(conectorService.listarExecucoes(null, null, null, 50)).thenReturn(List.of());

        mockMvc.perform(get("/conectores")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
        mockMvc.perform(get("/conectores/templates")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].codigo").value("SICAR_WFS"));
        mockMvc.perform(get("/conectores/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.credencialCifrada").doesNotExist());
        mockMvc.perform(get("/conectores/execucoes")).andExpect(status().isOk());

        mockMvc.perform(post("/conectores").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/conectores/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/conectores/1/ativo").contentType(MediaType.APPLICATION_JSON).content("{\"ativo\":false}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/conectores/1"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/conectores/1/testar"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/conectores/testar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/conectores/1/executar"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "operador", roles = "OPERADOR")
    @DisplayName("OPERADOR executa CRUD, teste de conexão e execução manual de conectores")
    void operadorExecutaOperacoesCompletas() throws Exception {
        ConectorFonteDadosResponse resp = exemploResponse(10L);
        AirflowIngestaoStatusResponse execResp = new AirflowIngestaoStatusResponse(
                77L, 10L, "SICAR PR", "run_10", "SICAR PR", "https://geo.test/wfs",
                "CARGA", "SICAR PR", "EM_ANDAMENTO", "PENDENTE", null, null, null,
                null, null, null, null, null, "[PENDENTE] Na fila", LocalDateTime.now(), null, 0L);

        when(conectorService.criarConector(any(), eq(1L))).thenReturn(resp);
        when(conectorService.atualizarConector(eq(10L), any())).thenReturn(resp);
        when(conectorService.alternarAtivo(10L, false)).thenReturn(resp);
        when(conectorService.testarConexao(10L)).thenReturn(Map.of("sucesso", true, "statusHttp", 200, "tempoMs", 45L));
        when(conectorService.testarConexaoAdHoc(any()))
                .thenReturn(Map.of("sucesso", true, "statusHttp", 200, "mensagem", "Conexão validada com sucesso (HTTP 200).", "tempoMs", 30L));
        when(conectorService.executarConector(eq(10L), any(), eq(1L))).thenReturn(execResp);

        String corpoValido = """
                {
                  "nome": "SICAR PR",
                  "tipoProtocolo": "OGC_WFS",
                  "urlBase": "https://geoserver.car.gov.br/geoserver/sicar/wfs",
                  "destino": "CARGA",
                  "formatoDados": "SHAPE-ZIP",
                  "tipoAutenticacao": "NONE",
                  "periodicidade": "MANUAL"
                }
                """;

        mockMvc.perform(post("/conectores").contentType(MediaType.APPLICATION_JSON).content(corpoValido))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.credencialCifrada").doesNotExist());

        mockMvc.perform(post("/conectores").contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/conectores/10").contentType(MediaType.APPLICATION_JSON).content(corpoValido))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10));

        mockMvc.perform(patch("/conectores/10/ativo").contentType(MediaType.APPLICATION_JSON).content("{\"ativo\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/conectores/10/ativo").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/conectores/10"))
                .andExpect(status().isNoContent());
        verify(conectorService).excluirConector(10L);

        mockMvc.perform(post("/conectores/10/testar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sucesso").value(true));

        mockMvc.perform(post("/conectores/testar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/conectores/testar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoValido))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sucesso").value(true))
                .andExpect(jsonPath("$.statusHttp").value(200));
        verify(conectorService).testarConexaoAdHoc(any());

        mockMvc.perform(post("/conectores/10/executar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parametrosOverride\":{\"uf\":\"PR\"}}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(77))
                .andExpect(jsonPath("$.etapaUi").value("PENDENTE"));
    }

    @Test
    @DisplayName("Callback interno /conectores/interno/** passa pelo Spring Security e valida X-Airflow-Token")
    void callbackInternoValidaXAirflowToken() throws Exception {
        when(conectorService.executarPosProcessamentoCarga(42L, null))
                .thenReturn(Map.of("processoId", 42L, "quarentena", false));

        // Sem header -> 401
        mockMvc.perform(post("/conectores/interno/processos/42/pos-processar")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        // Token errado -> 401
        mockMvc.perform(post("/conectores/interno/processos/42/pos-processar")
                        .header("X-Airflow-Token", "token-errado")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        // Token válido sem JWT/Basic -> 200 OK
        mockMvc.perform(post("/conectores/interno/processos/42/pos-processar")
                        .header("X-Airflow-Token", "segredo-interno-airflow-teste")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processoId").value(42));

        verify(conectorService).executarPosProcessamentoCarga(42L, null);
    }

    private ConectorFonteDadosResponse exemploResponse(Long id) {
        return new ConectorFonteDadosResponse(
                id, "SICAR PR", "Desc", "OGC_WFS", "https://geoserver.car.gov.br/geoserver/sicar/wfs",
                "CARGA", null, "Imóveis PR", 1L, "SICAR", 1L, "Imóveis rurais",
                "SHAPE-ZIP", "4674", "cod_imovel,ogc_fid", Map.of("uf", "PR"),
                "NONE", null, null, false, "MANUAL", null, true,
                null, null, null, 1L, LocalDateTime.now(), LocalDateTime.now()
        );
    }
}
