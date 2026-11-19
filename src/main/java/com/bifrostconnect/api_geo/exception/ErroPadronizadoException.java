package com.bifrostconnect.api_geo.exception;

import org.springframework.http.HttpStatus;

// US12 - Tarefa 16: erro com status e código fixo (ex.: ESCOPO_INSUFICIENTE, CHAVE_DE_OUTRO_USUARIO).
// O GlobalExceptionHandler transforma no corpo padrão do RespostaErroPadrao
public class ErroPadronizadoException extends RuntimeException {

    private final HttpStatus status;
    private final String codigo;

    public ErroPadronizadoException(HttpStatus status, String codigo, String mensagem) {
        super(mensagem);
        this.status = status;
        this.codigo = codigo;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCodigo() {
        return codigo;
    }
}
