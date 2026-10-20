
package com.bifrostconnect.api_geo.controller;

import com.bifrostconnect.api_geo.dto.FeicaoDetalheResponseDTO;
import com.bifrostconnect.api_geo.service.MapaService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mapa")
public class MapaController {

    private final MapaService mapaService;

    public MapaController(MapaService mapaService) {
        this.mapaService = mapaService;
    }

    @GetMapping("/feicoes/{id_poligono}")
    public ResponseEntity<FeicaoDetalheResponseDTO> buscarDetalhesFeicao(
            @PathVariable("id_poligono") Long idPoligono) {

        FeicaoDetalheResponseDTO resposta =
                mapaService.buscarDetalhesPorId(idPoligono);

        return ResponseEntity.ok(resposta);
    }
}
