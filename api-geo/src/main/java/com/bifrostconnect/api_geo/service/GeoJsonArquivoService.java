
package com.bifrostconnect.api_geo.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Service;

@Service
public class GeoJsonArquivoService {

    private static final long TAMANHO_MAXIMO = 100L * 1024 * 1024;

    public Path preparar(Path arquivo) throws IOException {
        if (arquivo == null || !Files.isRegularFile(arquivo)) {
            throw new IllegalArgumentException("Arquivo não encontrado.");
        }

        String nome = arquivo.getFileName()
                .toString()
                .toLowerCase(Locale.ROOT);

        if (nome.endsWith(".geojson")) {
            return arquivo;
        }

        if (!nome.endsWith(".zip")) {
            throw new IllegalArgumentException(
                    "Formato não suportado. Envie GeoJSON ou ZIP contendo um GeoJSON.");
        }

        Path extraido = null;

        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(arquivo))) {
            ZipEntry entrada;
            int quantidadeGeoJson = 0;

            while ((entrada = zip.getNextEntry()) != null) {
                String nomeEntrada = entrada.getName()
                        .toLowerCase(Locale.ROOT);

                if (entrada.isDirectory() || !nomeEntrada.endsWith(".geojson")) {
                    zip.closeEntry();
                    continue;
                }

                quantidadeGeoJson++;

                if (quantidadeGeoJson > 1) {
                    throw new IllegalArgumentException(
                            "O ZIP contém mais de um GeoJSON. Envie um ZIP com apenas um GeoJSON.");
                }

                extraido = Files.createTempFile("geojson-processamento-", ".geojson");

                try (OutputStream saida = Files.newOutputStream(extraido)) {
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int bytesLidos;

                    while ((bytesLidos = zip.read(buffer)) != -1) {
                        total += bytesLidos;

                        if (total > TAMANHO_MAXIMO) {
                            throw new IllegalArgumentException(
                                    "O GeoJSON extraído excede o limite de 100 MB.");
                        }

                        saida.write(buffer, 0, bytesLidos);
                    }
                }

                zip.closeEntry();
            }

            if (quantidadeGeoJson == 0) {
                throw new IllegalArgumentException(
                        "O ZIP não contém nenhum arquivo .geojson.");
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

    public void excluirTemporario(Path arquivo, Path original) throws IOException {
        if (arquivo != null && original != null && !arquivo.equals(original)) {
            Files.deleteIfExists(arquivo);
        }
    }
}
