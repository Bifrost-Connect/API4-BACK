package com.bifrostconnect.api_geo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.bifrostconnect.api_geo.dto.ResultadoCalculoAnalitico;
import com.bifrostconnect.api_geo.dto.ResultadoCalculoAnalitico.Categoria;
import com.bifrostconnect.api_geo.entity.ArquivoProcessado;
import com.bifrostconnect.api_geo.entity.Processo;

class CalculoAnaliticoServiceTest {

    // finge ser o banco: devolve as áreas e as linhas que o RETURNING traria
    private static class BancoFalso extends JdbcTemplate {
        final List<String> sqls = new ArrayList<>();
        final List<Object[]> argumentos = new ArrayList<>();
        List<BigDecimal> areas = List.of();
        List<Map<String, Object>> cruzamentos = List.of();

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            sqls.add(sql);
            argumentos.add(args);
            List<Map<String, Object>> linhas = sql.contains("UPDATE feicao_carga")
                    ? areas.stream().map(a -> Map.<String, Object>of("hectares", a)).toList()
                    : cruzamentos;

            List<T> resultado = new ArrayList<>();
            try {
                for (int i = 0; i < linhas.size(); i++) {
                    Map<String, Object> linha = linhas.get(i);
                    ResultSet rs = Mockito.mock(ResultSet.class);
                    Mockito.when(rs.getBigDecimal(Mockito.anyString()))
                            .thenAnswer(inv -> (BigDecimal) linha.get(inv.getArgument(0, String.class)));
                    Mockito.when(rs.getString(Mockito.anyString()))
                            .thenAnswer(inv -> (String) linha.get(inv.getArgument(0, String.class)));
                    resultado.add(rowMapper.mapRow(rs, i));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return resultado;
        }
    }

    private static Map<String, Object> cruzamento(String imovel, String fonte, String alerta, String hectares) {
        return Map.of("codigo_imovel", imovel, "fonte_camada", fonte,
                "codigo_alerta_app", alerta, "hectares_sobrepostos", new BigDecimal(hectares));
    }

    private static Processo processo() {
        Processo processo = new Processo();
        processo.setId(7L);
        return processo;
    }

    private static ArquivoProcessado copia() {
        ArquivoProcessado copia = new ArquivoProcessado();
        copia.setId(42L);
        return copia;
    }

    @Test
    void somaAreaECruzamentosPorCategoria() {
        BancoFalso banco = new BancoFalso();
        banco.areas = List.of(new BigDecimal("10.5000"), new BigDecimal("2.2500"));
        banco.cruzamentos = List.of(
                cruzamento("CAR-1", "INCAR_APP", "APP-9", "1.0000"),
                cruzamento("CAR-1", "INPE_DESMATAMENTO_CERRADO", "D-1", "0.5000"),
                cruzamento("CAR-2", "IBAMA_EMBARGO", "E-3", "0.2500"));

        CalculoAnaliticoService service = new CalculoAnaliticoService(banco,
                List.of("INCAR_APP"), List.of("INCAR_RESERVA_LEGAL"),
                List.of("INPE_DESMATAMENTO_CERRADO", " IBAMA_EMBARGO "));

        ResultadoCalculoAnalitico resultado = service.calcular(processo(), copia());

        assertEquals(2, resultado.poligonosCalculados());
        assertEquals(new BigDecimal("12.7500"), resultado.areaTotalHectares());
        assertEquals(new BigDecimal("1.0000"), resultado.hectaresPorCategoria(Categoria.APP));
        assertEquals(BigDecimal.ZERO, resultado.hectaresPorCategoria(Categoria.RESERVA_LEGAL));
        assertEquals(new BigDecimal("0.7500"), resultado.hectaresPorCategoria(Categoria.ALERTA));
        assertEquals(2, resultado.contar(Categoria.ALERTA));
    }

    @Test
    void passaAsFontesConfiguradasComoParametro() {
        BancoFalso banco = new BancoFalso();
        CalculoAnaliticoService service = new CalculoAnaliticoService(banco,
                List.of("INCAR_APP"), List.of(""), List.of("IBAMA_EMBARGO"));

        service.calcular(processo(), copia());

        String sqlCruzamento = banco.sqls.get(1);
        assertTrue(sqlCruzamento.contains("cr.fonte IN (?, ?)"));
        assertEquals(List.of(7L, 42L, "INCAR_APP", "IBAMA_EMBARGO", 42L,
                        ValidacaoTopologicaService.TOLERANCIA_SOBREPOSICAO_HA),
                List.of(banco.argumentos.get(1)));
    }

    @Test
    void semFonteConfiguradaSoCalculaArea() {
        BancoFalso banco = new BancoFalso();
        banco.areas = List.of(new BigDecimal("3.0000"));
        CalculoAnaliticoService service = new CalculoAnaliticoService(banco, List.of(""), List.of(), List.of());

        ResultadoCalculoAnalitico resultado = service.calcular(processo(), copia());

        assertEquals(1, banco.sqls.size());
        assertFalse(banco.sqls.get(0).contains("resultados_calculos"));
        assertTrue(resultado.cruzamentos().isEmpty());
        assertEquals(new BigDecimal("3.0000"), resultado.areaTotalHectares());
    }
}
