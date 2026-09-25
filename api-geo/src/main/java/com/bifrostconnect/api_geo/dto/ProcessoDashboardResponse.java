package com.bifrostconnect.api_geo.dto;

import java.time.LocalDateTime;

public record ProcessoDashboardResponse(

        Long id,

        Long operadorId,

        Long conjuntoId,

        Long orgaoId,

        String anoSafra,

        String epsgOrigem,

        Long etapaId,

        Long situacaoId,

        LocalDateTime dataCriacao

) {

}