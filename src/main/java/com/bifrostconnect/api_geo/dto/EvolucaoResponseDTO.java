package com.bifrostconnect.api_geo.dto;

public record EvolucaoResponseDTO(
    double areaSafraAHectares,
    double areaSafraBHectares,
    double saldoVariacaoHectares,
    double percentualVariacao,
    String geoJsonClassificado // Contém as features com as propriedades: NOVO_ALERTA, REDUCAO_ALERTA, MANTIDO
) {}