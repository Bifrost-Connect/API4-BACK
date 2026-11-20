package com.bifrostconnect.api_geo.service.conversor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

@Component
@Order(20)
public class ConversorShapefileZip implements ConversorArquivoGeoespacial {

    private static final long LIMITE_DESCOMPACTACAO = 5L * 1024 * 1024 * 1024; // 5 GB
    private static final int IO_BUFFER_SIZE = 64 * 1024; // 64 KB buffer para alto throughput

    @Override
    public boolean suporta(String extensao, Path arquivo) {
        if (!"zip".equalsIgnoreCase(extensao) || arquivo == null || !Files.isRegularFile(arquivo)) {
            return false;
        }

        try (ZipFile zip = new ZipFile(arquivo.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entrada = entries.nextElement();
                String nome = entrada.getName().toLowerCase(Locale.ROOT);
                if (!entrada.isDirectory() && nome.endsWith(".shp")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    @Override
    public Path converterParaGeoJson(Path arquivoOrigem) throws IOException {
        try (ZipFile zipFile = new ZipFile(arquivoOrigem.toFile())) {
            List<ZipEntry> entradas = new ArrayList<>();
            ZipEntry shpEntry = null;
            long tamanhoTotalConhecido = 0L;

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entrada = entries.nextElement();
                if (entrada.isDirectory()) {
                    continue;
                }
                long entrySize = entrada.getSize();
                if (entrySize > 0) {
                    tamanhoTotalConhecido += entrySize;
                    if (tamanhoTotalConhecido > LIMITE_DESCOMPACTACAO) {
                        throw new IllegalArgumentException(
                                "O conteúdo do ZIP excede o limite máximo permitido de 5 GB.");
                    }
                }
                entradas.add(entrada);
                if (shpEntry == null && entrada.getName().toLowerCase(Locale.ROOT).endsWith(".shp")) {
                    shpEntry = entrada;
                }
            }

            if (shpEntry == null) {
                throw new IllegalArgumentException("Nenhum arquivo .shp encontrado dentro do arquivo ZIP.");
            }

            String shpNorm = normalizarCaminhoZip(shpEntry.getName());
            int lastSlash = shpNorm.lastIndexOf('/');
            String parentPrefix = lastSlash >= 0 ? shpNorm.substring(0, lastSlash + 1) : "";
            String fileName = lastSlash >= 0 ? shpNorm.substring(lastSlash + 1) : shpNorm;
            int dotIndex = fileName.lastIndexOf('.');
            String baseName = dotIndex >= 0 ? fileName.substring(0, dotIndex) : fileName;

            ZipEntry dbfEntry = localizarEntradaComplementar(entradas, parentPrefix, baseName, ".dbf");
            ZipEntry prjEntry = localizarEntradaComplementar(entradas, parentPrefix, baseName, ".prj", ".prj.txt");

            String epsg = extrairEpsgDePrj(zipFile, prjEntry);

            Path geoJsonSaida = Files.createTempFile("shp-para-geojson-", ".geojson");
            try {
                converterEmStreaming(zipFile, shpEntry, dbfEntry, epsg, geoJsonSaida);
                return geoJsonSaida;
            } catch (Throwable t) {
                try {
                    Files.deleteIfExists(geoJsonSaida);
                } catch (Throwable ignored) {
                }
                throw t;
            }
        }
    }

    private static String normalizarCaminhoZip(String nome) {
        return nome.replace('\\', '/');
    }

    private ZipEntry localizarEntradaComplementar(
            List<ZipEntry> entradas,
            String parentPrefix,
            String baseName,
            String... extensoes) {
        String parentLower = parentPrefix.toLowerCase(Locale.ROOT);
        String baseLower = baseName.toLowerCase(Locale.ROOT);

        for (String ext : extensoes) {
            String alvo = parentLower + baseLower + ext.toLowerCase(Locale.ROOT);
            for (ZipEntry e : entradas) {
                if (normalizarCaminhoZip(e.getName()).toLowerCase(Locale.ROOT).equals(alvo)) {
                    return e;
                }
            }
        }

        for (String ext : extensoes) {
            String extLower = ext.toLowerCase(Locale.ROOT);
            for (ZipEntry e : entradas) {
                String norm = normalizarCaminhoZip(e.getName()).toLowerCase(Locale.ROOT);
                int idx = norm.lastIndexOf('/');
                String dir = idx >= 0 ? norm.substring(0, idx + 1) : "";
                if (dir.equals(parentLower) && norm.endsWith(extLower)) {
                    return e;
                }
            }
        }

        for (String ext : extensoes) {
            String extLower = ext.toLowerCase(Locale.ROOT);
            for (ZipEntry e : entradas) {
                if (normalizarCaminhoZip(e.getName()).toLowerCase(Locale.ROOT).endsWith(extLower)) {
                    return e;
                }
            }
        }

        return null;
    }

    private String extrairEpsgDePrj(ZipFile zipFile, ZipEntry prjEntry) {
        if (prjEntry == null) {
            return "4674"; // Default SIRGAS 2000
        }
        try (InputStream prjIn = new BufferedInputStream(zipFile.getInputStream(prjEntry))) {
            String wkt = new String(prjIn.readNBytes(65536), StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT);
            Pattern p = Pattern.compile("AUTHORITY\\[\"EPSG\"\\s*,\\s*\"?(\\d+)\"?\\]");
            Matcher m = p.matcher(wkt);
            String ultimoEpsg = null;
            while (m.find()) {
                ultimoEpsg = m.group(1);
            }
            if (ultimoEpsg != null) {
                return ultimoEpsg;
            }
            if (wkt.contains("SIRGAS") || wkt.contains("4674")) return "4674";
            if (wkt.contains("WGS_1984") || wkt.contains("WGS 84") || wkt.contains("4326")) return "4326";
            if (wkt.contains("31982")) return "31982";
            if (wkt.contains("31983")) return "31983";
            if (wkt.contains("5880")) return "5880";
        } catch (Exception ignored) {
        }
        return "4674";
    }

    /**
     * Converte .shp + .dbf em .geojson via streaming direto do ZipFile registro a registro (memória O(1)),
     * suportando arquivos de múltiplos Gigabytes e milhões de polígonos sem OutOfMemoryError.
     */
    private void converterEmStreaming(
            ZipFile zipFile,
            ZipEntry shpEntry,
            ZipEntry dbfEntry,
            String epsg,
            Path geoJsonDestino) throws IOException {
        JsonFactory jfactory = new JsonFactory();
        AtomicLong bytesDescompactados = new AtomicLong(0L);

        try (DataInputStream shpIn = new DataInputStream(new BufferedInputStream(
                     new LimitingInputStream(zipFile.getInputStream(shpEntry), bytesDescompactados, LIMITE_DESCOMPACTACAO),
                     IO_BUFFER_SIZE));
             DbfStreamingReader dbfReader = (dbfEntry != null)
                     ? new DbfStreamingReader(new LimitingInputStream(
                             zipFile.getInputStream(dbfEntry), bytesDescompactados, LIMITE_DESCOMPACTACAO))
                     : null;
             JsonGenerator jg = jfactory.createGenerator(
                     new BufferedOutputStream(Files.newOutputStream(geoJsonDestino), IO_BUFFER_SIZE),
                     JsonEncoding.UTF8)) {

            byte[] shpHeader = new byte[100];
            try {
                shpIn.readFully(shpHeader);
            } catch (EOFException e) {
                throw new IllegalArgumentException("Arquivo .shp corrompido ou menor que o cabeçalho padrão de 100 bytes.");
            }

            ByteBuffer hdrBuf = ByteBuffer.wrap(shpHeader).order(ByteOrder.BIG_ENDIAN);
            int fileCode = hdrBuf.getInt(0);
            if (fileCode != 9994) {
                throw new IllegalArgumentException("Código de arquivo Shapefile inválido: " + fileCode);
            }

            jg.writeStartObject();
            jg.writeStringField("type", "FeatureCollection");

            if (epsg != null && !epsg.isBlank()) {
                jg.writeObjectFieldStart("crs");
                jg.writeStringField("type", "name");
                jg.writeObjectFieldStart("properties");
                jg.writeStringField("name", "urn:ogc:def:crs:EPSG::" + epsg);
                jg.writeEndObject();
                jg.writeEndObject();
            }

            jg.writeArrayFieldStart("features");

            byte[] recBuf = new byte[64 * 1024];

            while (true) {
                int contentLengthWords;
                try {
                    shpIn.readInt(); // recordNumber
                    contentLengthWords = shpIn.readInt();
                } catch (EOFException eof) {
                    break; // Fim normal do arquivo .shp
                }

                long recordBytesLenLong = (long) contentLengthWords * 2L;
                if (recordBytesLenLong <= 0) {
                    continue;
                }
                if (recordBytesLenLong > LIMITE_DESCOMPACTACAO || recordBytesLenLong > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Registro .shp excede o tamanho máximo suportado.");
                }
                int recordBytesLen = (int) recordBytesLenLong;
                if (recordBytesLen > recBuf.length) {
                    recBuf = new byte[recordBytesLen];
                }

                try {
                    shpIn.readFully(recBuf, 0, recordBytesLen);
                } catch (EOFException eof) {
                    break;
                }

                Map<String, Object> attrs = dbfReader != null ? dbfReader.lerProximoRegistroAtivo() : null;
                escreverRegistroSeValido(jg, recBuf, recordBytesLen, attrs);
            }

            jg.writeEndArray();
            jg.writeEndObject();
        }
    }

    private void escreverRegistroSeValido(
            JsonGenerator jg,
            byte[] recBuf,
            int length,
            Map<String, Object> attrs) throws IOException {
        if (length < 4) {
            return;
        }
        ByteBuffer bb = ByteBuffer.wrap(recBuf, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        int shapeType = bb.getInt(0);

        if (shapeType == 0) { // Null Shape
            return;
        }

        if (shapeType == 5 || shapeType == 15 || shapeType == 25) { // Polygon, PolygonZ, PolygonM
            if (length < 44) {
                return;
            }
            int numParts = bb.getInt(36);
            int numPoints = bb.getInt(40);
            if (numParts <= 0 || numPoints <= 0) {
                return;
            }

            long pointsBaseOffset = 44L + (long) numParts * 4L;
            if (pointsBaseOffset > length) {
                return;
            }
            long maxValidPoints = (length - pointsBaseOffset) / 16L;
            if (maxValidPoints <= 0) {
                return;
            }

            int validParts = contarPartesValidas(bb, numParts, numPoints, maxValidPoints);
            if (validParts == 0) {
                return;
            }

            jg.writeStartObject();
            jg.writeStringField("type", "Feature");
            escreverPropriedades(jg, attrs);

            jg.writeObjectFieldStart("geometry");
            escreverPartesPoligono(
                    jg, bb, pointsBaseOffset, numParts, numPoints, maxValidPoints, validParts > 1);
            jg.writeEndObject();
            jg.writeEndObject();
        } else if (shapeType == 1 || shapeType == 11 || shapeType == 21) { // Point, PointZ, PointM
            if (length < 20) {
                return;
            }
            double x = bb.getDouble(4);
            double y = bb.getDouble(12);

            jg.writeStartObject();
            jg.writeStringField("type", "Feature");
            escreverPropriedades(jg, attrs);

            jg.writeObjectFieldStart("geometry");
            jg.writeStringField("type", "Point");
            jg.writeArrayFieldStart("coordinates");
            jg.writeNumber(x);
            jg.writeNumber(y);
            jg.writeEndArray();
            jg.writeEndObject();
            jg.writeEndObject();
        }
    }

    private void escreverPartesPoligono(
            JsonGenerator jg,
            ByteBuffer bb,
            long pointsBaseOffset,
            int numParts,
            int numPoints,
            long maxValidPoints,
            boolean multi) throws IOException {
        jg.writeStringField("type", multi ? "MultiPolygon" : "Polygon");
        jg.writeArrayFieldStart("coordinates");
        for (int i = 0; i < numParts; i++) {
            int start = bb.getInt(44 + i * 4);
            int end = calcularFimParte(bb, i, numParts, numPoints, maxValidPoints);
            if (start >= 0 && start < end) {
                if (multi) {
                    jg.writeStartArray();
                }
                escreverAnel(jg, bb, pointsBaseOffset, start, end);
                if (multi) {
                    jg.writeEndArray();
                }
            }
        }
        jg.writeEndArray();
    }

    private int contarPartesValidas(ByteBuffer bb, int numParts, int numPoints, long maxValidPoints) {
        int validParts = 0;
        for (int i = 0; i < numParts; i++) {
            int start = bb.getInt(44 + i * 4);
            int end = calcularFimParte(bb, i, numParts, numPoints, maxValidPoints);
            if (start >= 0 && start < end) {
                validParts++;
            }
        }
        return validParts;
    }

    private int calcularFimParte(ByteBuffer bb, int partIndex, int numParts, int numPoints, long maxValidPoints) {
        int rawEnd = (partIndex < numParts - 1) ? bb.getInt(44 + (partIndex + 1) * 4) : numPoints;
        long bounded = Math.min((long) numPoints, (long) rawEnd);
        bounded = Math.min(bounded, maxValidPoints);
        return (int) Math.max(0L, bounded);
    }

    private void escreverAnel(
            JsonGenerator jg,
            ByteBuffer bb,
            long pointsBaseOffset,
            int start,
            int end) throws IOException {
        jg.writeStartArray();
        double firstX = 0.0;
        double firstY = 0.0;
        double lastX = 0.0;
        double lastY = 0.0;
        for (int pIdx = start; pIdx < end; pIdx++) {
            int offset = (int) (pointsBaseOffset + (long) pIdx * 16L);
            double x = bb.getDouble(offset);
            double y = bb.getDouble(offset + 8);
            if (pIdx == start) {
                firstX = x;
                firstY = y;
            }
            lastX = x;
            lastY = y;
            jg.writeStartArray();
            jg.writeNumber(x);
            jg.writeNumber(y);
            jg.writeEndArray();
        }
        if (firstX != lastX || firstY != lastY) {
            jg.writeStartArray();
            jg.writeNumber(firstX);
            jg.writeNumber(firstY);
            jg.writeEndArray();
        }
        jg.writeEndArray();
    }

    private void escreverPropriedades(JsonGenerator jg, Map<String, Object> attrs) throws IOException {
        jg.writeObjectFieldStart("properties");
        if (attrs != null) {
            for (Map.Entry<String, Object> entry : attrs.entrySet()) {
                if (entry.getValue() == null) {
                    jg.writeNullField(entry.getKey());
                } else if (entry.getValue() instanceof Number n) {
                    if (n instanceof Double || n instanceof Float) {
                        jg.writeNumberField(entry.getKey(), n.doubleValue());
                    } else {
                        jg.writeNumberField(entry.getKey(), n.longValue());
                    }
                } else if (entry.getValue() instanceof Boolean b) {
                    jg.writeBooleanField(entry.getKey(), b);
                } else {
                    jg.writeStringField(entry.getKey(), entry.getValue().toString());
                }
            }
        }
        jg.writeEndObject();
    }

    private static final class LimitingInputStream extends FilterInputStream {
        private final AtomicLong totalBytesRead;
        private final long limit;

        LimitingInputStream(InputStream in, AtomicLong totalBytesRead, long limit) {
            super(in);
            this.totalBytesRead = totalBytesRead;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1 && totalBytesRead.incrementAndGet() > limit) {
                throw new IllegalArgumentException("O conteúdo do ZIP excede o limite máximo permitido de 5 GB.");
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0 && totalBytesRead.addAndGet(n) > limit) {
                throw new IllegalArgumentException("O conteúdo do ZIP excede o limite máximo permitido de 5 GB.");
            }
            return n;
        }
    }

    /**
     * Leitor de arquivos dBASE (.dbf) em streaming, sem alocar o arquivo inteiro na memória.
     */
    private static final class DbfStreamingReader implements AutoCloseable {
        private final DataInputStream in;
        private final int numRecords;
        private final int recordLength;
        private final List<CampoDbf> campos = new ArrayList<>();
        private final byte[] rowBuf;
        private final Charset charset = StandardCharsets.ISO_8859_1;
        private int recordsRead = 0;

        DbfStreamingReader(InputStream inputStream) throws IOException {
            this.in = new DataInputStream(new BufferedInputStream(inputStream, IO_BUFFER_SIZE));
            try {
                byte[] headerPrefix = new byte[32];
                try {
                    in.readFully(headerPrefix);
                } catch (EOFException e) {
                    this.numRecords = 0;
                    this.recordLength = 0;
                    this.rowBuf = new byte[0];
                    return;
                }

                ByteBuffer bb = ByteBuffer.wrap(headerPrefix).order(ByteOrder.LITTLE_ENDIAN);
                int totalRegistros = bb.getInt(4);
                int headerLength = Short.toUnsignedInt(bb.getShort(8));
                int tamanhoRegistro = Short.toUnsignedInt(bb.getShort(10));

                if (headerLength < 32 || totalRegistros < 0 || tamanhoRegistro <= 0) {
                    this.numRecords = 0;
                    this.recordLength = 0;
                    this.rowBuf = new byte[0];
                    return;
                }

                this.numRecords = totalRegistros;
                this.recordLength = tamanhoRegistro;
                this.rowBuf = new byte[this.recordLength];

                int remainingHeader = headerLength - 32;
                byte[] descriptorBytes = new byte[remainingHeader];
                in.readFully(descriptorBytes);

                int pos = 0;
                while (pos + 32 <= descriptorBytes.length && descriptorBytes[pos] != 0x0D) {
                    int zeroIndex = 0;
                    while (zeroIndex < 11 && descriptorBytes[pos + zeroIndex] != 0) {
                        zeroIndex++;
                    }
                    String nome = new String(descriptorBytes, pos, zeroIndex, StandardCharsets.US_ASCII).trim();
                    char tipo = (char) descriptorBytes[pos + 11];
                    int length = descriptorBytes[pos + 16] & 0xFF;
                    int decimal = descriptorBytes[pos + 17] & 0xFF;

                    campos.add(new CampoDbf(nome, tipo, length, decimal));
                    pos += 32;
                }
            } catch (Throwable t) {
                try {
                    this.in.close();
                } catch (Throwable ignored) {
                }
                throw t;
            }
        }

        Map<String, Object> lerProximoRegistroAtivo() throws IOException {
            while (recordsRead < numRecords && recordLength > 0) {
                try {
                    in.readFully(rowBuf, 0, recordLength);
                } catch (EOFException e) {
                    return null;
                }
                recordsRead++;

                byte deleteFlag = rowBuf[0];
                if (deleteFlag == 0x2A) { // '*' indica linha deletada no DBF
                    continue;
                }

                Map<String, Object> props = new LinkedHashMap<>(campos.size());
                int colOffset = 1;
                for (CampoDbf campo : campos) {
                    if (colOffset + campo.length <= recordLength) {
                        String strVal = new String(rowBuf, colOffset, campo.length, charset).trim();
                        if (strVal.isEmpty()) {
                            props.put(campo.nome, null);
                        } else if (campo.tipo == 'N' || campo.tipo == 'F') {
                            try {
                                if (campo.decimal > 0 || strVal.contains(".")) {
                                    props.put(campo.nome, Double.parseDouble(strVal));
                                } else {
                                    props.put(campo.nome, Long.parseLong(strVal));
                                }
                            } catch (NumberFormatException e) {
                                props.put(campo.nome, strVal);
                            }
                        } else if (campo.tipo == 'L') {
                            props.put(campo.nome, "T".equalsIgnoreCase(strVal) || "Y".equalsIgnoreCase(strVal));
                        } else {
                            props.put(campo.nome, strVal);
                        }
                    }
                    colOffset += campo.length;
                }
                return props;
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private record CampoDbf(String nome, char tipo, int length, int decimal) {}
}
