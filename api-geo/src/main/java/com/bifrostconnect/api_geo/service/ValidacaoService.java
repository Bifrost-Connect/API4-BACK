package com.bifrostconnect.api_geo.service;

import com.bifrostconnect.api_geo.entity.OcorrenciaValidacao;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.entity.RegraValidacao;
import com.bifrostconnect.api_geo.repository.OcorrenciaValidacaoRepository;
import com.bifrostconnect.api_geo.repository.RegraValidacaoRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ValidacaoService {

    private final RegraValidacaoRepository regraRepository;
    private final OcorrenciaValidacaoRepository ocorrenciaRepository;

    public ValidacaoService(RegraValidacaoRepository regraRepository, OcorrenciaValidacaoRepository ocorrenciaRepository) {
        this.regraRepository = regraRepository;
        this.ocorrenciaRepository = ocorrenciaRepository;
    }

    public boolean executarValidacoes(Processo processo, String nomeArquivo) {
        List<RegraValidacao> regras = regraRepository.findByAtivoTrue();
        boolean possuiErros = false;

        for (RegraValidacao regra : regras) {
            // Exemplo de regra: valida extensão
            if ("VALIDAR_EXTENSAO".equalsIgnoreCase(regra.getNome())) {
                if (!nomeArquivo.endsWith(".zip") && !nomeArquivo.endsWith(".geojson") && !nomeArquivo.endsWith(".shp")) {
                    registrarOcorrencia(processo, regra, "ERRO", "Extensão de arquivo não suportada para validação geo.");
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