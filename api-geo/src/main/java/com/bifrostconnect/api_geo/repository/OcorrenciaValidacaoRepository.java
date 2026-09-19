package com.bifrostconnect.api_geo.repository;

import com.bifrostconnect.api_geo.entity.OcorrenciaValidacao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OcorrenciaValidacaoRepository extends JpaRepository<OcorrenciaValidacao, Long> {
    List<OcorrenciaValidacao> findByProcessoId(Long processoId);
}