package com.bifrostconnect.api_geo.service;

import com.bifrostconnect.api_geo.dto.ExportacaoMetricaDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsvExportServiceTest {

    private CsvExportService csvExportService;

    @BeforeEach
    void setUp() {
        csvExportService = new CsvExportService();
    }

    @Test
    @DisplayName("Deve gerar CSV com BOM UTF-8 e cabeçalho correto")
    void deveGerarCsvComBomECabecalho() {
        // Arrange
        List<ExportacaoMetricaDTO> dados = List.of();

        // Act
        byte[] resultado = csvExportService.gerarCsvComBom(dados);

        // Assert
        assertNotNull(resultado);
        assertTrue(resultado.length > 3, "O ficheiro deve ter pelo menos o tamanho do BOM");
        
        // Verifica o BOM (EF BB BF)
        assertEquals((byte) 0xEF, resultado[0]);
        assertEquals((byte) 0xBB, resultado[1]);
        assertEquals((byte) 0xBF, resultado[2]);

        String conteudo = new String(resultado, 3, resultado.length - 3, StandardCharsets.UTF_8);
        assertTrue(conteudo.startsWith("Área Total (ha);Área Sobreposta (ha);Percentual Sobreposição;Órgão;Safra;Versão Oficial;Hash Rastreabilidade"));
    }

    @Test
    @DisplayName("Deve escapar corretamente caracteres especiais no CSV")
    void deveEscaparCaracteresEspeciais() {
        // Arrange
        ExportacaoMetricaDTO linhaNormal = new ExportacaoMetricaDTO(
                100.0, 50.0, 50.0, "IBAMA", "2026", "V1", "hash123", null
        );
        
        // Linha com caracteres que precisam de escape (ponto e vírgula e aspas)
        ExportacaoMetricaDTO linhaComEscape = new ExportacaoMetricaDTO(
                200.0, 10.0, 5.0, "Órgão; Teste", "2026", "V\"2\"", "hash456", null
        );

        // Act
        byte[] resultado = csvExportService.gerarCsvComBom(List.of(linhaNormal, linhaComEscape));
        String conteudo = new String(resultado, 3, resultado.length - 3, StandardCharsets.UTF_8);
        String[] linhas = conteudo.split("\n");

        // Assert
        assertTrue(linhas[1].contains("100.0;50.0;50.0;IBAMA;2026;V1;hash123"), "Linha normal não deve ter aspas adicionais");
        assertTrue(linhas[2].contains("\"Órgão; Teste\""), "Ponto e vírgula deve forçar aspas em volta da string");
        assertTrue(linhas[2].contains("\"V\"\"2\"\"\""), "Aspas internas devem ser duplicadas e o valor envolvido em aspas");
    }
}