package com.bifrostconnect.api_geo.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.bifrostconnect.api_geo.service.ComparacaoService;

@RestController
@RequestMapping("/analise/comparacao")
public class ComparacaoController {

    private final ComparacaoService comparacaoService;

    public ComparacaoController(ComparacaoService comparacaoService) {
        this.comparacaoService = comparacaoService;
    }

    @GetMapping("/safras")
    public ResponseEntity<?> listarSafrasElegiveis(
            @RequestParam("conjunto") String conjunto,
            @RequestParam("regiao") Long idRegiao) {

        try {
            return ResponseEntity.ok(
                    comparacaoService.listarSafrasElegiveis(
                            conjunto, idRegiao));

        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(
                    Map.of(
                            "erro",
                            e.getReason() == null
                                    ? "Não foi possível consultar as safras elegíveis."
                                    : e.getReason()));
        }
    }

    @GetMapping("/camadas")
    public ResponseEntity<?> compararCamadas(
            @RequestParam("id_carga_a") Long idCargaA,
            @RequestParam("id_carga_b") Long idCargaB,
            @RequestParam(value = "id_regiao", required = false) Long idRegiao,
            @RequestParam(value = "bbox", required = false) String bbox) {

        try {
            return ResponseEntity.ok(
                    comparacaoService.compararCamadas(
                            idCargaA,
                            idCargaB,
                            idRegiao,
                            bbox));

        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(
                    Map.of(
                            "erro",
                            e.getReason() == null
                                    ? "Não foi possível comparar as camadas."
                                    : e.getReason()));
        }
    }
}