package com.bifrostconnect.api_geo.service;

import com.bifrostconnect.api_geo.entity.ArquivoOriginal;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ArquivoOriginalRepository;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;

@Service
public class ArquivoUploadService {

    private final ArquivoOriginalRepository arquivoRepository;
    private final ProcessoRepository processoRepository;

    public ArquivoUploadService(ArquivoOriginalRepository arquivoRepository, ProcessoRepository processoRepository) {
        this.arquivoRepository = arquivoRepository;
        this.processoRepository = processoRepository;
    }

    @Transactional
    public ArquivoOriginal processarUpload(Long processoId, MultipartFile file) throws Exception {
        Processo processo = processoRepository.findById(processoId)
                .orElseThrow(() -> new IllegalArgumentException("Processo não encontrado com ID: " + processoId));

        // Geração do Hash SHA-256
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(file.getBytes());
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            hexString.append(String.format("%02x", b));
        }

        ArquivoOriginal arquivo = new ArquivoOriginal();
        arquivo.setProcesso(processo);
        arquivo.setNomeArquivo(file.getOriginalFilename());
        arquivo.setHashSha256(hexString.toString());
        arquivo.setTamanhoBytes(file.getSize());
        arquivo.setUrlArmazenamento("/uploads/" + file.getOriginalFilename()); // Ajuste conforme local real

        return arquivoRepository.save(arquivo);
    }
}