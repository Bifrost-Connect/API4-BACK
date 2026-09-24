package com.bifrostconnect.api_geo.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.bifrostconnect.api_geo.entity.ArquivoOriginal;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ArquivoOriginalRepository;

@Service
public class ArquivoService {

    @Autowired
    private ArquivoOriginalRepository repository;

    // Diretório local que simula o Object Storage da Zona Bruta
    private final String STORAGE_DIR = "zona_bruta_storage/";

    public ArquivoOriginal processarEArmazenarArquivo(MultipartFile file, Long processoId, Long usuarioUploadId) throws Exception {
        
        // 1. Gera o Hash SHA-256 do arquivo
        String hashSha256 = calcularHash(file);

        // 2. Verifica se o arquivo já foi enviado (evita duplicidade conforme a constraint UNIQUE)
        if (repository.existsByHashSha256(hashSha256)) {
            throw new RuntimeException("Este arquivo já foi processado anteriormente (Hash Duplicado).");
        }

        // 3. Salva o arquivo fisicamente no servidor (Zona Bruta)
        File diretorio = new File(STORAGE_DIR);
        if (!diretorio.exists()) {
            diretorio.mkdirs(); // Cria a pasta se não existir
        }
        
        String nomeArquivoOriginal = file.getOriginalFilename();
        Path caminhoFisico = Paths.get(STORAGE_DIR + System.currentTimeMillis() + "_" + nomeArquivoOriginal);
        Files.write(caminhoFisico, file.getBytes());

        // 4. Monta o objeto para salvar no banco (Oracle)
        ArquivoOriginal registro = new ArquivoOriginal();
        Processo processoRef = new Processo();
        processoRef.setId(processoId);
        registro.setProcesso(processoRef);
        registro.setUsuarioUploadId(usuarioUploadId);
        registro.setNomeOriginal(nomeArquivoOriginal);
        
        // Captura a extensão
        if (nomeArquivoOriginal != null && nomeArquivoOriginal.contains(".")) {
            registro.setExtensao(nomeArquivoOriginal.substring(nomeArquivoOriginal.lastIndexOf(".")));
        }
        
        registro.setTipoMime(file.getContentType());
        registro.setTamanhoBytes(file.getSize());
        registro.setUrlArmazenamento(caminhoFisico.toString());
        registro.setHashSha256(hashSha256);
        registro.setImutavel(true);

        // 5. Salva no banco de dados e retorna os dados
        return repository.save(registro);
    }

    // Método auxiliar para calcular o SHA-256
    private String calcularHash(MultipartFile file) throws NoSuchAlgorithmException, IOException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(file.getBytes());
        
        // Converte os bytes para string hexadecimal
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}