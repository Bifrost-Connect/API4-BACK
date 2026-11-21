package com.bifrostconnect.api_geo.dto;

public record ExportacaoMetricaDTO(
    double areaTotalHa,
    double areaSobrepostaHa,
    double percentualSobreposicao,
    String orgao,
    String safra,
    String versaoOficial,
    String hashRastreabilidade,
    String geometriaGeoJson // String gerada pelo PostGIS (ST_AsGeoJSON) para a Tarefa 11
) {}