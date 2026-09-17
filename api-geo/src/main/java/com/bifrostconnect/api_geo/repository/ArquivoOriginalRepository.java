package com.bifrostconnect.api_geo.repository;

import com.bifrostconnect.api_geo.entity.ArquivoOriginal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ArquivoOriginalRepository extends JpaRepository<ArquivoOriginal, Long> {
}