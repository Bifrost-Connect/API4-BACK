package com.bifrostconnect.api_geo.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "log_processamento")
@Data
public class LogProcessamento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "processo_id", nullable = false)
    private Long processoId;

    @Column(name = "processo_etapa_id", nullable = false)
    private Long processoEtapaId;

    @Column(name = "nivel", nullable = false)
    private String nivel;

    @Column(name = "mensagem", nullable = false)
    private String mensagem;

    @Column(name = "data_hora", nullable = false)
    private LocalDateTime dataHora;
}