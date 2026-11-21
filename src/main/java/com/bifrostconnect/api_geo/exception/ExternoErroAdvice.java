package com.bifrostconnect.api_geo.exception;

import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.bifrostconnect.api_geo.controller.ExternoConsumoController;
import com.bifrostconnect.api_geo.security.RespostaErroPadrao;

import jakarta.servlet.http.HttpServletRequest;

// US12 - Tarefa 16: só pros endpoints /v1/externo. Sem isso o ResponseStatusException caía no
// /error do Spring, que esconde a mensagem e devolve outro formato. O resto do sistema não muda
@RestControllerAdvice(assignableTypes = ExternoConsumoController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExternoErroAdvice {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(
            ResponseStatusException ex,
            HttpServletRequest request) {

        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());

        return ResponseEntity
                .status(status)
                .body(RespostaErroPadrao.corpo(
                        status, codigoPara(status), ex.getReason(), request.getRequestURI()));
    }

    private String codigoPara(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "REQUISICAO_INVALIDA";
            case UNAUTHORIZED -> "API_KEY_INVALIDA";
            case FORBIDDEN -> "ACESSO_NEGADO";
            case NOT_FOUND -> "RECURSO_NAO_ENCONTRADO";
            default -> status.name();
        };
    }
}
