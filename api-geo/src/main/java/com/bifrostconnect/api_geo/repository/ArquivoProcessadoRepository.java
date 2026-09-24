package com.bifrostconnect.api_geo.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.bifrostconnect.api_geo.entity.ArquivoProcessado;

@Repository
public interface ArquivoProcessadoRepository extends JpaRepository<ArquivoProcessado, Long> {
}