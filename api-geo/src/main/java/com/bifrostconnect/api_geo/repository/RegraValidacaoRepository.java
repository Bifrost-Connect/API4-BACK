package com.bifrostconnect.api_geo.repository;

import com.bifrostconnect.api_geo.entity.RegraValidacao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RegraValidacaoRepository extends JpaRepository<RegraValidacao, Long> {
    List<RegraValidacao> findByAtivoTrue();
}