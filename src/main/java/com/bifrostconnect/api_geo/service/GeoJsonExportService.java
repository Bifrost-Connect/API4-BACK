package com.bifrostconnect.api_geo.service;

import com.bifrostconnect.api_geo.dto.ExportacaoMetricaDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GeoJsonExportService {

    private final ObjectMapper objectMapper;

    // Construtor manual (sem Lombok) para injetar o ObjectMapper do Spring
    public GeoJsonExportService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String gerarGeoJson(List<ExportacaoMetricaDTO> dados) {
        // Tarefa 11: Estruturar como FeatureCollection
        ObjectNode featureCollection = objectMapper.createObjectNode();
        featureCollection.put("type", "FeatureCollection");
        ArrayNode features = featureCollection.putArray("features");

        for (ExportacaoMetricaDTO dado : dados) {
            ObjectNode feature = objectMapper.createObjectNode();
            feature.put("type", "Feature");

            // Propriedades: indicadores analíticos calculados e identificação oficial
            ObjectNode properties = objectMapper.createObjectNode();
            properties.put("areaTotalHa", dado.areaTotalHa());
            properties.put("areaSobrepostaHa", dado.areaSobrepostaHa());
            properties.put("percentualSobreposicao", dado.percentualSobreposicao());
            properties.put("orgao", dado.orgao());
            properties.put("safra", dado.safra());
            properties.put("versaoOficial", dado.versaoOficial());
            properties.put("hashRastreabilidade", dado.hashRastreabilidade());
            
            feature.set("properties", properties);

            // Geometria validada/publicada
            try {
                if (dado.geometriaGeoJson() != null && !dado.geometriaGeoJson().isBlank()) {
                    // Converte a string GeoJSON (ex: gerada pelo PostGIS) de volta para um nó JSON
                    feature.set("geometry", objectMapper.readTree(dado.geometriaGeoJson()));
                } else {
                    feature.putNull("geometry");
                }
            } catch (Exception e) {
                feature.putNull("geometry");
            }

            features.add(feature);
        }

        return featureCollection.toString();
    }
}