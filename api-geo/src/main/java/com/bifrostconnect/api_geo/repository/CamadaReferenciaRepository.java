package com.bifrostconnect.api_geo.repository;

import com.bifrostconnect.api_geo.entity.CamadaReferencia;

import java.util.List;

import org.locationtech.jts.geom.Geometry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CamadaReferenciaRepository extends JpaRepository<CamadaReferencia, Long> {
/**
     * Busca sobreposições espaciais entre a geometria do imóvel e as camadas de referência filtradas por fonte.
     */
    @Query(value = """
        SELECT cr.* FROM camada_referencia cr
        WHERE cr.fonte IN (:fontes)
        AND ST_Intersects(cr.geom, :geometriaImovel) = true
        """, nativeQuery = true)
    List<CamadaReferencia> buscarSobreposicoes(
        @Param("fontes") List<String> fontes,
        @Param("geometriaImovel") Geometry geometriaImovel
    );

    /**
     * Identifica o município ou bioma em que o imóvel está contido.
     */
    @Query(value = """
        SELECT cr.* FROM camada_referencia cr
        WHERE cr.fonte = :fonte
        AND ST_Intersects(cr.geom, :geometriaImovel) = true
        """, nativeQuery = true)
    List<CamadaReferencia> buscarInterseccaoPorFonte(
        @Param("fonte") String fonte,
        @Param("geometriaImovel") Geometry geometriaImovel
    );

}