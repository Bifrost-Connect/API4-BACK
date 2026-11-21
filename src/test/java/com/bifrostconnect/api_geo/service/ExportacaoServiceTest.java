package com.bifrostconnect.api_geo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import com.bifrostconnect.api_geo.security.UsuarioSessaoService;
import com.bifrostconnect.api_geo.service.ExportacaoService.FiltroExportacao;
import com.fasterxml.jackson.databind.ObjectMapper;

class ExportacaoServiceTest {

    private JdbcTemplate jdbcTemplate;
    private ExportacaoService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new ExportacaoService(
                jdbcTemplate,
                new ObjectMapper(),
                mock(UsuarioSessaoService.class),
                new CsvExportService());

        when(jdbcTemplate.queryForList(
                argThat((String sql) -> sql != null && sql.contains("FROM processo p")),
                eq(42L)))
                .thenReturn(List.of(Map.of("versao_fechada", true, "versao_oficial", "v1")));
        when(jdbcTemplate.queryForList(
                argThat((String sql) -> sql != null && sql.contains("ORDER BY v.feicao_id LIMIT")),
                any(Object[].class)))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("Caso 1: sem filtros, lê total_feicoes de base_localizacao_cache sem consultar view pesada")
    void deveUsarCacheQuandoSemFiltros() {
        when(jdbcTemplate.query(
                argThat((String sql) -> sql != null && sql.contains("base_localizacao_cache")),
                ArgumentMatchers.<ResultSetExtractor<Long>>any(),
                eq(42L)))
                .thenReturn(136267L);

        Map<String, Object> resposta = service.preview(new FiltroExportacao(42L, "   ", null, null, " "));

        assertEquals(136267L, resposta.get("total_registros"));
        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Long.class), any());
    }

    @Test
    @DisplayName("Caso 1 fallback: sem linha no cache, conta direto em vw_copia_vigente + feicao_carga")
    void deveFazerFallbackParaFeicaoCargaQuandoCacheVazio() {
        when(jdbcTemplate.query(
                argThat((String sql) -> sql != null && sql.contains("base_localizacao_cache")),
                ArgumentMatchers.<ResultSetExtractor<Long>>any(),
                eq(42L)))
                .thenReturn(null);
        when(jdbcTemplate.queryForObject(
                argThat((String sql) -> sql != null && sql.contains("FROM vw_copia_vigente cv")
                        && !sql.contains("codigo_imovel")),
                eq(Long.class),
                eq(42L)))
                .thenReturn(512L);

        Map<String, Object> resposta = service.preview(new FiltroExportacao(42L, null, null, null, null));

        assertEquals(512L, resposta.get("total_registros"));
        verify(jdbcTemplate, never()).queryForObject(
                argThat((String sql) -> sql != null && sql.contains("vw_relatorio_exportacao_oficial")),
                eq(Long.class),
                any(Object[].class));
    }

    @Test
    @DisplayName("Caso 2: apenas codigo_imovel conta direto em vw_copia_vigente + feicao_carga com trim")
    void deveContarDiretoEmFeicaoCargaQuandoApenasCodigoImovel() {
        when(jdbcTemplate.queryForObject(
                argThat((String sql) -> sql != null && sql.contains("FROM vw_copia_vigente cv")
                        && sql.contains("fc.codigo_imovel = ?")),
                eq(Long.class),
                eq(42L),
                eq("CAR-0001")))
                .thenReturn(1L);

        Map<String, Object> resposta = service.preview(new FiltroExportacao(42L, " CAR-0001 ", null, null, null));

        assertEquals(1L, resposta.get("total_registros"));
        verify(jdbcTemplate, never()).query(
                argThat((String sql) -> sql != null && sql.contains("base_localizacao_cache")),
                ArgumentMatchers.<ResultSetExtractor<Long>>any(),
                any());
        verify(jdbcTemplate, never()).queryForObject(
                argThat((String sql) -> sql != null && sql.contains("vw_relatorio_exportacao_oficial")),
                eq(Long.class),
                any(Object[].class));
    }

    @Test
    @DisplayName("Caso 3: filtros de sobreposição continuam contando na vw_relatorio_exportacao_oficial")
    void deveUsarViewOficialQuandoFiltroDeSobreposicaoAtivo() {
        when(jdbcTemplate.queryForObject(
                argThat((String sql) -> sql != null && sql.contains("vw_relatorio_exportacao_oficial")),
                eq(Long.class),
                any(Object[].class)))
                .thenReturn(7L);

        Map<String, Object> resposta = service.preview(
                new FiltroExportacao(42L, null, true, new BigDecimal("1.5"), "FUNAI_TERRA_INDIGENA"));

        assertEquals(7L, resposta.get("total_registros"));
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Long.class), any(Object[].class));
        assertEquals(true, sqlCaptor.getValue().contains("vw_relatorio_exportacao_oficial"));
    }
}
