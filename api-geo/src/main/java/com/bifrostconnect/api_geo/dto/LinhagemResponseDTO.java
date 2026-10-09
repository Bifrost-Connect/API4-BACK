package com.bifrostconnect.api_geo.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class LinhagemResponseDTO {
    private Long indicadorId;
    private Long processoId;
    private Long arquivoOriginalId;
    private String nomeArquivoOriginal;
    private String hashSha256;
    private String parametrosProcessamento;
    private LocalDateTime dataCarga;
}