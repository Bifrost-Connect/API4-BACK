package com.bifrostconnect.api_geo.dto;

// safraAId/safraBId são os ids das cargas (processo). idRegiao e bbox são opcionais
// e excludentes: sem nenhum dos dois a comparação usa a extensão das duas cargas
public record EvolucaoRequestDTO(
    Long safraAId,
    Long safraBId,
    Long idRegiao,
    String bbox
) {}
