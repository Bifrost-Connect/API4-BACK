package com.bifrostconnect.api_geo.exception;

// parâmetro de consulta inválido (paginação, ordenação...). Vira 400 no GlobalExceptionHandler
public class RequisicaoInvalidaException extends RuntimeException {

    public RequisicaoInvalidaException(String message) {
        super(message);
    }
}
