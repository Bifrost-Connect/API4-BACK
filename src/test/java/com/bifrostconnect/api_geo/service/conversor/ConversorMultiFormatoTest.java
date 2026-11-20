package com.bifrostconnect.api_geo.service.conversor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.bifrostconnect.api_geo.service.GeoJsonArquivoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ConversorMultiFormatoTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("ConversorGeoJson: deve aceitar .geojson diretamente")
    void deveAceitarGeoJsonDireto() throws Exception {
        Path geojson = tempDir.resolve("teste.geojson");
        Files.writeString(geojson, "{\"type\":\"FeatureCollection\",\"features\":[]}");

        ConversorGeoJson conversor = new ConversorGeoJson();
        assertTrue(conversor.suporta("geojson", geojson));

        Path resultado = conversor.converterParaGeoJson(geojson);
        assertEquals(geojson, resultado);
    }

    @Test
    @DisplayName("ConversorGeoJson: deve extrair GeoJSON contido em ZIP")
    void deveExtrairGeoJsonDeZip() throws Exception {
        Path zipFile = tempDir.resolve("teste_geojson.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile.toFile()))) {
            zos.putNextEntry(new ZipEntry("dados.geojson"));
            zos.write("{\"type\":\"FeatureCollection\",\"features\":[]}".getBytes());
            zos.closeEntry();
        }

        ConversorGeoJson conversor = new ConversorGeoJson();
        assertTrue(conversor.suporta("zip", zipFile));

        Path resultado = conversor.converterParaGeoJson(zipFile);
        assertNotNull(resultado);
        assertTrue(Files.exists(resultado));
        assertTrue(Files.readString(resultado).contains("FeatureCollection"));

        Files.deleteIfExists(resultado);
    }

    @Test
    @DisplayName("ConversorKml: deve converter KML com Placemark e Polygon para GeoJSON")
    void deveConverterKmlParaGeoJson() throws Exception {
        Path kmlFile = tempDir.resolve("imovel.kml");
        String kmlConteudo = """
                <?xml version="1.0" encoding="UTF-8"?>
                <kml xmlns="http://www.opengis.net/kml/2.2">
                  <Document>
                    <Placemark>
                      <name>Fazenda Boa Esperança</name>
                      <description>Propriedade Teste</description>
                      <ExtendedData>
                        <Data name="cod_imovel">
                          <value>GO-12345</value>
                        </Data>
                      </ExtendedData>
                      <Polygon>
                        <outerBoundaryIs>
                          <LinearRing>
                            <coordinates>
                              -49.25,-16.68,0 -49.24,-16.68,0 -49.24,-16.69,0 -49.25,-16.69,0 -49.25,-16.68,0
                            </coordinates>
                          </LinearRing>
                        </outerBoundaryIs>
                      </Polygon>
                    </Placemark>
                  </Document>
                </kml>
                """;
        Files.writeString(kmlFile, kmlConteudo);

        ConversorKml conversor = new ConversorKml();
        assertTrue(conversor.suporta("kml", kmlFile));

        Path resultado = conversor.converterParaGeoJson(kmlFile);
        assertNotNull(resultado);
        assertTrue(Files.exists(resultado));

        String geoJsonStr = Files.readString(resultado);
        assertTrue(geoJsonStr.contains("Fazenda Boa Esperança"));
        assertTrue(geoJsonStr.contains("GO-12345"));
        assertTrue(geoJsonStr.contains("EPSG::4326"));
        assertTrue(geoJsonStr.contains("Polygon"));

        Files.deleteIfExists(resultado);
    }

    @Test
    @DisplayName("ConversorCsv: deve converter CSV com Latitude/Longitude e CSV em ZIP para GeoJSON")
    void deveConverterCsvEZipComCsvParaGeoJson() throws Exception {
        Path csvFile = tempDir.resolve("queimadas.csv");
        String csvConteudo = """
                DataHora,Satelite,Estado,Municipio,Bioma,Latitude,Longitude
                2026/08/16 16:42:00,AQUA_M-T,GOIAS,GOIANIA,Cerrado,-16.68,-49.25
                """;
        Files.writeString(csvFile, csvConteudo);

        ConversorCsv conversor = new ConversorCsv();
        assertTrue(conversor.suporta("csv", csvFile));

        Path resultadoCsv = conversor.converterParaGeoJson(csvFile);
        assertNotNull(resultadoCsv);
        assertTrue(Files.exists(resultadoCsv));
        String geoJsonCsv = Files.readString(resultadoCsv);
        assertTrue(geoJsonCsv.contains("FeatureCollection"));
        assertTrue(geoJsonCsv.contains("GOIANIA"));
        assertTrue(geoJsonCsv.contains("Polygon"));
        Files.deleteIfExists(resultadoCsv);

        Path zipCsvFile = tempDir.resolve("bdqueimadas.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipCsvFile.toFile()))) {
            zos.putNextEntry(new ZipEntry("focos.csv"));
            zos.write(csvConteudo.getBytes());
            zos.closeEntry();
        }
        assertTrue(conversor.suporta("zip", zipCsvFile));
        Path resultadoZipCsv = conversor.converterParaGeoJson(zipCsvFile);
        assertNotNull(resultadoZipCsv);
        assertTrue(Files.exists(resultadoZipCsv));
        assertTrue(Files.readString(resultadoZipCsv).contains("AQUA_M-T"));
        Files.deleteIfExists(resultadoZipCsv);
    }

    @Test
    @DisplayName("GeoJsonArquivoService: delega para o conversor apropriado na lista")
    void servicoDelegaParaConversorApropriado() throws Exception {
        ConversorGeoJson cGeoJson = new ConversorGeoJson();
        ConversorKml cKml = new ConversorKml();
        ConversorShapefileZip cShp = new ConversorShapefileZip();
        ConversorCsv cCsv = new ConversorCsv();
        ConversorGeoPackage cGpkg = new ConversorGeoPackage();

        GeoJsonArquivoService service = new GeoJsonArquivoService(List.of(cGeoJson, cKml, cShp, cCsv, cGpkg));

        Path geojson = tempDir.resolve("mapa.geojson");
        Files.writeString(geojson, "{\"type\":\"FeatureCollection\",\"features\":[]}");

        Path res = service.preparar(geojson);
        assertEquals(geojson, res);

        Path txtInvalido = tempDir.resolve("invalido.txt");
        Files.writeString(txtInvalido, "texto comum");

        assertThrows(IllegalArgumentException.class, () -> service.preparar(txtInvalido));
    }

    @Test
    @DisplayName("Precedência ZIP: respeita ordem de detecção entre Shapefile, GeoJSON, CSV e KML")
    void deveRespeitarPrecedenciaDeDeteccaoEmZips() throws Exception {
        ConversorShapefileZip cShp = new ConversorShapefileZip();
        ConversorGeoJson cGeoJson = new ConversorGeoJson();
        ConversorCsv cCsv = new ConversorCsv();
        ConversorKml cKml = new ConversorKml();

        Path zipComShp = tempDir.resolve("misto_shp.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipComShp.toFile()))) {
            zos.putNextEntry(new ZipEntry("camada.shp"));
            zos.write(new byte[]{1});
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("camada.geojson"));
            zos.write("{}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("camada.csv"));
            zos.write("a,b\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("camada.kml"));
            zos.write("<kml/>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        assertTrue(cShp.suporta("zip", zipComShp));
        assertFalse(cGeoJson.suporta("zip", zipComShp));
        assertFalse(cCsv.suporta("zip", zipComShp));
        assertTrue(cKml.suporta("zip", zipComShp));

        Path zipGeoJsonECsv = tempDir.resolve("misto_geojson_csv.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipGeoJsonECsv.toFile()))) {
            zos.putNextEntry(new ZipEntry("dados.json"));
            zos.write("{}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("dados.csv"));
            zos.write("a,b\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        assertFalse(cShp.suporta("zip", zipGeoJsonECsv));
        assertTrue(cGeoJson.suporta("zip", zipGeoJsonECsv));
        assertFalse(cCsv.suporta("zip", zipGeoJsonECsv));
    }

    @Test
    @DisplayName("ConversorGeoJson: deve rejeitar ZIP com múltiplos arquivos GeoJSON sem vazar arquivo temporário")
    void deveRejeitarZipComMultiplosGeoJson() throws Exception {
        Path zipMulti = tempDir.resolve("multi_geojson.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipMulti.toFile()))) {
            zos.putNextEntry(new ZipEntry("a.geojson"));
            zos.write("{\"type\":\"FeatureCollection\",\"features\":[]}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("b.json"));
            zos.write("{\"type\":\"FeatureCollection\",\"features\":[]}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        Set<Path> antes = listarTemps("geojson-extraido-");
        ConversorGeoJson conversor = new ConversorGeoJson();
        assertThrows(IllegalArgumentException.class, () -> conversor.converterParaGeoJson(zipMulti));
        Set<Path> depois = listarTemps("geojson-extraido-");
        assertEquals(antes, depois);
    }

    @Test
    @DisplayName("ConversorKml: deve extrair e converter KMZ com atributos SimpleData")
    void deveConverterKmzParaGeoJson() throws Exception {
        String kml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <kml xmlns="http://www.opengis.net/kml/2.2">
                  <Document>
                    <Placemark>
                      <name>Area KMZ</name>
                      <ExtendedData>
                        <SchemaData>
                          <SimpleData name="cod_imovel">MT-999</SimpleData>
                        </SchemaData>
                      </ExtendedData>
                      <Polygon>
                        <outerBoundaryIs>
                          <LinearRing>
                            <coordinates>-56.1,-12.6,0 -56.0,-12.6,0 -56.0,-12.7,0 -56.1,-12.7,0</coordinates>
                          </LinearRing>
                        </outerBoundaryIs>
                      </Polygon>
                    </Placemark>
                  </Document>
                </kml>
                """;
        Path kmzFile = tempDir.resolve("area.kmz");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(kmzFile.toFile()))) {
            zos.putNextEntry(new ZipEntry("doc.kml"));
            zos.write(kml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        ConversorKml conversor = new ConversorKml();
        assertTrue(conversor.suporta("kmz", kmzFile));
        Path resultado = conversor.converterParaGeoJson(kmzFile);
        try {
            JsonNode root = new ObjectMapper().readTree(resultado.toFile());
            assertEquals("FeatureCollection", root.path("type").asText());
            assertEquals("urn:ogc:def:crs:EPSG::4326", root.at("/crs/properties/name").asText());
            assertEquals("MT-999", root.at("/features/0/properties/cod_imovel").asText());
            JsonNode coords = root.at("/features/0/geometry/coordinates/0");
            assertEquals(5, coords.size());
            assertEquals(coords.get(0), coords.get(coords.size() - 1));
        } finally {
            Files.deleteIfExists(resultado);
        }
    }

    @Test
    @DisplayName("ConversorShapefileZip: converte Polygon (anel aberto), MultiPolygon, Point e NullShape com DBF e PRJ")
    void deveConverterShapefileZipComPolygonMultiPolygonPointDbfEPrj() throws Exception {
        byte[] recPolygon = shpPolygon(5, new int[]{0}, new double[][]{
                {-49.0, -16.0}, {-48.9, -16.0}, {-48.9, -16.1}, {-49.0, -16.1} // anel propositalmente aberto
        });
        byte[] recMultiPolygon = shpPolygon(15, new int[]{0, 5}, new double[][]{
                {-48.0, -15.0}, {-47.9, -15.0}, {-47.9, -15.1}, {-48.0, -15.1}, {-48.0, -15.0},
                {-48.02, -15.02}, {-48.01, -15.02}, {-48.01, -15.03}, {-48.02, -15.03} // segunda parte aberta
        });
        byte[] recPoint = shpPoint(1, -47.5, -15.8);
        byte[] recNull = shpNull();

        byte[] shpBytes = buildShp(5, List.of(recPolygon, recMultiPolygon, recPoint, recNull));

        List<DbfFieldSpec> campos = List.of(
                new DbfFieldSpec("COD_IMOVEL", 'C', 12, 0),
                new DbfFieldSpec("AREA_HA", 'N', 10, 2),
                new DbfFieldSpec("QTD_PARC", 'N', 4, 0),
                new DbfFieldSpec("ATIVO", 'L', 1, 0)
        );
        byte[] dbfBytes = buildDbf(campos, List.of(
                new DbfRowSpec(true, List.of("DELETADO", "999.00", "9", "F")), // deve ser ignorada
                new DbfRowSpec(false, List.of("GO-001", "125.50", "1", "T")),
                new DbfRowSpec(false, List.of("GO-002", "250.75", "2", "T")),
                new DbfRowSpec(false, List.of("GO-PT", "1.00", "1", "F")),
                new DbfRowSpec(false, List.of("GO-NULL", "0.00", "0", "F"))
        ));
        String prj = "PROJCS[\"SIRGAS_2000_UTM_Zone_23S\",GEOGCS[\"GCS_SIRGAS_2000\"],AUTHORITY[\"EPSG\",\"31983\"]]";

        Path zipFile = tempDir.resolve("fazendas.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile.toFile()))) {
            zos.putNextEntry(new ZipEntry("dados/imoveis.shp"));
            zos.write(shpBytes);
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("dados/imoveis.dbf"));
            zos.write(dbfBytes);
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("dados/imoveis.prj"));
            zos.write(prj.getBytes(StandardCharsets.ISO_8859_1));
            zos.closeEntry();
        }

        Set<Path> dirsAntes = listarTemps("shp-extraido-");
        ConversorShapefileZip conversor = new ConversorShapefileZip();
        assertTrue(conversor.suporta("zip", zipFile));
        assertFalse(new ConversorGeoJson().suporta("zip", zipFile));
        assertFalse(new ConversorCsv().suporta("zip", zipFile));

        Path resultado = conversor.converterParaGeoJson(zipFile);
        try {
            assertEquals(dirsAntes, listarTemps("shp-extraido-"));
            JsonNode root = new ObjectMapper().readTree(resultado.toFile());
            assertEquals("FeatureCollection", root.path("type").asText());
            assertEquals("urn:ogc:def:crs:EPSG::31983", root.at("/crs/properties/name").asText());

            JsonNode features = root.get("features");
            assertEquals(3, features.size());

            // Feature 0: Polygon com anel fechado automaticamente
            JsonNode f0 = features.get(0);
            assertEquals("GO-001", f0.at("/properties/COD_IMOVEL").asText());
            assertEquals(125.5, f0.at("/properties/AREA_HA").asDouble(), 1e-6);
            assertEquals(1L, f0.at("/properties/QTD_PARC").asLong());
            assertTrue(f0.at("/properties/ATIVO").asBoolean());
            assertEquals("Polygon", f0.at("/geometry/type").asText());
            JsonNode ring0 = f0.at("/geometry/coordinates/0");
            assertEquals(5, ring0.size());
            assertEquals(-49.0, ring0.get(4).get(0).asDouble(), 1e-9);
            assertEquals(-16.0, ring0.get(4).get(1).asDouble(), 1e-9);

            // Feature 1: MultiPolygon (2 partes, segunda parte fechada automaticamente)
            JsonNode f1 = features.get(1);
            assertEquals("GO-002", f1.at("/properties/COD_IMOVEL").asText());
            assertEquals("MultiPolygon", f1.at("/geometry/type").asText());
            assertEquals(2, f1.at("/geometry/coordinates").size());
            JsonNode part2Ring = f1.at("/geometry/coordinates/1/0");
            assertEquals(5, part2Ring.size());
            assertEquals(-48.02, part2Ring.get(4).get(0).asDouble(), 1e-9);
            assertEquals(-15.02, part2Ring.get(4).get(1).asDouble(), 1e-9);

            // Feature 2: Point
            JsonNode f2 = features.get(2);
            assertEquals("GO-PT", f2.at("/properties/COD_IMOVEL").asText());
            assertEquals("Point", f2.at("/geometry/type").asText());
            assertEquals(-47.5, f2.at("/geometry/coordinates/0").asDouble(), 1e-9);
            assertEquals(-15.8, f2.at("/geometry/coordinates/1").asDouble(), 1e-9);
        } finally {
            Files.deleteIfExists(resultado);
        }
    }

    @Test
    @DisplayName("ConversorShapefileZip: localiza DBF/PRJ.TXT em fallback de diretório e remove saída temporária ao falhar")
    void deveEncontrarDbfEPrjPorFallbackDeDiretorioEManterLimpezaEmErro() throws Exception {
        byte[] recPolygon = shpPolygon(5, new int[]{0}, new double[][]{
                {-50.0, -20.0}, {-49.9, -20.0}, {-49.9, -20.1}, {-50.0, -20.0}
        });
        byte[] shpBytes = buildShp(5, List.of(recPolygon));
        byte[] dbfBytes = buildDbf(
                List.of(new DbfFieldSpec("COD_IMOVEL", 'C', 10, 0)),
                List.of(new DbfRowSpec(false, List.of("SP-777")))
        );
        String prj = "PROJCS[\"SIRGAS_2000_UTM_Zone_22S\",AUTHORITY[\"EPSG\",\"31982\"]]";

        Path zipFallback = tempDir.resolve("fallback.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFallback.toFile()))) {
            zos.putNextEntry(new ZipEntry("camada/geometrias.shp"));
            zos.write(shpBytes);
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("atributos.dbf"));
            zos.write(dbfBytes);
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("crs/sistema.prj.txt"));
            zos.write(prj.getBytes(StandardCharsets.ISO_8859_1));
            zos.closeEntry();
        }

        ConversorShapefileZip conversor = new ConversorShapefileZip();
        Path resultado = conversor.converterParaGeoJson(zipFallback);
        try {
            JsonNode root = new ObjectMapper().readTree(resultado.toFile());
            assertEquals("urn:ogc:def:crs:EPSG::31982", root.at("/crs/properties/name").asText());
            assertEquals("SP-777", root.at("/features/0/properties/COD_IMOVEL").asText());
        } finally {
            Files.deleteIfExists(resultado);
        }

        // SHP corrompido (fileCode inválido) não deve vazar arquivo temporário de saída nem diretório
        byte[] shpCorrompido = new byte[100];
        ByteBuffer.wrap(shpCorrompido).order(ByteOrder.BIG_ENDIAN).putInt(0, 1234);
        Path zipInvalido = tempDir.resolve("corrompido.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipInvalido.toFile()))) {
            zos.putNextEntry(new ZipEntry("quebrado.shp"));
            zos.write(shpCorrompido);
            zos.closeEntry();
        }

        Set<Path> saidasAntes = listarTemps("shp-para-geojson-");
        Set<Path> dirsAntes = listarTemps("shp-extraido-");
        assertThrows(IllegalArgumentException.class, () -> conversor.converterParaGeoJson(zipInvalido));
        assertEquals(saidasAntes, listarTemps("shp-para-geojson-"));
        assertEquals(dirsAntes, listarTemps("shp-extraido-"));
    }

    @Test
    @DisplayName("ConversorShapefileZip: protege limites de part/ring contra índices invertidos ou negativos sem underflow")
    void deveLidarComPolygonComParteVaziaOuInvertidaSemUnderflow() throws Exception {
        // numParts=2, parts=[0, 0] -> primeira parte vazia (0..0), segunda parte (0..4) válida
        byte[] recComParteVazia = shpPolygon(5, new int[]{0, 0}, new double[][]{
                {-49.0, -16.0}, {-48.9, -16.0}, {-48.9, -16.1}, {-49.0, -16.0}
        });
        // numParts=2, parts=[0, -1] -> primeira parte (0..-1 => vazia), segunda parte (-1 -> ignorada)
        byte[] recComIndiceNegativo = shpPolygon(5, new int[]{0, -1}, new double[][]{
                {-49.0, -16.0}, {-48.9, -16.0}, {-48.9, -16.1}, {-49.0, -16.0}
        });
        byte[] shpBytes = buildShp(5, List.of(recComParteVazia, recComIndiceNegativo));

        Path zipFile = tempDir.resolve("partes_borda.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile.toFile()))) {
            zos.putNextEntry(new ZipEntry("borda.shp"));
            zos.write(shpBytes);
            zos.closeEntry();
        }

        Path resultado = new ConversorShapefileZip().converterParaGeoJson(zipFile);
        try {
            JsonNode root = new ObjectMapper().readTree(resultado.toFile());
            assertEquals(1, root.get("features").size());
            assertEquals("Polygon", root.at("/features/0/geometry/type").asText());
            assertEquals(4, root.at("/features/0/geometry/coordinates/0").size());
        } finally {
            Files.deleteIfExists(resultado);
        }
    }

    @Test
    @DisplayName("ConversorShapefileZip: faz streaming com DBF multi-bloco e tolera cabeçalho DBF menor que 32 bytes")
    void deveFazerStreamingMultiBlocoETolerarDbfHeaderCurto() throws Exception {
        int total = 500;
        List<byte[]> records = new java.util.ArrayList<>(total);
        List<DbfRowSpec> rows = new java.util.ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            records.add(shpPoint(1, -48.0 - (i * 0.001), -15.0 - (i * 0.001)));
            rows.add(new DbfRowSpec(false, List.of("REG-" + i, String.valueOf(i))));
        }
        byte[] shpBytes = buildShp(1, records);
        byte[] dbfBytes = buildDbf(List.of(
                new DbfFieldSpec("COD_IMOVEL", 'C', 12, 0),
                new DbfFieldSpec("ORDEM", 'N', 6, 0)), rows);

        Path zipMulti = tempDir.resolve("multi_bloco.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipMulti.toFile()))) {
            zos.putNextEntry(new ZipEntry("lote.shp"));
            zos.write(shpBytes);
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("lote.dbf"));
            zos.write(dbfBytes);
            zos.closeEntry();
        }

        Path resultado = new ConversorShapefileZip().converterParaGeoJson(zipMulti);
        try {
            JsonNode root = new ObjectMapper().readTree(resultado.toFile());
            JsonNode features = root.get("features");
            assertEquals(total, features.size());
            assertEquals("REG-499", features.get(499).at("/properties/COD_IMOVEL").asText());
            assertEquals(499L, features.get(499).at("/properties/ORDEM").asLong());
        } finally {
            Files.deleteIfExists(resultado);
        }

        // DBF com headerLength < 32 não deve causar NegativeArraySizeException
        byte[] dbfCurto = new byte[32];
        ByteBuffer.wrap(dbfCurto).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 1).putShort(8, (short) 16).putShort(10, (short) 8);
        Path zipDbfCurto = tempDir.resolve("dbf_curto.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipDbfCurto.toFile()))) {
            zos.putNextEntry(new ZipEntry("um.shp"));
            zos.write(buildShp(1, List.of(shpPoint(1, -47.0, -15.0))));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("um.dbf"));
            zos.write(dbfCurto);
            zos.closeEntry();
        }

        Path resCurto = new ConversorShapefileZip().converterParaGeoJson(zipDbfCurto);
        try {
            JsonNode root = new ObjectMapper().readTree(resCurto.toFile());
            assertEquals(1, root.get("features").size());
            assertEquals(0, root.at("/features/0/properties").size());
        } finally {
            Files.deleteIfExists(resCurto);
        }
    }

    private static Set<Path> listarTemps(String prefixo) throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Set<Path> encontrados = new HashSet<>();
        try (Stream<Path> s = Files.list(tmp)) {
            s.filter(p -> p.getFileName().toString().startsWith(prefixo)).forEach(encontrados::add);
        }
        return encontrados;
    }

    private static byte[] buildShp(int shapeType, List<byte[]> recordContents) {
        int totalBytes = 100;
        for (byte[] rec : recordContents) {
            totalBytes += 8 + rec.length;
        }
        ByteBuffer buf = ByteBuffer.allocate(totalBytes);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putInt(0, 9994);
        buf.putInt(24, totalBytes / 2);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(28, 1000);
        buf.putInt(32, shapeType);

        int pos = 100;
        for (int i = 0; i < recordContents.size(); i++) {
            byte[] rec = recordContents.get(i);
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.putInt(pos, i + 1);
            buf.putInt(pos + 4, rec.length / 2);
            pos += 8;
            System.arraycopy(rec, 0, buf.array(), pos, rec.length);
            pos += rec.length;
        }
        return buf.array();
    }

    private static byte[] shpNull() {
        ByteBuffer bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0);
        return bb.array();
    }

    private static byte[] shpPoint(int shapeType, double x, double y) {
        ByteBuffer bb = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(shapeType);
        bb.putDouble(x);
        bb.putDouble(y);
        return bb.array();
    }

    private static byte[] shpPolygon(int shapeType, int[] parts, double[][] points) {
        int baseLen = 44 + 4 * parts.length + 16 * points.length;
        int extraLen = shapeType == 15 ? (16 + 8 * points.length) * 2 : 0;
        ByteBuffer bb = ByteBuffer.allocate(baseLen + extraLen).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(shapeType);
        bb.putDouble(0.0);
        bb.putDouble(0.0);
        bb.putDouble(0.0);
        bb.putDouble(0.0);
        bb.putInt(parts.length);
        bb.putInt(points.length);
        for (int p : parts) {
            bb.putInt(p);
        }
        for (double[] pt : points) {
            bb.putDouble(pt[0]);
            bb.putDouble(pt[1]);
        }
        return bb.array();
    }

    private record DbfFieldSpec(String name, char type, int length, int decimals) {}

    private record DbfRowSpec(boolean deleted, List<String> values) {}

    private static byte[] buildDbf(List<DbfFieldSpec> fields, List<DbfRowSpec> rows) throws Exception {
        int headerLength = 32 + 32 * fields.size() + 1;
        int recordLength = 1;
        for (DbfFieldSpec f : fields) {
            recordLength += f.length();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer prefix = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        prefix.put(0, (byte) 0x03);
        prefix.put(1, (byte) 26);
        prefix.put(2, (byte) 11);
        prefix.put(3, (byte) 20);
        prefix.putInt(4, rows.size());
        prefix.putShort(8, (short) headerLength);
        prefix.putShort(10, (short) recordLength);
        out.write(prefix.array());

        for (DbfFieldSpec f : fields) {
            byte[] desc = new byte[32];
            byte[] nameBytes = f.name().getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(nameBytes, 0, desc, 0, Math.min(11, nameBytes.length));
            desc[11] = (byte) f.type();
            desc[16] = (byte) f.length();
            desc[17] = (byte) f.decimals();
            out.write(desc);
        }
        out.write(0x0D);

        for (DbfRowSpec row : rows) {
            out.write(row.deleted() ? 0x2A : 0x20);
            for (int i = 0; i < fields.size(); i++) {
                DbfFieldSpec f = fields.get(i);
                String val = i < row.values().size() ? row.values().get(i) : "";
                byte[] raw = val.getBytes(StandardCharsets.ISO_8859_1);
                byte[] padded = new byte[f.length()];
                java.util.Arrays.fill(padded, (byte) ' ');
                System.arraycopy(raw, 0, padded, 0, Math.min(raw.length, f.length()));
                out.write(padded);
            }
        }
        return out.toByteArray();
    }
}
