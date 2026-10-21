package com.bifrostconnect.api_geo.controller;

import com.bifrostconnect.api_geo.dto.FeicaoDetalheResponseDTO;
import com.bifrostconnect.api_geo.entity.CamadaReferencia;
import com.bifrostconnect.api_geo.repository.CamadaReferenciaRepository;
import com.bifrostconnect.api_geo.service.CamadasMapaService;
import com.bifrostconnect.api_geo.service.MapaService;
import com.bifrostconnect.api_geo.service.RevisaoPublicacaoService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MapaController {

    private final MapaService mapaService;
    private final CamadaReferenciaRepository camadaReferenciaRepository;
    private final RevisaoPublicacaoService revisaoPublicacaoService; // Adicionado para Tarefa 13
    private final CamadasMapaService camadasMapaService;               // Adicionado para Tarefa 14

    public MapaController(
            MapaService mapaService, 
            CamadaReferenciaRepository camadaReferenciaRepository,
            RevisaoPublicacaoService revisaoPublicacaoService,
            CamadasMapaService camadasMapaService) {
        this.mapaService = mapaService;
        this.camadaReferenciaRepository = camadaReferenciaRepository;
        this.revisaoPublicacaoService = revisaoPublicacaoService;
        this.camadasMapaService = camadasMapaService;
    }

    /**
     * Tarefa 16: Rota GET /api/mapa/feicoes/{id_poligono} para detalhes no clique do mapa.
     */
    @GetMapping("/mapa/feicoes/{id_poligono}")
    public ResponseEntity<FeicaoDetalheResponseDTO> buscarDetalhesFeicao(
            @PathVariable("id_poligono") Long idPoligono) {

        FeicaoDetalheResponseDTO resposta = mapaService.buscarDetalhesPorId(idPoligono);
        return ResponseEntity.ok(resposta);
    }

    /**
     * Tarefa 15 e Tarefa 17 (Legado): Rota GET /api/mapa/camadas com blindagem de payload.
     */
    @GetMapping("/mapa/camadas-legado")
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
            return ResponseEntity.ok(List.of());
        }
    }

    // ==========================================
    // US08 - Tarefa 13: API de Mapa e Histórico para Revisão de Publicação
    // ==========================================
    @PreAuthorize("hasAnyRole('AUDITOR', 'ADMINISTRADOR', 'OPERADOR')")
    @GetMapping("/publicacao/{id_carga}/revisao")
    public ResponseEntity<Map<String, Object>> obterRevisaoPublicacao(@PathVariable("id_carga") Long idCarga) {
        Map<String, Object> resultado = revisaoPublicacaoService.obterDadosRevisao(idCarga);
        return ResponseEntity.ok(resultado);
    }

    // ==========================================
    // US08 - Tarefa 14: API de Camadas Publicadas para o Mapa (GeoJSON com Bounding Box e Filtros)
    // ==========================================
    @PreAuthorize("hasAnyRole('AUDITOR', 'ADMINISTRADOR', 'OPERADOR')")
    @GetMapping("/mapa/camadas")
    public ResponseEntity<Map<String, Object>> listarCamadasMapaGeoJson(
            @RequestParam(required = false) Long conjuntoId,
            @RequestParam(required = false) String anoSafra,
            @RequestParam(required = false) String bbox) {
        
        Map<String, Object> geoJson = camadasMapaService.buscarCamadasPorFiltros(conjuntoId, anoSafra, bbox);
        return ResponseEntity.ok(geoJson);
    }
}