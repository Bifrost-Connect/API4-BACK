package com.bifrostconnect.api_geo.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class GeoJsonEpsgService {
    private static final Pattern EPSG_PATTERN = Pattern.compile("(?i)(?:EPSG(?::|::|/|%3A)+|::)([0-9]{4,6})");
    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate;

    public GeoJsonEpsgService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String detectar(Path arquivo) {
        if (arquivo == null || !arquivo.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".geojson")) {
            throw new IllegalArgumentException("A etapa de EPSG aceita somente arquivos .geojson.");
        }
        try {
            JsonNode root = mapper.readTree(arquivo.toFile());
            if (root == null || !root.isObject() || !"FeatureCollection".equals(root.path("type").asText())) {
                throw new IllegalArgumentException("GeoJSON inválido: esperado um FeatureCollection.");
            }
            JsonNode crs = root.path("crs");
            if (crs.isMissingNode() || crs.isNull()) {
                return "4326";
            }
            String nome = crs.path("properties").path("name").asText("");
            if (nome.isBlank()) nome = crs.path("name").asText("");
            Matcher matcher = EPSG_PATTERN.matcher(nome);
            if (matcher.find()) return matcher.group(1);
            throw new IllegalArgumentException("O GeoJSON declara um CRS que não pôde ser interpretado: " + nome);
        } catch (IOException e) {
            throw new IllegalArgumentException("Não foi possível ler o GeoJSON para verificar o EPSG.", e);
        }
    }

    public void validarCodigoEpsg(String epsg) {
        if (epsg == null || !epsg.matches("[1-9][0-9]{0,5}")) {
            throw new IllegalArgumentException("EPSG é obrigatório e deve ser um código numérico válido.");
        }
        Boolean existe = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM spatial_ref_sys WHERE srid = ?)",
                Boolean.class, Integer.valueOf(epsg));
        if (!Boolean.TRUE.equals(existe)) {
            throw new IllegalArgumentException("O código EPSG:" + epsg + " não existe na tabela spatial_ref_sys do PostGIS.");
        }
    }
}
