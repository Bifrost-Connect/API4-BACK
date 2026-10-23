package com.bifrostconnect.api_geo.service;

import com.bifrostconnect.api_geo.dto.LinhagemResponseDTO;
import com.bifrostconnect.api_geo.entity.ArquivoOriginal;
import com.bifrostconnect.api_geo.entity.LogValidacao;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ArquivoOriginalRepository;
import com.bifrostconnect.api_geo.repository.LogValidacaoRepository;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LinhagemService {

    private final ProcessoRepository processoRepository;
    private final ArquivoOriginalRepository arquivoOriginalRepository;
    
    // Injeções adicionadas para a Tarefa 15
    private final LogValidacaoRepository logValidacaoRepository;
    private final JdbcTemplate jdbcTemplate;

    public LinhagemResponseDTO buscarLinhagem(Long indicadorId) {
        // Busca o processo/indicador. Se não achar, lança 404 (Tarefa 8)
        Processo processo = processoRepository.findById(indicadorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Indicador/Processo não encontrado."));

        // Busca o arquivo original atrelado ao processo para recuperar o Hash (Tarefa 6 e 9)
        ArquivoOriginal arquivo = arquivoOriginalRepository.findByProcessoId(processo.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Arquivo original da linhagem não encontrado."));

        // Busca os Logs de Quarentena gerados na Tarefa 14
        List<LogValidacao> logs = logValidacaoRepository.findByProcessoId(processo.getId());

        // Busca o Histórico de Etapas no banco de dados (Ajustado para tabelas 'etapa' e 'situacao')
        String sql = """
            SELECT e.nome as etapa, s.nome as situacao, pe.data_inicio, pe.data_fim, pe.mensagem, pe.resultado
            FROM processo_etapa pe
            JOIN etapa e ON pe.etapa_id = e.id
            JOIN situacao s ON pe.situacao_id = s.id
            WHERE pe.processo_id = ?
            ORDER BY pe.data_inicio ASC
            """;

        List<LinhagemResponseDTO.HistoricoEtapaDTO> historico = jdbcTemplate.query(sql, (rs, rowNum) -> 
            LinhagemResponseDTO.HistoricoEtapaDTO.builder()
                .etapa(rs.getString("etapa"))
                .situacao(rs.getString("situacao"))
                .dataInicio(rs.getTimestamp("data_inicio") != null ? rs.getTimestamp("data_inicio").toLocalDateTime() : null)
                .dataFim(rs.getTimestamp("data_fim") != null ? rs.getTimestamp("data_fim").toLocalDateTime() : null)
                .mensagem(rs.getString("mensagem"))
                .resultado(rs.getString("resultado"))
                .build()
        , processo.getId());

        // Identifica a Etapa e Situação atual olhando para o último registro do histórico
        String nomeEtapaAtual = "Desconhecida";
        String nomeSituacaoAtual = "Desconhecida";
        if (!historico.isEmpty()) {
            LinhagemResponseDTO.HistoricoEtapaDTO ultimaEtapa = historico.get(historico.size() - 1);
            nomeEtapaAtual = ultimaEtapa.getEtapa();
            nomeSituacaoAtual = ultimaEtapa.getSituacao();
        }

        List<LinhagemResponseDTO.LogQuarentenaDTO> logsDTO = logs.stream().map(log -> 
            LinhagemResponseDTO.LogQuarentenaDTO.builder()
                .dataDeteccao(log.getDataDeteccao())
                // Extrai a etapa do padrão salvo no ProcessoEstadoService ("Etapa - Erro")
                .etapaFalha(log.getMensagem() != null && log.getMensagem().contains(" - ") ? log.getMensagem().split(" - ")[0] : "Validação")
                .severidade(log.getSeveridade())
                .mensagemErro(log.getMensagem())
                .build()
        ).toList();

        return LinhagemResponseDTO.builder()
                .indicadorId(processo.getId())
                .processoId(processo.getId())
                .arquivoOriginalId(arquivo.getId())
                .nomeArquivoOriginal(arquivo.getNomeOriginal())
                .hashSha256(arquivo.getHashSha256())
                .parametrosProcessamento("Validação Espacial, Regras de Negócio Padrão")
                .dataCarga(processo.getDataCriacao())
                .statusAtual(nomeSituacaoAtual) // Corrigido aqui
                .etapaAtual(nomeEtapaAtual)     // Corrigido aqui
                .historicoEtapas(historico)
                .logsQuarentena(logsDTO)
                .build();
    }
}