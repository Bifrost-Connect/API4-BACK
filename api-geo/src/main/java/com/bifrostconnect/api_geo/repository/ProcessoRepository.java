package com.bifrostconnect.api_geo.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bifrostconnect.api_geo.entity.Processo;

public interface ProcessoRepository extends JpaRepository<Processo, Long> {
}