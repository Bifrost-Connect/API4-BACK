package com.bifrostconnect.api_geo.controller;

import com.bifrostconnect.api_geo.dto.EvolucaoRequestDTO;
import com.bifrostconnect.api_geo.dto.EvolucaoResponseDTO;
import com.bifrostconnect.api_geo.dto.ImovelComparacaoDTO;
import com.bifrostconnect.api_geo.service.MotorGeomatematicoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/analise/comparacao")
public class AnaliseEspacialController {

    private final MotorGeomatematicoService motorGeomatematicoService;

    public AnaliseEspacialController(MotorGeomatematicoService motorGeomatematicoService) {
        this.motorGeomatematicoService = motorGeomatematicoService;
    }

    // Tarefa 15: API de Métricas Comparativas e Camada de Diferença
    @PostMapping("/evolucao")
    public ResponseEntity<EvolucaoResponseDTO> compararEvolucaoGlobal(@RequestBody EvolucaoRequestDTO request) {
        
        // 1. Aqui você deve buscar as geometrias unificadas (ou listar as geometrias iteráveis) 
        // referentes à Safra A e Safra B no seu repositório usando os IDs do request.
        // Exemplo: Geometry geometriaSafraA = repositorio.obterGeometriaConsolidada(request.safraAId());
        
        /* 
        // 2. Acionar o Motor (Tarefa 14)
        Geometry novosAlertas = motorGeomatematicoService.calcularNovosAlertas(geometriaSafraA, geometriaSafraB);
        Geometry reducaoAlertas = motorGeomatematicoService.calcularReducao(geometriaSafraA, geometriaSafraB);
        Geometry mantidos = motorGeomatematicoService.calcularPersistente(geometriaSafraA, geometriaSafraB);

        // 3. Extrair as áreas
        double areaA = motorGeomatematicoService.calcularAreaEmHectares(geometriaSafraA);
        double areaB = motorGeomatematicoService.calcularAreaEmHectares(geometriaSafraB);
        double saldoVariacao = areaB - areaA;
        double percentual = (areaA == 0) ? 100.0 : (saldoVariacao / areaA) * 100.0;
        
        // 4. Montar e classificar o GeoJSON final unindo as 3 geometrias resultantes
        String geoJsonFinal = "{}"; // Substituir pelo conversor de Geometry para GeoJSON (ex: org.wololo.jts2geojson ou nativo do PostGIS)
        */

        // Retorno mockado até a conexão com os Repositórios ser feita
        EvolucaoResponseDTO response = new EvolucaoResponseDTO(
            1500.5, 1750.0, 249.5, 16.6, "{\"type\": \"FeatureCollection\", \"features\": []}"
        );
        
        return ResponseEntity.ok(response);
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