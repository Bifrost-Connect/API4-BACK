package com.bifrostconnect.api_geo.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.bifrostconnect.api_geo.entity.ArquivoOriginal;

@Repository
public interface ArquivoOriginalRepository extends JpaRepository<ArquivoOriginal, Long> {
    // O Spring já implementa o .save() e .findById() por padrão!
    // Podemos buscar se um arquivo já existe pelo hash:
    boolean existsByHashSha256(String hashSha256);
}