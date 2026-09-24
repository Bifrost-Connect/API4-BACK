package com.bifrostconnect.api_geo.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bifrostconnect.api_geo.dto.MetadadosCargaRequest;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;

@Service
public class MetadadosCargaService {

    private final ProcessoRepository processoRepository;

    public MetadadosCargaService(ProcessoRepository processoRepository) {
        this.processoRepository = processoRepository;
    }

    @Transactional
    public Processo salvarMetadados(MetadadosCargaRequest request) {
        // Validações de regra de negócio adicionais (ano e EPSG)
        validarRegrasNegocio(request);

        // Mapeamento do DTO para a Entidade de Banco
        Processo processo = new Processo();
        
        // Atribuições completas para satisfazer as restrições NOT NULL do banco
        processo.setOperadorId(request.getOrgaoId()); // Ou ID do operador/usuário correspondente
        processo.setOrgaoId(request.getOrgaoId());     // Define o órgão vinculado
        processo.setConjuntoId(request.getConjuntoId()); // Define o conjunto vinculado (evita o erro null na coluna)
        
        processo.setAno(request.getAnoSafra());
        processo.setEpsg(request.getEpsgOrigem());

        // Salva e retorna o processo criado
        return processoRepository.save(processo);
    }

    private void validarRegrasNegocio(MetadadosCargaRequest request) {
        try {
            int ano = Integer.parseInt(request.getAnoSafra());
            int anoAtual = java.time.Year.now().getValue();

            if (ano < 2000 || ano > anoAtual) {
                throw new IllegalArgumentException(
                        "O ano da safra deve estar entre 2000 e " + anoAtual
                );
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("O ano da safra deve ser um valor numérico");
        }

        if (!request.getEpsgOrigem().equals("4674") && !request.getEpsgOrigem().equals("4326")) {
            throw new IllegalArgumentException("O EPSG de origem deve ser 4674 ou 4326");
        }
    }
}