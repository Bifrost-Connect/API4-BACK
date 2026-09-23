package com.bifrostconnect.api_geo.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "processo")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Processo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operador_id", nullable = false)
    private Long operadorId;

    @Column(name = "conjunto_id", nullable = false)
    private Long conjuntoId;

    @Column(name = "orgao_id", nullable = false)
    private Long orgaoId;

    @Column(name = "etapa_atual_id")
    private Long etapaAtualId;

    @Column(name = "situacao_atual_id")
    private Long situacaoAtualId;

    @Column(name = "ano_safra", nullable = false, length = 10)
    private String anoSafra;

    @Column(name = "epsg_origem", nullable = false, length = 20)
    private String epsgOrigem;

    @Column(name = "epsg_destino", length = 20)
    private String epsgDestino;

    @Column(name = "data_criacao", insertable = false, updatable = false)
    private LocalDateTime dataCriacao;

    @Column(name = "data_inicio")
    private LocalDateTime dataInicio;

    @Column(name = "data_fim")
    private LocalDateTime dataFim;
}