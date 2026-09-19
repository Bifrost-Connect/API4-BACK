package com.bifrostconnect.api_geo.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

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

    @Column(name = "ano_safra", nullable = false)
    private String anoSafra;

   @Column(name = "epsg_origem", nullable = false)
    private String epsgOrigem;

    @Column(name = "data_criacao", insertable = false, updatable = false)
    private LocalDateTime dataCriacao;
}