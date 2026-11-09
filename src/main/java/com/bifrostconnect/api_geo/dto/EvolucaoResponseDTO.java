package com.bifrostconnect.api_geo.dto;

public record EvolucaoResponseDTO(
    double areaSafraAHectares,
    double areaSafraBHectares,
    double saldoVariacaoHectares,
    double percentualVariacao,
    double areaAumentoHectares, // B - A (NOVO_ALERTA)
    double areaReducaoHectares, // A - B (REDUCAO_ALERTA)
    double areaMantidaHectares, // A ∩ B (MANTIDO)
    boolean simplificada, // Tarefa 18: true quando o GeoJSON voltou com ST_SimplifyPreserveTopology
    String geoJsonClassificado // Contém as features com as propriedades: NOVO_ALERTA, REDUCAO_ALERTA, MANTIDO
) {}
