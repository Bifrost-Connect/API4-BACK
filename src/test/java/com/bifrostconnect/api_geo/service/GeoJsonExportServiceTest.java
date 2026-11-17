package com.bifrostconnect.api_geo.service;

import com.bifrostconnect.api_geo.dto.ExportacaoMetricaDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeoJsonExportServiceTest {

    private GeoJsonExportService geoJsonExportService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        geoJsonExportService = new GeoJsonExportService(objectMapper);
    }

    @Test
    @DisplayName("Deve gerar FeatureCollection estruturado corretamente")
    void deveGerarFeatureCollection() throws Exception {
        // Arrange
        String geometriaMock = "{\"type\":\"Polygon\",\"coordinates\":[[[-54,-20],[-54,-21],[-55,-21],[-54,-20]]]}";
        
        ExportacaoMetricaDTO dado = new ExportacaoMetricaDTO(
                150.5, 20.0, 13.2, "INPE", "2026", "V1-FINAL", "abc-123", geometriaMock
        );

        // Act
        String resultadoJson = geoJsonExportService.gerarGeoJson(List.of(dado));
        JsonNode raiz = objectMapper.readTree(resultadoJson);

        // Assert
        assertEquals("FeatureCollection", raiz.get("type").asText());
        assertTrue(raiz.has("features"));
        assertTrue(raiz.get("features").isArray());
        assertEquals(1, raiz.get("features").size());

        JsonNode feature = raiz.get("features").get(0);
        assertEquals("Feature", feature.get("type").asText());
        
        // Verifica Properties
        JsonNode properties = feature.get("properties");
        assertEquals(150.5, properties.get("areaTotalHa").asDouble());
        assertEquals("INPE", properties.get("orgao").asText());
        assertEquals("abc-123", properties.get("hashRastreabilidade").asText());

        // Verifica Geometry
        JsonNode geometry = feature.get("geometry");
        assertEquals("Polygon", geometry.get("type").asText());
        assertTrue(geometry.has("coordinates"));
    }

    @Test
    @DisplayName("Deve tratar geometria nula ou inválida sem quebrar")
    void deveTratarGeometriaNula() throws Exception {
        // Arrange
        ExportacaoMetricaDTO dadoSemGeometria = new ExportacaoMetricaDTO(
                10.0, 0.0, 0.0, "IBAMA", "2026", "V1", "hash", null
        );
        
        ExportacaoMetricaDTO dadoGeometriaInvalida = new ExportacaoMetricaDTO(
                10.0, 0.0, 0.0, "IBAMA", "2026", "V1", "hash", "isto-nao-e-json"
        );

        // Act
        String resultadoJson = geoJsonExportService.gerarGeoJson(List.of(dadoSemGeometria, dadoGeometriaInvalida));
        JsonNode raiz = objectMapper.readTree(resultadoJson);

        // Assert
        assertEquals(2, raiz.get("features").size());
        assertTrue(raiz.get("features").get(0).get("geometry").isNull());
        assertTrue(raiz.get("features").get(1).get("geometry").isNull());
    }
}