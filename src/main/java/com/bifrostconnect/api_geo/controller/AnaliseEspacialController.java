package com.bifrostconnect.api_geo.controller;

import java.util.Map;

import com.bifrostconnect.api_geo.dto.EvolucaoRequestDTO;
import com.bifrostconnect.api_geo.dto.ImovelComparacaoDTO;
import com.bifrostconnect.api_geo.service.ComparacaoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

// o spring.mvc.servlet.path já é /api, então a rota final fica /api/analise/comparacao/...
@RestController
@RequestMapping("/analise/comparacao")
public class AnaliseEspacialController {

    private final ComparacaoService comparacaoService;

    public AnaliseEspacialController(ComparacaoService comparacaoService) {
        this.comparacaoService = comparacaoService;
    }

    // Tarefa 15: API de Métricas Comparativas e Camada de Diferença.
    // O cálculo (B - A, A - B, A ∩ B e as áreas) roda no PostGIS dentro do ComparacaoService;
    // as validações da Tarefa 17 e o controle de payload da Tarefa 18 também ficam lá
    @PostMapping("/evolucao")
    public ResponseEntity<?> compararEvolucaoGlobal(@RequestBody EvolucaoRequestDTO request) {
        try {
            return ResponseEntity.ok(
                    comparacaoService.calcularEvolucao(
                            request.safraAId(),
                            request.safraBId(),
                            request.idRegiao(),
                            request.bbox()));

        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(
                    Map.of(
                            "erro",
                            e.getReason() == null
                                    ? "Não foi possível calcular a evolução entre as safras."
                                    : e.getReason()));
        }
    }

    // Tarefa 16: API de Comparação Pontual por Imóvel Rural
    @GetMapping("/imovel/{identificador}")
    public ResponseEntity<ImovelComparacaoDTO> compararPorImovel(
            @PathVariable String identificador,
            @RequestParam Long safraAId,
            @RequestParam Long safraBId) {

        // 1. Buscar no banco o imóvel específico (CAR) associado à Safra A e depois à Safra B.
        // 2. Calcular a diferença de área especificamente para essa feição.
        // 3. Determinar o status da evolução.

        // Retorno mockado
        ImovelComparacaoDTO response = new ImovelComparacaoDTO(
            identificador, 45.0, 50.0, 5.0, "AUMENTO"
        );

        return ResponseEntity.ok(response);
    }
}
