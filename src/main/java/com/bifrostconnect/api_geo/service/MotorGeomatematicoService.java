package com.bifrostconnect.api_geo.service;

import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Service;

@Service
public class MotorGeomatematicoService {

    // Calcula áreas de desmatamento/novos alertas: Geometria B - Geometria A
    public Geometry calcularNovosAlertas(Geometry safraA, Geometry safraB) {
        if (safraB == null) return null;
        if (safraA == null) return safraB; // Tudo em B é novo se A não existe
        
        return safraB.difference(safraA);
    }

    // Calcula áreas reduzidas/regeneradas: Geometria A - Geometria B
    public Geometry calcularReducao(Geometry safraA, Geometry safraB) {
        if (safraA == null) return null;
        if (safraB == null) return safraA; // Tudo em A foi reduzido se B não existe
        
        return safraA.difference(safraB);
    }

    // Calcula áreas persistentes: Geometria A ∩ Geometria B
    public Geometry calcularPersistente(Geometry safraA, Geometry safraB) {
        if (safraA == null || safraB == null) return null;
        
        return safraA.intersection(safraB);
    }

    // Converte a área da geometria para hectares.
    // Nota: O método .getArea() retorna a área na unidade do CRS (Sistema de Coordenadas).
    // Se o CRS estiver em graus (EPSG:4326 / EPSG:4674), é necessário projetar para metros 
    // ou delegar o cálculo de área para uma query nativa do PostGIS com ST_Area(geography).
    // Assumindo que a geometria já está projetada em metros (ex: UTM ou SIRGAS 2000 Polyconic):
    public double calcularAreaEmHectares(Geometry geometria) {
        if (geometria == null || geometria.isEmpty()) {
            return 0.0;
        }
        // 1 Hectare = 10.000 metros quadrados
        return geometria.getArea() / 10000.0;
    }
}