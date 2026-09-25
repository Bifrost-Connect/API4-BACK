package com.bifrostconnect.api_geo.service;

import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Service;

import com.bifrostconnect.api_geo.entity.OcorrenciaValidacao;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.entity.RegraValidacao;
import com.bifrostconnect.api_geo.repository.AnaliseEspacialRepository;
import com.bifrostconnect.api_geo.repository.OcorrenciaValidacaoRepository;
import com.bifrostconnect.api_geo.repository.RegraValidacaoRepository;

@Service
public class ValidacaoService {

    private final RegraValidacaoRepository regraRepository;
    private final OcorrenciaValidacaoRepository ocorrenciaRepository;
    private final AnaliseEspacialRepository analiseEspacialRepository;

    public ValidacaoService(RegraValidacaoRepository regraRepository,
                            OcorrenciaValidacaoRepository ocorrenciaRepository,
                            AnaliseEspacialRepository analiseEspacialRepository) {
        this.regraRepository = regraRepository;
        this.ocorrenciaRepository = ocorrenciaRepository;
        this.analiseEspacialRepository = analiseEspacialRepository;
    }

    public boolean executarValidacoes(Processo processo, String nomeArquivo) {
        return executarValidacoes(processo, nomeArquivo, null);
    }

    // Método principal com suporte ao Motor Espacial (Tarefa 1)
    public boolean executarValidacoes(Processo processo, String nomeArquivo, Path caminhoArquivo) {
        List<RegraValidacao> regras = regraRepository.findByAtivoTrue();
        boolean possuiErros = false;

        for (RegraValidacao regra : regras) {
            // 1. Validação de extensão de arquivo
            if ("VALIDAR_EXTENSAO".equalsIgnoreCase(regra.getNome())) {
                if (!nomeArquivo.endsWith(".zip") && !nomeArquivo.endsWith(".geojson") && !nomeArquivo.endsWith(".shp")) {
                    registrarOcorrencia(processo, regra, "ERRO", "Extensão de arquivo não suportada para validação geo.");
                    possuiErros = true;
                }
            }

            // 2. Tarefa 1 (Motor Espacial): Validação de Sobreposição Espacial
            if ("VALIDAR_SOBREPOSICAO".equalsIgnoreCase(regra.getNome()) && nomeArquivo.endsWith(".geojson") && caminhoArquivo != null) {
                try {
                    List<String> geometriasWkt = GeoJsonLeitorUtil.extrairGeometriasWkt(caminhoArquivo);
                    for (String wktGeom : geometriasWkt) {
                        boolean sobreposto = analiseEspacialRepository.existeSobreposicao(wktGeom, processo.getId());
                        if (sobreposto) {
                            registrarOcorrencia(processo, regra, "ERRO", "Detectada sobreposição de polígonos com outro processo existente.");
                            possuiErros = true;
                            break;
                        }
                    }
                } catch (Exception e) {
                    registrarOcorrencia(processo, regra, "ERRO", "Falha ao processar análise espacial do arquivo: " + e.getMessage());
                    possuiErros = true;
                }
            }
        }
        return !possuiErros;
    }

    private void registrarOcorrencia(Processo processo, RegraValidacao regra, String severidade, String mensagem) {
        OcorrenciaValidacao ocorrencia = new OcorrenciaValidacao();
        ocorrencia.setProcesso(processo);
        ocorrencia.setRegra(regra);
        ocorrencia.setSeveridade(severidade);
        ocorrencia.setMensagem(mensagem);
        ocorrenciaRepository.save(ocorrencia);
    }
}