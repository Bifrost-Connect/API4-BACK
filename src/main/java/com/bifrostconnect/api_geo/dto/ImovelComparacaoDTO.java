package com.bifrostconnect.api_geo.dto;

public record ImovelComparacaoDTO(
    String identificadorCar,
    double areaSafraA,
    double areaSafraB,
    double variacaoHectares,
    String statusEvolucao // Ex: "AUMENTO", "REDUCAO", "ESTAVEL"
) {}