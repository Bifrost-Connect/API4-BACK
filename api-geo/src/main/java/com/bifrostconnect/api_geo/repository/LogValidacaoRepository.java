
package com.bifrostconnect.api_geo.repository;

import com.bifrostconnect.api_geo.entity.LogValidacao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LogValidacaoRepository extends JpaRepository<LogValidacao, Long> {

    @Query(value = """
        SELECT ST_Area(ST_Transform(geom, 5880)) / 10000.0
        FROM log_validacao
        WHERE id = :id
          AND geom IS NOT NULL
        """, nativeQuery = true)
    Double calcularAreaEmHectares(@Param("id") Long id);
}
