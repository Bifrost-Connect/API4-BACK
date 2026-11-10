package com.bifrostconnect.api_geo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bifrostconnect.api_geo.exception.RequisicaoInvalidaException;
import com.bifrostconnect.api_geo.service.CatalogoBasesService.FiltroCatalogo;
import com.fasterxml.jackson.databind.ObjectMapper;

class CatalogoBasesServiceTest {

    private static final FiltroCatalogo SEM_FILTRO = new FiltroCatalogo(null, null, null, null, null, null, false);

    private JdbcTemplate jdbcTemplate;
    private CatalogoBasesService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new CatalogoBasesService(jdbcTemplate, new ObjectMapper());
    }

    @ParameterizedTest(name = "{0} itens com size {1} = {2} páginas")
    @CsvSource({"0, 20, 0", "1, 20, 1", "20, 20, 1", "21, 20, 2", "100, 10, 10", "101, 10, 11", "5, 1, 5"})
    @DisplayName("total_pages sempre bate com total_items / size arredondado pra cima")
    void deveCalcularTotalDePaginas(long totalItens, int size, int paginasEsperadas) {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(totalItens);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        Map<String, Object> pagina = service.listarBases(SEM_FILTRO, 0, size, null, null);

        assertEquals(totalItens, pagina.get("total_items"));
        assertEquals(paginasEsperadas, pagina.get("total_pages"));
        assertEquals(size, pagina.get("size"));
    }

    @Test
    @DisplayName("Todos os filtros juntos viram parâmetros (nada concatenado no SQL) e o OFFSET sai da página")
    void deveMontarFiltrosCombinados() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(50L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        FiltroCatalogo filtro = new FiltroCatalogo("50%_off", 3L, "2025", 7L, "Pública", "disponivel", true);
        service.listarBases(filtro, 2, 10, "ano_safra", "asc");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(sql.capture(), params.capture());

        String consulta = sql.getValue();
        assertTrue(consulta.contains("v.conjunto_id = ?"));
        assertTrue(consulta.contains("v.ano_safra = ?"));
        assertTrue(consulta.contains("v.orgao_id = ?"));
        assertTrue(consulta.contains("v.classificacao_acesso ILIKE ?"));
        assertTrue(consulta.contains("v.pronta_para_consulta"));
        assertTrue(consulta.contains("ORDER BY v.ano_safra asc"));
        assertFalse(consulta.contains("50%_off"), "o termo de busca não pode ir direto pro SQL");

        List<Object> valores = List.of(params.getValue());
        // % e _ do termo são escapados pra não virarem curinga do LIKE
        assertEquals("%50\\%\\_off%", valores.get(0));
        assertTrue(valores.containsAll(List.of(3L, "2025", 7L, "Pública", "disponivel")));
        // LIMIT 10 OFFSET 20 (página 2)
        assertEquals(10, valores.get(valores.size() - 2));
        assertEquals(20L, valores.get(valores.size() - 1));
    }

    @ParameterizedTest(name = "page={0}, size={1}")
    @CsvSource({"-1, 20", "0, 0", "0, 101", "0, -5"})
    @DisplayName("Limites de paginação fora da faixa são recusados")
    void deveRecusarPaginacaoForaDosLimites(int page, int size) {
        assertThrows(RequisicaoInvalidaException.class,
                () -> service.listarBases(SEM_FILTRO, page, size, null, null));
    }

    @Test
    @DisplayName("Ordenação fora da lista branca é recusada")
    void deveRecusarOrdenacaoDesconhecida() {
        assertThrows(RequisicaoInvalidaException.class,
                () -> service.listarBases(SEM_FILTRO, 0, 20, "senha_hash", "asc"));
        assertThrows(RequisicaoInvalidaException.class,
                () -> service.listarBases(SEM_FILTRO, 0, 20, "id", "asc; DROP TABLE processo"));
    }

    @Test
    @DisplayName("Base não publicada perde caminho, geometria e metadados internos na listagem")
    void deveSanitizarBaseNaoPublicada() {
        Map<String, Object> naoPublicada = new LinkedHashMap<>();
        naoPublicada.put("id", 1L);
        naoPublicada.put("conjunto", "CAR");
        naoPublicada.put("ano_safra", "2025");
        naoPublicada.put("orgao", "SEMA");
        naoPublicada.put("etapa_atual", "VALIDACAO");
        naoPublicada.put("situacao", "EM_VALIDACAO");
        naoPublicada.put("status_operacional", "EM_QUARENTENA");
        naoPublicada.put("pronta_para_consulta", false);
        naoPublicada.put("epsg_destino", "4674");
        naoPublicada.put("cobertura_espacial", "MT");
        naoPublicada.put("total_feicoes", 99L);

        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(naoPublicada));

        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) service
                .listarBases(SEM_FILTRO, 0, 20, null, null).get("items")).get(0);

        assertEquals("EM_QUARENTENA", item.get("status_operacional"));
        assertEquals(false, item.get("pronta_para_consulta"));
        assertFalse(item.containsKey("epsg_destino"));
        assertFalse(item.containsKey("cobertura_espacial"));
        assertFalse(item.containsKey("total_feicoes"));
    }
}
