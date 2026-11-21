package com.bifrostconnect.api_geo.dto;

import java.time.LocalDateTime;

public record AirflowIngestaoStatusResponse(
        Long id,
        Long conectorId,
        String conectorNome,
        String dagRunId,
        String nomeArquivo,
        String driveCaminho,
        String destino,
        String fonte,
        String statusBanco,
        String etapaUi,
        Long processoId,
        String etapaProcesso,
        String situacaoProcesso,
        Long linhasCarregadas,
        Long linhasDescartadas,
        Long tamanhoBytes,
        String hashSha256,
        String md5Drive,
        String mensagem,
        LocalDateTime dataInicio,
        LocalDateTime dataFim,
        Long duracaoSegundos
) {
}
