package com.bifrostconnect.api_geo.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Service;

// Tarefa 10: serviço criptográfico das chaves de API.
// - 32 bytes aleatórios de um gerador criptograficamente seguro (SecureRandom, equivalente ao
//   crypto.randomBytes(32) do Node), codificados em Base64 URL-safe, com o prefixo grp_live_
// - SHA-256 em hexadecimal minúsculo, no formato exigido por chaves_api.hash_chave_sha256 (CHAR(64))
// - O serviço não guarda a chave em campo nenhum nem a escreve em log: ela só existe nas variáveis
//   locais do método e no objeto devolvido, que o chamador usa uma única vez
@Service
public class ChaveApiCriptoService {

    public static final String PREFIXO_PADRAO = "grp_live_";

    private static final int BYTES_DE_ENTROPIA = 32;

    // chaves_api.prefixo_chave aceita até 16 caracteres; guardamos só 12 (grp_live_ + 3),
    // o suficiente para o parceiro identificar a chave e pouco para ajudar um ataque
    private static final int TAMANHO_PREFIXO_VISIVEL = 12;

    private final SecureRandom secureRandom = new SecureRandom();

    public record ChaveGerada(String chaveAberta, String prefixoVisivel, String hashSha256) {

        // protege contra vazamento acidental em log
        @Override
        public String toString() {
            return "ChaveGerada[prefixoVisivel=" + prefixoVisivel + ", chaveAberta=****, hashSha256=****]";
        }
    }

    public ChaveGerada gerar() {
        byte[] aleatorio = new byte[BYTES_DE_ENTROPIA];

        try {
            secureRandom.nextBytes(aleatorio);

            String corpo = Base64.getUrlEncoder().withoutPadding().encodeToString(aleatorio);
            String chaveAberta = PREFIXO_PADRAO + corpo;

            return new ChaveGerada(
                    chaveAberta,
                    chaveAberta.substring(0, TAMANHO_PREFIXO_VISIVEL),
                    hashSha256(chaveAberta));
        } finally {
            // descarta o material aleatório bruto da memória
            Arrays.fill(aleatorio, (byte) 0);
        }
    }

    // SHA-256 da chave em hexadecimal minúsculo (64 caracteres).
    // É o mesmo cálculo que a autenticação deve usar para localizar a chave pelo hash.
    public String hashSha256(String chave) {
        byte[] bytes = chave.getBytes(StandardCharsets.UTF_8);

        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM.", e);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}