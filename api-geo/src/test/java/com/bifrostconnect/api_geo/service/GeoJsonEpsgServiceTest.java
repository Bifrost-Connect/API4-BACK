package com.bifrostconnect.api_geo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class GeoJsonEpsgServiceTest {
    @TempDir Path tempDir;

    @Test
    void usa4326QuandoGeoJsonNaoDeclaraCrs() throws Exception {
        Path arquivo = tempDir.resolve("sem-crs.geojson");
        Files.writeString(arquivo, """
                {"type":"FeatureCollection","features":[]}
                """);
        GeoJsonEpsgService service = new GeoJsonEpsgService(mock(JdbcTemplate.class));
        assertEquals("4326", service.detectar(arquivo));
    }

    @Test
    void leEpsgDosMetadadosAntigosDoGeoJson() throws Exception {
        Path arquivo = tempDir.resolve("epsg-31983.geojson");
        Files.writeString(arquivo, """
                {"type":"FeatureCollection","crs":{"type":"name","properties":{"name":"urn:ogc:def:crs:EPSG::31983"}},"features":[]}
                """);
        GeoJsonEpsgService service = new GeoJsonEpsgService(mock(JdbcTemplate.class));
        assertEquals("31983", service.detectar(arquivo));
    }

    @Test
    void rejeitaArquivoQueNaoSejaGeoJson() throws Exception {
        Path arquivo = tempDir.resolve("arquivo.zip");
        Files.writeString(arquivo, "x");
        GeoJsonEpsgService service = new GeoJsonEpsgService(mock(JdbcTemplate.class));
        assertThrows(IllegalArgumentException.class, () -> service.detectar(arquivo));
    }
}
