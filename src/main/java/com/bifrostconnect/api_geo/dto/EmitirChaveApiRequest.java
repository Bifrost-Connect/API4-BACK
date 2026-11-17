package com.bifrostconnect.api_geo.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

// Tarefa 11 - US12: corpo do POST /api/integracoes/chaves
// Colunas de chaves_api: nome_parceiro VARCHAR(120), escopos JSONB (array), data_expiracao opcional.
public record EmitirChaveApiRequest(

        @JsonAlias("nome_parceiro")
        @NotBlank(message = "Informe o nome do parceiro.")
        @Size(max = 120, message = "O nome do parceiro deve ter no máximo 120 caracteres.")
        String nomeParceiro,

        @NotEmpty(message = "Informe ao menos um escopo.")
        @Size(max = 20, message = "Informe no máximo 20 escopos.")
        List<@NotBlank(message = "Escopo não pode ser vazio.")
             @Size(max = 60, message = "Cada escopo deve ter no máximo 60 caracteres.") String> escopos,

        @JsonAlias("data_expiracao")
        @Future(message = "A data de expiração deve ser futura.")
        LocalDateTime dataExpiracao
) {
}