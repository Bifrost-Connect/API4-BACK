package com.bifrostconnect.api_geo.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Cifragem autenticada AES-256-GCM para credenciais de conectores externos.
 */
@Service
public class ConectorCriptoService {

    private static final String CHAVE_INTERNA_DEV = "geodatahub-dev-conector-secret-key";
    private static final String ALGORITMO_GCM = "AES/GCM/NoPadding";
    private static final String PREFIXO_V1 = "v1:";
    private static final int TAMANHO_IV_BYTES = 12;
    private static final int TAMANHO_TAG_BITS = 128;
    private static final int TAMANHO_TAG_BYTES = TAMANHO_TAG_BITS / 8;

    private final SecretKey chaveSecreta;
    private final SecureRandom secureRandom = new SecureRandom();

    public ConectorCriptoService() {
        this("", "");
    }

    @Autowired
    public ConectorCriptoService(
            @Value("${conector.cripto.segredo:}") String segredoConector,
            @Value("${seguranca.jwt.segredo:}") String segredoJwt) {
        String material = resolverMaterialChave(segredoConector, segredoJwt);
        this.chaveSecreta = derivarChaveAes256(material);
    }

    /**
     * Cifra um segredo em texto plano usando AES-256-GCM com IV aleatório de 12 bytes.
     */
    public String cifrar(String segredoPlano) {
        if (segredoPlano == null || segredoPlano.isBlank()) {
            return null;
        }
        byte[] iv = new byte[TAMANHO_IV_BYTES];
        secureRandom.nextBytes(iv);
        byte[] textoPlano = segredoPlano.getBytes(StandardCharsets.UTF_8);

        try {
            Cipher cipher = Cipher.getInstance(ALGORITMO_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, chaveSecreta, new GCMParameterSpec(TAMANHO_TAG_BITS, iv));
            byte[] cifrado = cipher.doFinal(textoPlano);

            byte[] payload = new byte[iv.length + cifrado.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(cifrado, 0, payload, iv.length, cifrado.length);
            return PREFIXO_V1 + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao cifrar credencial do conector.", e);
        } finally {
            Arrays.fill(textoPlano, (byte) 0);
        }
    }

    /**
     * Decifra um valor produzido por {@link #cifrar(String)}.
     */
    public String decifrar(String valorCifrado) {
        if (valorCifrado == null || valorCifrado.isBlank()) {
            return null;
        }
        if (!valorCifrado.startsWith(PREFIXO_V1)) {
            throw new IllegalArgumentException("Formato de credencial cifrada inválido.");
        }

        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(valorCifrado.substring(PREFIXO_V1.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Credencial cifrada com Base64 inválido.", e);
        }

        if (payload.length <= TAMANHO_IV_BYTES + TAMANHO_TAG_BYTES) {
            throw new IllegalArgumentException("Payload de credencial cifrada truncado.");
        }

        byte[] iv = Arrays.copyOfRange(payload, 0, TAMANHO_IV_BYTES);
        byte[] cifrado = Arrays.copyOfRange(payload, TAMANHO_IV_BYTES, payload.length);

        try {
            Cipher cipher = Cipher.getInstance(ALGORITMO_GCM);
            cipher.init(Cipher.DECRYPT_MODE, chaveSecreta, new GCMParameterSpec(TAMANHO_TAG_BITS, iv));
            byte[] plano = cipher.doFinal(cifrado);
            try {
                return new String(plano, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(plano, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao decifrar credencial do conector.", e);
        }
    }

    /**
     * Retorna apenas um prefixo mascarado para exibição segura na interface.
     */
    public String mascararPrefixo(String segredoPlano) {
        if (segredoPlano == null || segredoPlano.isBlank()) {
            return null;
        }
        if (segredoPlano.length() <= 4) {
            return "****";
        }
        return segredoPlano.substring(0, 4) + "****";
    }

    private static String resolverMaterialChave(String segredoConector, String segredoJwt) {
        if (segredoConector != null && !segredoConector.isBlank()) {
            return segredoConector;
        }
        if (segredoJwt != null && !segredoJwt.isBlank()) {
            return segredoJwt;
        }
        return CHAVE_INTERNA_DEV;
    }

    private static SecretKey derivarChaveAes256(String material) {
        byte[] bytes = material.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            return new SecretKeySpec(hash, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM.", e);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
