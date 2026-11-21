package com.bifrostconnect.api_geo.dto;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

// Tarefa 11 - US12: corpo do POST /api/integracoes/chaves e /api/admin/chaves-api
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
        @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
        @Future(message = "A data de expiração deve ser futura.")
        LocalDateTime dataExpiracao
) {

    /**
     * Aceita timestamps ISO-8601 com offset/Z (convertidos para UTC) ou locais sem offset.
     */
    static final class FlexibleLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String texto = p.getValueAsString();
            if (texto == null || texto.isBlank()) {
                return null;
            }
            String valor = texto.trim();
            try {
                return LocalDateTime.ofInstant(OffsetDateTime.parse(valor).toInstant(), ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                try {
                    return LocalDateTime.parse(valor);
                } catch (DateTimeParseException e) {
                    throw InvalidFormatException.from(
                            p,
                            "Data de expiração inválida. Use ISO-8601 (ex.: 2027-12-31T23:59:59Z).",
                            valor,
                            LocalDateTime.class);
                }
            }
        }
    }
}
