package com.bifrostconnect.api_geo.security;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

// US12 - Tarefa 16: um formato só pros erros da integração externa (401, 403 e 429), venha ele do
// filtro da X-API-Key ou de dentro do controller. Mantém o "erro" com a mensagem, como o resto do
// projeto, e acrescenta um "codigo" fixo que o parceiro consegue tratar sem depender do texto
public final class RespostaErroPadrao {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RespostaErroPadrao() {
    }

    public static Map<String, Object> corpo(HttpStatus status, String codigo, String mensagem, String caminho) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("status", status.value());
        corpo.put("codigo", codigo);
        corpo.put("erro", mensagem);
        corpo.put("caminho", caminho);
        corpo.put("timestamp", Instant.now().toString());
        return corpo;
    }

    // usado no filtro, que roda antes do Spring MVC e por isso não passa pelos @ExceptionHandler
    public static void escrever(
            HttpServletResponse response,
            HttpStatus status,
            String codigo,
            String mensagem,
            String caminho) throws IOException {

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JSON.writeValueAsString(corpo(status, codigo, mensagem, caminho)));
    }
}
