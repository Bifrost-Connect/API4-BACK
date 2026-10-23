package com.bifrostconnect.api_geo.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

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
    
    // Novos campos da Tarefa 15:
    private String statusAtual;
    private String etapaAtual;
    private List<HistoricoEtapaDTO> historicoEtapas;
    private List<LogQuarentenaDTO> logsQuarentena;

    @Data
    @Builder
    public static class HistoricoEtapaDTO {
        private String etapa;
        private String situacao;
        private LocalDateTime dataInicio;
        private LocalDateTime dataFim;
        private String mensagem;
        private String resultado;
    }

    @Data
    @Builder
    public static class LogQuarentenaDTO {
        private LocalDateTime dataDeteccao;
        private String etapaFalha;
        private String severidade;
        private String mensagemErro;
    }
}