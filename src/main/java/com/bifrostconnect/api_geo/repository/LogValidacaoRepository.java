package com.bifrostconnect.api_geo.repository;

import com.bifrostconnect.api_geo.entity.LogValidacao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LogValidacaoRepository extends JpaRepository<LogValidacao, Long> {
}