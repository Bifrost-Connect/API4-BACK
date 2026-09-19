package com.bifrostconnect.api_geo.service;

import com.bifrostconnect.api_geo.entity.ArquivoOriginal;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ArquivoOriginalRepository;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class HashServiceTest {

    private ArquivoUploadService arquivoUploadService;
    private ArquivoOriginalRepository arquivoRepository;
    private ProcessoRepository processoRepository;

    @BeforeEach
    void setUp() {
        // Criamos mocks dos repositórios para testar apenas o serviço sem banco de dados
        arquivoRepository = Mockito.mock(ArquivoOriginalRepository.class);
        processoRepository = Mockito.mock(ProcessoRepository.class);

        this.arquivoUploadService = new ArquivoUploadService(arquivoRepository, processoRepository);

        // Simula que o processo com ID 1 sempre existe no banco
        Processo processoMock = new Processo();
        when(processoRepository.findById(1L)).thenReturn(Optional.of(processoMock));

        // Simula o salvamento do arquivo no repositório retornando o próprio objeto recebido
        when(arquivoRepository.save(any(ArquivoOriginal.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Deve gerar o mesmo hash SHA-256 para arquivos com conteúdo idêntico")
    void deveGerarMesmoHashParaConteudoIdentico() throws Exception {
        byte[] conteudo = "conteudo do arquivo geo".getBytes();
        MockMultipartFile arquivo1 = new MockMultipartFile("file", "teste.txt", "text/plain", conteudo);
        MockMultipartFile arquivo2 = new MockMultipartFile("file", "teste.txt", "text/plain", conteudo);

        ArquivoOriginal resultado1 = arquivoUploadService.processarUpload(1L, arquivo1);
        ArquivoOriginal resultado2 = arquivoUploadService.processarUpload(1L, arquivo2);

        assertNotNull(resultado1.getHashSha256());
        assertEquals(64, resultado1.getHashSha256().length(), "O hash SHA-256 deve ter 64 caracteres");
        assertEquals(resultado1.getHashSha256(), resultado2.getHashSha256(), "Arquivos com mesmo conteúdo devem gerar o mesmo hash");
    }

    @Test
    @DisplayName("Deve gerar hashes diferentes para arquivos com conteúdos diferentes")
    void deveGerarHashesDiferentesParaConteudosDiferentes() throws Exception {
        MockMultipartFile arquivoA = new MockMultipartFile("file", "a.txt", "text/plain", "conteudo A".getBytes());
        MockMultipartFile arquivoB = new MockMultipartFile("file", "b.txt", "text/plain", "conteudo B".getBytes());

        ArquivoOriginal resultadoA = arquivoUploadService.processarUpload(1L, arquivoA);
        ArquivoOriginal resultadoB = arquivoUploadService.processarUpload(1L, arquivoB);

        assertNotNull(resultadoA.getHashSha256());
        assertNotNull(resultadoB.getHashSha256());
        assertNotEquals(resultadoA.getHashSha256(), resultadoB.getHashSha256(), "Arquivos com conteúdos diferentes devem ter hashes únicos");
    }
}