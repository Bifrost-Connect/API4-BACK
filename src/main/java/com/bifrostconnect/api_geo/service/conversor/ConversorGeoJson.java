package com.bifrostconnect.api_geo.service.conversor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class ConversorGeoJson implements ConversorArquivoGeoespacial {

    private static final long TAMANHO_MAXIMO = 5L * 1024 * 1024 * 1024; // 5 GB
    private static final int IO_BUFFER_SIZE = 64 * 1024;

    @Override
    public boolean suporta(String extensao, Path arquivo) {
        if ("geojson".equalsIgnoreCase(extensao) || "json".equalsIgnoreCase(extensao)) {
            return true;
        }

        if ("zip".equalsIgnoreCase(extensao) && arquivo != null && Files.isRegularFile(arquivo)) {
            try (ZipFile zip = new ZipFile(arquivo.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                boolean temGeoJson = false;
                boolean temShp = false;

                while (entries.hasMoreElements()) {
                    ZipEntry entrada = entries.nextElement();
                    String nome = entrada.getName().toLowerCase(Locale.ROOT);
                    if (!entrada.isDirectory()) {
                        if (nome.endsWith(".geojson") || nome.endsWith(".json")) {
                            temGeoJson = true;
                        }
                        if (nome.endsWith(".shp")) {
                            temShp = true;
                        }
                    }
                }
                return temGeoJson && !temShp;
            } catch (Exception ignored) {
                return false;
            }
        }

        return false;
    }

    @Override
    public Path converterParaGeoJson(Path arquivoOrigem) throws IOException {
        String nome = arquivoOrigem.getFileName().toString().toLowerCase(Locale.ROOT);

        if (nome.endsWith(".geojson") || nome.endsWith(".json")) {
            return arquivoOrigem;
        }

        if (!nome.endsWith(".zip")) {
            throw new IllegalArgumentException("Formato incompatível com o conversor GeoJSON: " + nome);
        }

        Path extraido = null;

        try (ZipFile zip = new ZipFile(arquivoOrigem.toFile())) {
            ZipEntry geoJsonEntry = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entrada = entries.nextElement();
                String nomeEntrada = entrada.getName().toLowerCase(Locale.ROOT);

                if (entrada.isDirectory() || (!nomeEntrada.endsWith(".geojson") && !nomeEntrada.endsWith(".json"))) {
                    continue;
                }

                if (geoJsonEntry != null) {
                    throw new IllegalArgumentException(
                            "O ZIP contém mais de um arquivo GeoJSON. Envie um ZIP com apenas um GeoJSON.");
                }
                geoJsonEntry = entrada;
            }

            if (geoJsonEntry == null) {
                throw new IllegalArgumentException("O ZIP não contém nenhum arquivo .geojson ou .json.");
            }

            if (geoJsonEntry.getSize() > TAMANHO_MAXIMO) {
                throw new IllegalArgumentException("O GeoJSON extraído excede o limite de 5 GB.");
            }

            extraido = Files.createTempFile("geojson-extraido-", ".geojson");

            try (InputStream entrada = new BufferedInputStream(zip.getInputStream(geoJsonEntry), IO_BUFFER_SIZE);
                 OutputStream saida = new BufferedOutputStream(Files.newOutputStream(extraido), IO_BUFFER_SIZE)) {
                byte[] buffer = new byte[IO_BUFFER_SIZE];
                long total = 0;
                int bytesLidos;

                while ((bytesLidos = entrada.read(buffer)) != -1) {
                    total += bytesLidos;

                    if (total > TAMANHO_MAXIMO) {
                        throw new IllegalArgumentException("O GeoJSON extraído excede o limite de 5 GB.");
                    }

                    saida.write(buffer, 0, bytesLidos);
                }
            }

            Path resultado = extraido;
            extraido = null;
            return resultado;

        } finally {
            if (extraido != null) {
                Files.deleteIfExists(extraido);
            }
        }
    }
}
