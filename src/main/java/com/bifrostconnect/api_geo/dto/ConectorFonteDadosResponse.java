package com.bifrostconnect.api_geo.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record ConectorFonteDadosResponse(
        Long id,
        String nome,
        String descricao,
        String tipoProtocolo,
        String urlBase,
        String destino,
        String fonteReferencia,
        String nomeCamadaExibicao,
        Long orgaoId,
        String orgaoSigla,
        Long conjuntoId,
        String conjuntoNome,
        String formatoDados,
        String epsgOrigem,
        String colunaIdentificador,
        Map<String, Object> parametrosJson,
        String tipoAutenticacao,
        String nomeCredencial,
        String prefixoCredencial,
        boolean possuiCredencial,
        String periodicidade,
        String expressaoCron,
        boolean ativo,
        LocalDateTime ultimaExecucaoEm,
        LocalDateTime proximaExecucaoEm,
        String ultimoStatus,
        Long criadoPorId,
        LocalDateTime dataCriacao,
        LocalDateTime dataAtualizacao
) {
}
