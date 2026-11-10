package com.bifrostconnect.api_geo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import static org.junit.jupiter.api.Assertions.*;

class MotorGeomatematicoServiceTest {

    private MotorGeomatematicoService motor;
    private WKTReader wktReader;

    @BeforeEach
    void setUp() {
        motor = new MotorGeomatematicoService();
        wktReader = new WKTReader();
    }

    @Test
    void deveCalcularNovosAlertasCorretamente() throws ParseException {
        // Safra A: Quadrado de 0 a 10
        Geometry safraA = wktReader.read("POLYGON((0 0, 0 10, 10 10, 10 0, 0 0))");
        // Safra B: Quadrado deslocado de 5 a 15
        Geometry safraB = wktReader.read("POLYGON((5 0, 5 10, 15 10, 15 0, 5 0))");

        // Novos Alertas = B - A (A parte do B que não sobrepõe A: de x=10 a x=15)
        Geometry novosAlertas = motor.calcularNovosAlertas(safraA, safraB);

        assertNotNull(novosAlertas);
        // A área total de B é 100. A intersecção é 50. Logo, a diferença é 50.
        assertEquals(50.0, novosAlertas.getArea());
    }

    @Test
    void deveCalcularReducaoCorretamente() throws ParseException {
        Geometry safraA = wktReader.read("POLYGON((0 0, 0 10, 10 10, 10 0, 0 0))");
        Geometry safraB = wktReader.read("POLYGON((5 0, 5 10, 15 10, 15 0, 5 0))");

        // Redução = A - B (A parte do A que sumiu no B: de x=0 a x=5)
        Geometry reducao = motor.calcularReducao(safraA, safraB);

        assertNotNull(reducao);
        assertEquals(50.0, reducao.getArea());
    }

    @Test
    void deveCalcularPersistenteCorretamente() throws ParseException {
        Geometry safraA = wktReader.read("POLYGON((0 0, 0 10, 10 10, 10 0, 0 0))");
        Geometry safraB = wktReader.read("POLYGON((5 0, 5 10, 15 10, 15 0, 5 0))");

        // Persistente = A interseção B (A área comum: de x=5 a x=10)
        Geometry persistente = motor.calcularPersistente(safraA, safraB);

        assertNotNull(persistente);
        assertEquals(50.0, persistente.getArea());
    }

    @Test
    void deveConverterAreaParaHectares() throws ParseException {
        // Polígono com 20.000 metros quadrados de área
        Geometry areaEmMetros = wktReader.read("POLYGON((0 0, 0 200, 100 200, 100 0, 0 0))");

        double hectares = motor.calcularAreaEmHectares(areaEmMetros);

        // 20.000 m2 / 10.000 = 2 hectares
        assertEquals(2.0, hectares);
    }
}