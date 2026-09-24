package com.bifrostconnect.api_geo.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.springframework.stereotype.Service;

import com.bifrostconnect.api_geo.entity.ArquivoOriginal;
import com.bifrostconnect.api_geo.entity.ArquivoProcessado;
import com.bifrostconnect.api_geo.repository.ArquivoOriginalRepository;
import com.bifrostconnect.api_geo.repository.ArquivoProcessadoRepository;

@Service
public class ArquivoProcessadoService {

    private final ArquivoOriginalRepository arquivoOriginalRepository;
    private final ArquivoProcessadoRepository arquivoProcessadoRepository;

    private final String STORAGE_DIR = "zona_processada_storage/";

    public ArquivoProcessadoService(
            ArquivoOriginalRepository arquivoOriginalRepository,
            ArquivoProcessadoRepository arquivoProcessadoRepository) {

        this.arquivoOriginalRepository = arquivoOriginalRepository;
        this.arquivoProcessadoRepository = arquivoProcessadoRepository;
    }

    public ArquivoProcessado criarCopiaProcessada(
            Long arquivoOriginalId,
            Long processoId,
            Long etapaGeracaoId,
            String epsg) throws Exception {

        ArquivoOriginal original = arquivoOriginalRepository.findById(arquivoOriginalId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Arquivo original não encontrado: " + arquivoOriginalId));

        Path caminhoOriginal = Paths.get(original.getUrlArmazenamento());

        if (!Files.exists(caminhoOriginal)) {
            throw new IllegalArgumentException(
                    "Arquivo original não encontrado no armazenamento: "
                            + caminhoOriginal);
        }
        Path diretorioProcessado = Paths.get(STORAGE_DIR);

        if (!Files.exists(diretorioProcessado)) {
            Files.createDirectories(diretorioProcessado);
        }

        String nomeArquivo = original.getNomeOriginal();

        Path caminhoProcessado = diretorioProcessado.resolve(
                System.currentTimeMillis() + "_" + nomeArquivo
        );

        Files.copy(
                caminhoOriginal,
                caminhoProcessado,
                StandardCopyOption.COPY_ATTRIBUTES
        );

       
        ArquivoProcessado processado = new ArquivoProcessado();

        processado.setArquivoOriginalId(original.getId());
        processado.setProcessoId(processoId);
        processado.setEtapaGeracaoId(etapaGeracaoId);
        processado.setNomeArquivo(nomeArquivo);
        processado.setFormato(original.getExtensao());
        processado.setUrlArmazenamento(caminhoProcessado.toString());
        processado.setHashSha256(original.getHashSha256());
        processado.setEpsg(epsg);
        processado.setVersao(1);

        
        return arquivoProcessadoRepository.save(processado);
    }
}