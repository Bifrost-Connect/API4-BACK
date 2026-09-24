package com.bifrostconnect.api_geo.repository;

import com.bifrostconnect.api_geo.entity.LogProcessamento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LogProcessamentoRepository extends JpaRepository<LogProcessamento, Long> {
}