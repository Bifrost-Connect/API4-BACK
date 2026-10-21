package com.bifrostconnect.api_geo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CamadasMapaService {

    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> buscarCamadasPorFiltros(Long conjuntoId, String anoSafra, String bbox) {
        StringBuilder sql = new StringBuilder("""
            SELECT json_build_object(
                'type', 'FeatureCollection',
                'features', COALESCE(json_agg(
                    json_build_object(
                        'type', 'Feature',
                        'geometry', ST_AsGeoJSON(fc.geom)::json,
                        'properties', json_build_object(
                            'id', fc.id,
                            'processoId', p.id,
                            'anoSafra', p.ano_safra
                        )
                    )
                ), '[]'::json)
            ) as geojson
            FROM feicao_carga fc
            JOIN arquivo_processado ap ON fc.arquivo_processado_id = ap.id
            JOIN processo p ON ap.processo_id = p.id
            WHERE 1=1
            """);

        List<Object> params = new ArrayList<>();

        if (conjuntoId != null) {
            sql.append(" AND p.conjunto_id = ?");
            params.add(conjuntoId);
        }

        if (anoSafra != null && !anoSafra.isBlank()) {
            sql.append(" AND p.ano_safra = ?");
            params.add(anoSafra);
        }

        // Se o Bounding Box foi informado (formato esperado: minLon,minLat,maxLon,maxLat)
        if (bbox != null && !bbox.isBlank()) {
            String[] coords = bbox.split(",");
            if (coords.length == 4) {
                sql.append(" AND fc.geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)");
                params.add(Double.parseDouble(coords[0].trim())); // minLon
                params.add(Double.parseDouble(coords[1].trim())); // minLat
                params.add(Double.parseDouble(coords[2].trim())); // maxLon
                params.add(Double.parseDouble(coords[3].trim())); // maxLat
            }
        }

        String geoJsonString = jdbcTemplate.queryForObject(sql.toString(), String.class, params.toArray());

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            return mapper.readValue(geoJsonString, Map.class);
        } catch (Exception e) {
            return Map.of("type", "FeatureCollection", "features", List.of());
        }
    }
}