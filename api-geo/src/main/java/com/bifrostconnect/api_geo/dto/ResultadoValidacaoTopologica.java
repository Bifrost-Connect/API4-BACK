package com.bifrostconnect.api_geo.dto;

import java.math.BigDecimal;
import java.util.List;


public record ResultadoValidacaoTopologica(
        int totalFeicoes,
        int feicoesCarregadas,
        List<Problema> problemas) {

    // cada tipo já sabe qual regra_validacao usar na hora de gravar a ocorrência
    public enum TipoProblema {
        GEOMETRIA_INVALIDA("GEOMETRIA_INVALIDA"),
        CODIGO_IMOVEL_DUPLICADO("CAR_DUPLICADO"),
        POLIGONO_DUPLICADO("POLIGONO_DUPLICADO"),
        POLIGONO_SOBREPOSTO("SOBREPOSICAO_PROPRIEDADE");

        private final String regra;

        TipoProblema(String regra) {
            this.regra = regra;
        }

        public String getRegra() {
            return regra;
        }
    }

    // codigoImovelRelacionado e hectares só vêm preenchidos quando o problema envolve
    // dois polígonos (duplicado ou sobreposto)
    public record Problema(
            TipoProblema tipo,
            String codigoImovel,
            String codigoImovelRelacionado,
            BigDecimal hectaresSobrepostos,
            String mensagem) {

        public static Problema daFeicao(TipoProblema tipo, String codigoImovel, String mensagem) {
            return new Problema(tipo, codigoImovel, null, null, mensagem);
        }
    }

    public boolean possuiProblemas() {
        return !problemas.isEmpty();
    }

    public long contar(TipoProblema tipo) {
        return problemas.stream().filter(p -> p.tipo() == tipo).count();
    }
}
