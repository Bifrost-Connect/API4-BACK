package com.bifrostconnect.api_geo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

class CamadasMapaServiceTest {

    private static final String GEOJSON_VAZIO = "{\"type\":\"FeatureCollection\",\"features\":[]}";

    private JdbcTemplate jdbcTemplate;
    private CamadasMapaService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new CamadasMapaService(jdbcTemplate);
    }

    @Test
    @DisplayName("Reutiliza ObjectMapper estático e final em vez de instanciar por chamada")
    void deveDeclararObjectMapperEstaticoFinal() throws NoSuchFieldException {
        Field field = CamadasMapaService.class.getDeclaredField("OBJECT_MAPPER");
        assertTrue(Modifier.isStatic(field.getModifiers()));
        assertTrue(Modifier.isFinal(field.getModifiers()));
        assertEquals(ObjectMapper.class, field.getType());
    }

    @Test
    @DisplayName("Usa base_localizacao_cache quando filtrado apenas por processoId")
    void deveUsarCacheQuandoFiltradoApenasPorProcessoId() {
        when(jdbcTemplate.queryForList(
                "SELECT total_feicoes FROM base_localizacao_cache WHERE processo_id = ?",
                Long.class,
                42L))
                .thenReturn(List.of(25L));
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(GEOJSON_VAZIO);

        Map<String, Object> resposta = service.buscarCamadasPorFiltros(
                null, "   ", "  ", 0, 10, " ", 42L);

        assertEquals(25L, resposta.get("totalElements"));
        assertEquals(3L, resposta.get("totalPages"));
        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }

    @Test
    @DisplayName("Faz fallback para COUNT(*) quando cache estiver vazio ou nulo")
    void deveFazerFallbackParaCountQuandoCacheVazioOuNulo() {
        when(jdbcTemplate.queryForList(
                "SELECT total_feicoes FROM base_localizacao_cache WHERE processo_id = ?",
                Long.class,
                42L))
                .thenReturn(List.of())
                .thenReturn(Collections.singletonList(null));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(7L);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(GEOJSON_VAZIO);

        Map<String, Object> semCache = service.buscarCamadasPorFiltros(null, null, null, 0, 10, null, 42L);
        Map<String, Object> valorNulo = service.buscarCamadasPorFiltros(null, null, null, 0, 10, null, 42L);

        assertEquals(7L, semCache.get("totalElements"));
        assertEquals(7L, valorNulo.get("totalElements"));
    }

    @Test
    @DisplayName("Ignora cache quando houver outros filtros além de processoId")
    void deveIgnorarCacheQuandoHouverOutrosFiltros() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(2L);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(GEOJSON_VAZIO);

        service.buscarCamadasPorFiltros(null, null, "-49,-16,-48,-15", 0, 10, null, 42L);
        service.buscarCamadasPorFiltros(1L, null, null, 0, 10, null, 42L);
        service.buscarCamadasPorFiltros(null, "2026", null, 0, 10, null, 42L);
        service.buscarCamadasPorFiltros(null, null, null, 0, 10, "CAR-1", 42L);

        verify(jdbcTemplate, never()).queryForList(anyString(), eq(Long.class), any());
    }
}
