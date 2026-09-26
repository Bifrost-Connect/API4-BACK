package com.bifrostconnect.api_geo.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bifrostconnect.api_geo.entity.ArquivoOriginal;


public interface ArquivoOriginalRepository extends JpaRepository<ArquivoOriginal, Long> {
    boolean existsByHashSha256(String hashSha256);
}