package com.bifrostconnect.api_geo.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.bifrostconnect.api_geo.entity.ArquivoOriginal;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ArquivoOriginalRepository;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;

@Service
public class ArquivoUploadService {

    private static final Path STORAGE_DIR = Paths.get("zona_bruta_storage");

    private final ArquivoOriginalRepository arquivoRepository;
    private final ProcessoRepository processoRepository;

    public ArquivoUploadService(
            ArquivoOriginalRepository arquivoRepository,
            ProcessoRepository processoRepository) {

        this.arquivoRepository = arquivoRepository;
        this.processoRepository = processoRepository;
    }

    @Transactional
    public ArquivoOriginal processarUpload(
            Long processoId,
            MultipartFile file) throws Exception {

        Processo processo = processoRepository.findById(processoId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Processo não encontrado com ID: " + processoId));

        /*
         * Gera o Hash SHA-256 utilizando os bytes
         * do arquivo recebido.
         */
        byte[] arquivoBytes = file.getBytes();

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(arquivoBytes);

        StringBuilder hexString = new StringBuilder();

        for (byte b : hashBytes) {
            hexString.append(String.format("%02x", b));
        }

        String hashSha256 = hexString.toString();

        /*
         * Impede arquivos duplicados.
         */
        if (arquivoRepository.existsByHashSha256(hashSha256)) {
            throw new IllegalArgumentException(
                    "Este arquivo já foi processado anteriormente "
                            + "(Hash SHA-256 duplicado).");
        }

        /*
         * Obtém o nome original do arquivo.
         */
        String nomeOriginal = file.getOriginalFilename();

        if (nomeOriginal == null || nomeOriginal.isBlank()) {
            nomeOriginal = "arquivo";
        }

        /*
         * Evita que o nome enviado pelo cliente
         * contenha caminhos de diretório.
         */
        nomeOriginal = Paths.get(nomeOriginal)
                .getFileName()
                .toString();

        /*
         * Cria a pasta de armazenamento caso
         * ainda não exista.
         */
        Files.createDirectories(STORAGE_DIR);

        /*
         * Gera um nome físico único para o arquivo.
         */
        String nomeFisico =
                System.currentTimeMillis()
                        + "_"
                        + nomeOriginal;

        Path caminhoFisico =
                STORAGE_DIR.resolve(nomeFisico);

        /*
         * AQUI está a alteração principal da tarefa:
         * o arquivo recebido passa a ser realmente
         * gravado no disco.
         */
        Files.write(caminhoFisico, arquivoBytes);

        try {

            ArquivoOriginal arquivo = new ArquivoOriginal();

            arquivo.setProcesso(processo);
            arquivo.setNomeOriginal(nomeOriginal);
            arquivo.setHashSha256(hashSha256);
            arquivo.setTamanhoBytes(file.getSize());

            /*
             * Campo obrigatório existente no projeto.
             */
            arquivo.setUsuarioUploadId(1L);

            arquivo.setTipoMime(file.getContentType());

            /*
             * Salva a extensão.
             */
            if (nomeOriginal.contains(".")) {
                arquivo.setExtensao(
                        nomeOriginal.substring(
                                nomeOriginal.lastIndexOf(".") + 1));
            }

            /*
             * Guarda no banco somente o caminho
             * do arquivo físico.
             */
            arquivo.setUrlArmazenamento(
                    caminhoFisico.toString());

            arquivo.setImutavel(true);

            return arquivoRepository.saveAndFlush(arquivo);

        } catch (Exception e) {

            /*
             * Se o registro no banco falhar,
             * remove o arquivo físico para não
             * deixar um arquivo órfão.
             */
            try {
                Files.deleteIfExists(caminhoFisico);
            } catch (IOException ignored) {
            }

            throw e;
        }
    }

    /*
     * Localiza o arquivo original pelo processo.
     */
    public ArquivoOriginal buscarArquivoOriginalPorProcesso(
            Long processoId) {

        return arquivoRepository
                .findFirstByProcessoIdOrderByIdDesc(processoId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Nenhum arquivo original encontrado "
                                        + "para o processo: "
                                        + processoId));
    }

    /*
     * Abre o arquivo físico para download.
     */
    public Resource abrirArquivoParaDownload(
            ArquivoOriginal arquivo) throws IOException {

        Path caminho =
                Paths.get(arquivo.getUrlArmazenamento());

        if (!Files.exists(caminho)
                || !Files.isRegularFile(caminho)) {

            throw new IOException(
                    "Arquivo físico não encontrado: "
                            + caminho);
        }

        InputStream inputStream =
                Files.newInputStream(caminho);

        return new InputStreamResource(inputStream);
    }

    /*
     * Retorna o tamanho do arquivo físico.
     */
    public long obterTamanhoArquivo(
            ArquivoOriginal arquivo) throws IOException {

        Path caminho =
                Paths.get(arquivo.getUrlArmazenamento());

        if (!Files.exists(caminho)
                || !Files.isRegularFile(caminho)) {

            throw new IOException(
                    "Arquivo físico não encontrado: "
                            + caminho);
        }

        return Files.size(caminho);
    }
}