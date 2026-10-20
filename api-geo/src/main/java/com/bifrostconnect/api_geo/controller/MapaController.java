package com.bifrostconnect.api_geo.controller;

import com.bifrostconnect.api_geo.dto.FeicaoDetalheResponseDTO;
import com.bifrostconnect.api_geo.entity.CamadaReferencia;
import com.bifrostconnect.api_geo.repository.CamadaReferenciaRepository;
import com.bifrostconnect.api_geo.service.MapaService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/mapa")
public class MapaController {

    private final MapaService mapaService;
    private final CamadaReferenciaRepository camadaReferenciaRepository;

    public MapaController(MapaService mapaService, CamadaReferenciaRepository camadaReferenciaRepository) {
        this.mapaService = mapaService;
        this.camadaReferenciaRepository = camadaReferenciaRepository;
    }

    /**
     * Tarefa 16: Rota GET /api/mapa/feicoes/{id_poligono} para detalhes no clique do mapa.
     */
    @GetMapping("/feicoes/{id_poligono}")
    public ResponseEntity<FeicaoDetalheResponseDTO> buscarDetalhesFeicao(
            @PathVariable("id_poligono") Long idPoligono) {

        FeicaoDetalheResponseDTO resposta =
                mapaService.buscarDetalhesPorId(idPoligono);

        return ResponseEntity.ok(resposta);
    }

    /**
     * Tarefa 15 e Tarefa 17: Rota GET /api/mapa/camadas com blindagem de publicação 
     * e controle estrito de volume de payload (retorna 413 se exceder o limite seguro).
     */
   @GetMapping("/camadas")
    public ResponseEntity<List<CamadaReferencia>> listarCamadasAnalista(
            @RequestParam(required = false) String conjunto,
            @RequestParam(required = false) Integer ano) {

        try {
            long totalRegistros = camadaReferenciaRepository.contarCamadasPublicadasAnalista(conjunto, ano);
            long LIMITE_MAXIMO_PAYLOAD = 5000;

            if (totalRegistros > LIMITE_MAXIMO_PAYLOAD) {
                throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Volume de dados excessivo (" + totalRegistros + " feições)."
                );
            }

            List<CamadaReferencia> camadas = camadaReferenciaRepository
                    .buscarCamadasPublicadasAnalista(conjunto, ano);

            return ResponseEntity.ok(camadas);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            // Retorna lista vazia caso ocorra incompatibilidade de serialização geométrica na tabela vazia
            return ResponseEntity.ok(List.of());
        }
    }
}