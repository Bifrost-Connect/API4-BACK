package com.bifrostconnect.api_geo.service;

import com.bifrostconnect.api_geo.dto.ExportacaoMetricaDTO;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class CsvExportService {

    public byte[] gerarCsvComBom(List<ExportacaoMetricaDTO> dados) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        
        // Tarefa 10: Escrever o BOM (Byte Order Mark) para UTF-8. 
        // Isto força o Excel a reconhecer a codificação corretamente.
        out.write(0xEF);
        out.write(0xBB);
        out.write(0xBF);

        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            
            // Cabeçalho padronizado
            pw.println("Área Total (ha);Área Sobreposta (ha);Percentual Sobreposição;Órgão;Safra;Versão Oficial;Hash Rastreabilidade");

            // Linhas
            for (ExportacaoMetricaDTO linha : dados) {
                pw.print(escapar(String.valueOf(linha.areaTotalHa())) + ";");
                pw.print(escapar(String.valueOf(linha.areaSobrepostaHa())) + ";");
                pw.print(escapar(String.valueOf(linha.percentualSobreposicao())) + ";");
                pw.print(escapar(linha.orgao()) + ";");
                pw.print(escapar(linha.safra()) + ";");
                pw.print(escapar(linha.versaoOficial()) + ";");
                pw.println(escapar(linha.hashRastreabilidade()));
            }
        }
        
        return out.toByteArray();
    }

    /**
     * Tratamento de escape de caracteres especiais (aspas, ponto-e-vírgula e quebras de linha)
     */
    private String escapar(String valor) {
        if (valor == null) {
            return "";
        }
        // Se contiver os caracteres problemáticos para CSV, aplicamos o escape padrão
        if (valor.contains(";") || valor.contains("\"") || valor.contains("\n")) {
            valor = valor.replace("\"", "\"\"");
            return "\"" + valor + "\"";
        }
        return valor;
    }
}