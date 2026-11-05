package com.bifrostconnect.api_geo.exception;

public class BaseIndisponivelException extends RuntimeException {

    private final String etapaAtual;
    private final String situacao;

    public BaseIndisponivelException(String message, String etapaAtual, String situacao) {
        super(message);
        this.etapaAtual = etapaAtual;
        this.situacao = situacao;
    }

    public String getEtapaAtual() {
        return etapaAtual;
    }

    public String getSituacao() {
        return situacao;
    }
}
