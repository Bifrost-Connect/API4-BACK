package com.bifrostconnect.api_geo.service;

import com.bifrostconnect.api_geo.dto.LinhagemResponseDTO;
import com.bifrostconnect.api_geo.entity.ArquivoOriginal;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ArquivoOriginalRepository;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class LinhagemService {

    private final ProcessoRepository processoRepository;
    private final ArquivoOriginalRepository arquivoOriginalRepository;

    public LinhagemResponseDTO buscarLinhagem(Long indicadorId) {
        // Busca o processo/indicador. Se não achar, lança 404 (Tarefa 8)
        Processo processo = processoRepository.findById(indicadorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Indicador/Processo não encontrado."));

        // Busca o arquivo original atrelado ao processo para recuperar o Hash (Tarefa 6 e 9)
        ArquivoOriginal arquivo = arquivoOriginalRepository.findByProcessoId(processo.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Arquivo original da linhagem não encontrado."));

        return LinhagemResponseDTO.builder()
                .indicadorId(processo.getId())
                .processoId(processo.getId())
                .arquivoOriginalId(arquivo.getId())
                .nomeArquivoOriginal(arquivo.getNomeOriginal())
                .hashSha256(arquivo.getHashSha256())
                .parametrosProcessamento("Validação Espacial, Regras de Negócio Padrão")
                .dataCarga(processo.getDataCriacao())
                .build();
    }
}