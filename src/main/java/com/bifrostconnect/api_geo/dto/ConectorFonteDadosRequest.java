package com.bifrostconnect.api_geo.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;

public record ConectorFonteDadosRequest(
        @NotBlank(message = "Nome do conector é obrigatório.")
        String nome,
        String descricao,
        @NotBlank(message = "Tipo de protocolo é obrigatório.")
        String tipoProtocolo,
        @NotBlank(message = "URL base é obrigatória.")
        String urlBase,
        @NotBlank(message = "Destino é obrigatório.")
        String destino,
        String fonteReferencia,
        String nomeCamadaExibicao,
        Long orgaoId,
        Long conjuntoId,
        @NotBlank(message = "Formato de dados é obrigatório.")
        String formatoDados,
        String epsgOrigem,
        String colunaIdentificador,
        Map<String, Object> parametrosJson,
        @NotBlank(message = "Tipo de autenticação é obrigatório.")
        String tipoAutenticacao,
        String nomeCredencial,
        String credencial,
        @NotBlank(message = "Periodicidade é obrigatória.")
        String periodicidade,
        String expressaoCron,
        Boolean ativo
) {
    public ConectorFonteDadosRequest {
        if (epsgOrigem == null || epsgOrigem.isBlank()) {
            epsgOrigem = "4674";
        }
        if (colunaIdentificador == null || colunaIdentificador.isBlank()) {
            colunaIdentificador = "cod_imovel,ogc_fid";
        }
        if (parametrosJson == null) {
            parametrosJson = Map.of();
        }
        if (ativo == null) {
            ativo = Boolean.TRUE;
        }
    }
}
