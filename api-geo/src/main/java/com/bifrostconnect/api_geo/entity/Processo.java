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

    @Column(name = "orgao_id", nullable = false)
    private Long orgaoId;

    @Column(name = "conjunto_id", nullable = false)
    private Long conjuntoId;

    @Column(name = "operador_id", nullable = false)
    private Long operadorId; // Ajustado conforme a tabela real do banco

    @Column(name = "ano_safra", nullable = false)
    private String ano; // Mantém o atributo como 'ano' para usar setAno()

    @Column(name = "epsg_origem", nullable = false)
    private String epsg; // Ajustado para 'epsg_origem' e tipo String para aceitar texto
    
    @Column(name = "data_criacao", insertable = false, updatable = false)
    private LocalDateTime dataCriacao;
}