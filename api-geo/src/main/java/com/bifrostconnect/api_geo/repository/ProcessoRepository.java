package com.bifrostconnect.api_geo.repository;

import com.bifrostconnect.api_geo.entity.Processo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessoRepository extends JpaRepository<Processo, Long> {
}