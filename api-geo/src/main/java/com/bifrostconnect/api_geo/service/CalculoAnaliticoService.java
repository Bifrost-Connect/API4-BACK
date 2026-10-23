package com.bifrostconnect.api_geo.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.bifrostconnect.api_geo.dto.ResultadoCalculoAnalitico;
import com.bifrostconnect.api_geo.dto.ResultadoCalculoAnalitico.Categoria;
import com.bifrostconnect.api_geo.dto.ResultadoCalculoAnalitico.Cruzamento;
import com.bifrostconnect.api_geo.entity.ArquivoProcessado;
import com.bifrostconnect.api_geo.entity.Processo;

// para cada polígono válido calcula a área em hectares e cruza
// com as camadas de APP, Reserva Legal e Alertas da camada_referencia
@Service
public class CalculoAnaliticoService {

    // a área vai pro JSONB da própria feição; a feicao_carga não tem coluna pra isso.
    // A geography é pra área sair em m² de verdade (a geometria está em graus, SIRGAS 2000)
    private static final String SQL_AREA = """
            UPDATE feicao_carga fc
               SET atributos = COALESCE(fc.atributos, '{}'::jsonb) || jsonb_build_object('area_hectares', a.hectares)
              FROM (SELECT id, ROUND((ST_Area(geom::geography) / 10000)::numeric, 4) AS hectares
                      FROM feicao_carga
                     WHERE arquivo_processado_id = ?
                       AND ST_IsValid(geom)) a
             WHERE fc.id = a.id
            RETURNING a.hectares
            """;

    private static final String SQL_CRUZAMENTOS = """
            INSERT INTO resultados_calculos
                   (processo_id, arquivo_processado_id, codigo_imovel, fonte_camada, codigo_alerta_app, hectares_sobrepostos)
            SELECT ?, ?, s.codigo_imovel, s.fonte, s.codigo_alerta, s.hectares
              FROM (SELECT fc.codigo_imovel, cr.fonte,
                           COALESCE(cr.identificador_origem, 'ID_' || cr.id) AS codigo_alerta,
                           ROUND((ST_Area(ST_Union(ST_CollectionExtract(ST_Intersection(fc.geom,
                                   CASE WHEN ST_IsValid(cr.geom) THEN cr.geom
                                        ELSE ST_CollectionExtract(ST_MakeValid(cr.geom), 3) END), 3))::geography)
                                  / 10000)::numeric, 4) AS hectares
                      FROM feicao_carga fc
                      JOIN camada_referencia cr
                        ON cr.geom && fc.geom
                       AND cr.fonte IN (%s)
                     WHERE fc.arquivo_processado_id = ?
                       AND ST_IsValid(fc.geom)
                       AND ST_Intersects(fc.geom, cr.geom)
                     GROUP BY fc.codigo_imovel, cr.fonte, COALESCE(cr.identificador_origem, 'ID_' || cr.id)) s
             WHERE s.hectares >= ?
            RETURNING codigo_imovel, fonte_camada, codigo_alerta_app, hectares_sobrepostos
            """;

    private final JdbcTemplate jdbcTemplate;

    // fonte da camada_referencia -> categoria do cálculo
    private final Map<String, Categoria> categoriaPorFonte = new LinkedHashMap<>();

    public CalculoAnaliticoService(
            JdbcTemplate jdbcTemplate,
            @Value("${calculo-analitico.fontes.app:}") List<String> fontesApp,
            @Value("${calculo-analitico.fontes.reserva-legal:}") List<String> fontesReservaLegal,
            @Value("${calculo-analitico.fontes.alertas:}") List<String> fontesAlertas) {
        this.jdbcTemplate = jdbcTemplate;
        mapear(fontesApp, Categoria.APP);
        mapear(fontesReservaLegal, Categoria.RESERVA_LEGAL);
        mapear(fontesAlertas, Categoria.ALERTA);
    }

    private void mapear(List<String> fontes, Categoria categoria) {
        for (String fonte : fontes) {
            String limpa = fonte.trim();
            if (!limpa.isEmpty()) {
                categoriaPorFonte.putIfAbsent(limpa, categoria);
            }
        }
    }

    public ResultadoCalculoAnalitico calcular(Processo processo, ArquivoProcessado copiaTrabalho) {
        Long arquivoId = copiaTrabalho.getId();

        List<BigDecimal> areas = jdbcTemplate.query(SQL_AREA, (rs, i) -> rs.getBigDecimal("hectares"), arquivoId);
        BigDecimal areaTotal = areas.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ResultadoCalculoAnalitico(areas.size(), areaTotal, cruzar(processo.getId(), arquivoId));
    }

    private List<Cruzamento> cruzar(Long processoId, Long arquivoId) {
        // sem nenhuma fonte configurada não tem com o que cruzar
        if (categoriaPorFonte.isEmpty()) {
            return Collections.emptyList();
        }

        List<Object> argumentos = new ArrayList<>();
        argumentos.add(processoId);
        argumentos.add(arquivoId);
        argumentos.addAll(categoriaPorFonte.keySet());
        argumentos.add(arquivoId);
        argumentos.add(ValidacaoTopologicaService.TOLERANCIA_SOBREPOSICAO_HA);

        String placeholders = String.join(", ", Collections.nCopies(categoriaPorFonte.size(), "?"));

        return jdbcTemplate.query(SQL_CRUZAMENTOS.formatted(placeholders), (rs, i) -> new Cruzamento(
                rs.getString("codigo_imovel"),
                categoriaPorFonte.get(rs.getString("fonte_camada")),
                rs.getString("fonte_camada"),
                rs.getString("codigo_alerta_app"),
                rs.getBigDecimal("hectares_sobrepostos")), argumentos.toArray());
    }
}
