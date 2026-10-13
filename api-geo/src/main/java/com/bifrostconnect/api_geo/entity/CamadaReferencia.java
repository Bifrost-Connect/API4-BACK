package com.bifrostconnect.api_geo.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Geometry;

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
@Table(name = "camada_referencia")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CamadaReferencia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fonte", nullable = false, length = 50)
    private String fonte;

    @Column(name = "nome_camada", nullable = false, length = 150)
    private String nomeCamada;

    @Column(name = "identificador_origem", length = 100)
    private String identificadorOrigem;

    @Column(name = "geom", nullable = false, columnDefinition = "geometry")
    private Geometry geom;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "atributos")
    private Map<String, Object> atributos;

    @Column(name = "data_referencia")
    private LocalDate dataReferencia;

    @Column(name = "data_carga", insertable = false, updatable = false)
    private LocalDateTime dataCarga;
}