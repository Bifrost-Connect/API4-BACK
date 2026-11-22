package com.bifrostconnect.api_geo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import com.bifrostconnect.api_geo.dto.EvolucaoResponseDTO;
import com.fasterxml.jackson.databind.ObjectMapper;

class ComparacaoServiceTest {

    private static final long LIMITE_SIMPLIFICACAO = 100L;
    private static final long LIMITE_MAXIMO = 1000L;
    private static final double TOLERANCIA = 0.0001;

    private JdbcTemplate jdbcTemplate;
    private ComparacaoService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new ComparacaoService(
                jdbcTemplate,
                new ObjectMapper(),
                LIMITE_SIMPLIFICACAO,
                LIMITE_MAXIMO,
                TOLERANCIA);
    }

    private void stubCargasPublicadasEMesmoConjunto(long idCargaA, long idCargaB) {
        when(jdbcTemplate.queryForList(anyString(), eq(Boolean.class), eq(idCargaA)))
                .thenReturn(List.of(true));
        when(jdbcTemplate.queryForList(anyString(), eq(Boolean.class), eq(idCargaB)))
                .thenReturn(List.of(true));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(idCargaA), eq(idCargaB)))
                .thenReturn(1L);
    }

    private void stubRegiaoExistente(long idRegiao) {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(idRegiao)))
                .thenReturn(1L);
    }

    @SuppressWarnings("unchecked")
    private void stubInspecaoJanela(boolean sobrepoe, long totalVertices) {
        when(jdbcTemplate.queryForObject(
                anyString(),
                ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class)))
                .thenAnswer(inv -> {
                    RowMapper<Object> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getBoolean("sobrepoe")).thenReturn(sobrepoe);
                    when(rs.getObject("sobrepoe", Boolean.class)).thenReturn(sobrepoe);
                    when(rs.getLong("total_vertices")).thenReturn(totalVertices);
                    return mapper.mapRow(rs, 0);
                });
    }

    @SuppressWarnings("unchecked")
    private void stubInspecaoEEvolucao(boolean sobrepoe, long totalVertices) {
        when(jdbcTemplate.queryForObject(
                anyString(),
                ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    RowMapper<Object> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    if (sql.contains("resumo_cargas")) {
                        when(rs.getBoolean("sobrepoe")).thenReturn(sobrepoe);
                        when(rs.getObject("sobrepoe", Boolean.class)).thenReturn(sobrepoe);
                        when(rs.getLong("total_vertices")).thenReturn(totalVertices);
                    } else {
                        when(rs.getDouble("area_a")).thenReturn(40.0);
                        when(rs.getDouble("area_b")).thenReturn(70.0);
                        when(rs.getDouble("area_aumento")).thenReturn(35.0);
                        when(rs.getDouble("area_reducao")).thenReturn(5.0);
                        when(rs.getDouble("area_mantido")).thenReturn(35.0);
                        when(rs.getString("features_json")).thenReturn("[]");
                    }
                    return mapper.mapRow(rs, 0);
                });
    }

    @Test
    @DisplayName("compararCamadas executa apenas 1 consulta combinada de sobreposição e vértices antes da consulta principal")
    void compararCamadasExecutaApenasUmaConsultaCombinadaDeInspecao() {
        stubCargasPublicadasEMesmoConjunto(1L, 2L);
        stubRegiaoExistente(10L);
        stubInspecaoJanela(true, 50L);
        when(jdbcTemplate.query(
                anyString(),
                ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class)))
                .thenReturn(List.of());

        Map<String, Object> resposta = service.compararCamadas(1L, 2L, 10L, null);

        assertEquals(false, resposta.get("simplificada"));

        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Boolean.class), any(Object[].class));
        // apenas conjuntos (1L, 2L) e regiao (10L) usam queryForObject(..., Long.class, ...), não há 3ª query de vértices
        verify(jdbcTemplate, times(1)).queryForObject(anyString(), eq(Long.class), eq(1L), eq(2L));
        verify(jdbcTemplate, times(1)).queryForObject(anyString(), eq(Long.class), eq(10L));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(1)).queryForObject(
                sqlCaptor.capture(),
                ArgumentMatchers.<RowMapper<Object>>any(),
                argsCaptor.capture());

        String sqlInspecao = sqlCaptor.getValue();
        assertTrue(sqlInspecao.contains("resumo_cargas"));
        assertTrue(sqlInspecao.contains("ST_SetSRID(ST_Extent(fc.geom)::geometry, 4674)"));
        assertTrue(sqlInspecao.contains("COALESCE(SUM(ST_NPoints(fc.geom)), 0)"));
        assertTrue(sqlInspecao.contains("AS sobrepoe"));
        assertTrue(sqlInspecao.contains("AS total_vertices"));
        assertEquals(List.of(10L, 1L, 2L, 1L, 2L), List.of(argsCaptor.getValue()));

        verify(jdbcTemplate, times(1)).query(
                anyString(),
                ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class));
    }

    @Test
    @DisplayName("calcularEvolucao executa apenas 1 consulta combinada de inspeção antes da consulta principal")
    void calcularEvolucaoExecutaApenasUmaConsultaCombinadaDeInspecao() {
        stubCargasPublicadasEMesmoConjunto(1L, 2L);
        stubRegiaoExistente(10L);
        stubInspecaoEEvolucao(true, 50L);

        EvolucaoResponseDTO dto = service.calcularEvolucao(1L, 2L, 10L, null);

        assertFalse(dto.simplificada());
        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Boolean.class), any(Object[].class));
        verify(jdbcTemplate, times(1)).queryForObject(anyString(), eq(Long.class), eq(1L), eq(2L));
        verify(jdbcTemplate, times(1)).queryForObject(anyString(), eq(Long.class), eq(10L));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        // 1 chamada de inspecionarJanela + 1 chamada principal de calcularEvolucao
        verify(jdbcTemplate, times(2)).queryForObject(
                sqlCaptor.capture(),
                ArgumentMatchers.<RowMapper<Object>>any(),
                argsCaptor.capture());

        String sqlInspecao = sqlCaptor.getAllValues().get(0);
        assertTrue(sqlInspecao.contains("resumo_cargas"));
        assertTrue(sqlInspecao.contains("AS sobrepoe"));
        assertTrue(sqlInspecao.contains("AS total_vertices"));
        assertEquals(List.of(10L, 1L, 2L, 1L, 2L), List.of(argsCaptor.getAllValues().get(0)));
    }

    @Test
    @DisplayName("Lança 422 UNPROCESSABLE_ENTITY quando sobrepoe = false sem executar consulta principal")
    void lanca422QuandoNaoHaSobreposicao() {
        stubCargasPublicadasEMesmoConjunto(1L, 2L);
        stubRegiaoExistente(10L);
        stubInspecaoJanela(false, 50L);

        ResponseStatusException erroCamadas = assertThrows(
                ResponseStatusException.class,
                () -> service.compararCamadas(1L, 2L, 10L, null));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, erroCamadas.getStatusCode());
        verify(jdbcTemplate, never()).query(
                anyString(),
                ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class));

        ResponseStatusException erroEvolucao = assertThrows(
                ResponseStatusException.class,
                () -> service.calcularEvolucao(1L, 2L, 10L, null));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, erroEvolucao.getStatusCode());
        // 1 chamada em compararCamadas + 1 chamada em calcularEvolucao (nunca executou a principal)
        verify(jdbcTemplate, times(2)).queryForObject(
                anyString(),
                ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class));
    }

    @Test
    @DisplayName("Lança 413 PAYLOAD_TOO_LARGE quando total_vertices excede limiteMaximoVertices")
    void lanca413QuandoExcedeLimiteMaximoDeVertices() {
        stubCargasPublicadasEMesmoConjunto(1L, 2L);
        stubRegiaoExistente(10L);
        stubInspecaoJanela(true, LIMITE_MAXIMO + 1);

        ResponseStatusException erroCamadas = assertThrows(
                ResponseStatusException.class,
                () -> service.compararCamadas(1L, 2L, 10L, null));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, erroCamadas.getStatusCode());
        assertTrue(erroCamadas.getReason().contains(String.valueOf(LIMITE_MAXIMO + 1)));
        assertTrue(erroCamadas.getReason().contains(String.valueOf(LIMITE_MAXIMO)));
        verify(jdbcTemplate, never()).query(
                anyString(),
                ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class));

        ResponseStatusException erroEvolucao = assertThrows(
                ResponseStatusException.class,
                () -> service.calcularEvolucao(1L, 2L, 10L, null));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, erroEvolucao.getStatusCode());
        verify(jdbcTemplate, times(2)).queryForObject(
                anyString(),
                ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class));
    }

    @Test
    @DisplayName("Retorna simplificada = true quando vértices estão entre limiteSimplificacaoVertices e limiteMaximoVertices")
    void retornaSimplificadaTrueQuandoAcimaDoLimiteDeSimplificacao() {
        stubCargasPublicadasEMesmoConjunto(1L, 2L);
        stubRegiaoExistente(10L);
        stubInspecaoEEvolucao(true, LIMITE_SIMPLIFICACAO + 1);
        when(jdbcTemplate.query(
                anyString(),
                ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class)))
                .thenReturn(List.of());

        Map<String, Object> camadas = service.compararCamadas(1L, 2L, 10L, null);
        assertEquals(true, camadas.get("simplificada"));

        ArgumentCaptor<String> sqlCamadas = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCamadas.capture(),
                ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class));
        assertTrue(sqlCamadas.getValue().contains("ST_SimplifyPreserveTopology("));

        EvolucaoResponseDTO evolucao = service.calcularEvolucao(1L, 2L, 10L, null);
        assertTrue(evolucao.simplificada());

        ArgumentCaptor<String> sqlEvolucao = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(3)).queryForObject(
                sqlEvolucao.capture(),
                ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class));
        assertTrue(sqlEvolucao.getAllValues().get(2).contains("ST_SimplifyPreserveTopology("));
    }

    @Test
    @DisplayName("Modo extensao_total usa ST_SetSRID(ST_Extent(fc.geom)::geometry, 4674) e não usa ST_Collect(fc.geom) na CTE janela")
    void modoExtensaoTotalUsaStExtentEmVezDeStCollect() {
        stubCargasPublicadasEMesmoConjunto(1L, 2L);
        stubInspecaoEEvolucao(true, 50L);

        service.calcularEvolucao(1L, 2L, null, null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).queryForObject(
                sqlCaptor.capture(),
                ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class));

        for (String sql : sqlCaptor.getAllValues()) {
            assertTrue(sql.contains("ST_SetSRID(ST_Extent(fc.geom)::geometry, 4674) AS geom"));
            assertFalse(sql.contains("ST_Collect(fc.geom)"));
        }
    }

    @Test
    @DisplayName("calcularEvolucao calcula áreas uma única vez na CTE areas e reutiliza c.area_ha no json_agg")
    void calcularEvolucaoNaoDuplicaCalculoDeAreaNoJsonAgg() {
        stubCargasPublicadasEMesmoConjunto(1L, 2L);
        stubRegiaoExistente(10L);
        stubInspecaoEEvolucao(true, 50L);

        service.calcularEvolucao(1L, 2L, 10L, null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).queryForObject(
                sqlCaptor.capture(),
                ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class));

        String sqlPrincipal = sqlCaptor.getAllValues().get(1);
        assertTrue(sqlPrincipal.contains("areas AS ("));
        assertTrue(sqlPrincipal.contains("FROM areas"));
        assertTrue(sqlPrincipal.contains("ROUND(c.area_ha::numeric, 4)"));
        assertTrue(sqlPrincipal.contains("(1, 'NOVO_ALERTA', aumento, area_aumento)"));
        assertTrue(sqlPrincipal.contains("(2, 'REDUCAO_ALERTA', reducao, area_reducao)"));
        assertTrue(sqlPrincipal.contains("(3, 'MANTIDO', mantido, area_mantido)"));
        assertTrue(sqlPrincipal.contains("AS c(ordem, classe, geom, area_ha)"));
        assertFalse(sqlPrincipal.contains("ST_Area(c.geom::geography)"));
    }
}
