package com.bifrostconnect.api_geo.controller;

import com.bifrostconnect.api_geo.service.CatalogoBasesService;
import com.bifrostconnect.api_geo.service.CatalogoBasesService.FiltroCatalogo;

import org.springframework.http.ResponseEntity;
import com.bifrostconnect.api_geo.security.Permissao;
import com.bifrostconnect.api_geo.security.RequirePermission;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/catalogo")
@RequirePermission(Permissao.CATALOGO_CONSULTAR)
public class CatalogoController {

    private final CatalogoBasesService catalogoBasesService;

    public CatalogoController(CatalogoBasesService catalogoBasesService) {
        this.catalogoBasesService = catalogoBasesService;
    }

    // conjuntoId/anoSafra são os nomes antigos dos filtros, mantidos pro front atual não quebrar
    @GetMapping("/bases")
    public ResponseEntity<Map<String, Object>> listarBases(
            @RequestParam(required = false) String busca,
            @RequestParam(required = false) Long conjunto,
            @RequestParam(required = false) Long conjuntoId,
            @RequestParam(required = false) String ano,
            @RequestParam(required = false) String anoSafra,
            @RequestParam(required = false) Long orgao,
            @RequestParam(required = false) String classificacao,
            @RequestParam(required = false) String situacao,
            @RequestParam(name = "apenas_disponiveis", defaultValue = "false") boolean apenasDisponiveis,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + CatalogoBasesService.TAMANHO_PADRAO) int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direcao) {

        FiltroCatalogo filtro = new FiltroCatalogo(
                busca,
                conjunto != null ? conjunto : conjuntoId,
                ano != null ? ano : anoSafra,
                orgao,
                classificacao,
                situacao,
                apenasDisponiveis);

        Map<String, Object> pagina = catalogoBasesService.listarBases(filtro, page, size, sort, direcao);

        if (((Number) pagina.get("total_items")).longValue() == 0) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(pagina);
    }

    @GetMapping("/resumo")
    public ResponseEntity<Map<String, Object>> resumo() {
        return ResponseEntity.ok(catalogoBasesService.resumo());
    }

    @GetMapping("/opcoes-filtros")
    public ResponseEntity<Map<String, Object>> opcoesFiltros() {
        return ResponseEntity.ok(catalogoBasesService.opcoesFiltros());
    }

    // ficha resumida (a mesma linha da listagem). ID que não existe cai no 404 do GlobalExceptionHandler
    @GetMapping("/bases/{id_base}")
    public ResponseEntity<Map<String, Object>> buscarBase(@PathVariable("id_base") Long idBase) {
        return ResponseEntity.ok(catalogoBasesService.buscarBase(idBase));
    }

    @GetMapping("/bases/{id_base}/detalhes")
    public ResponseEntity<Map<String, Object>> buscarDetalhes(
            @PathVariable("id_base") Long idBase,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + CatalogoBasesService.TAMANHO_PADRAO) int size) {

        return ResponseEntity.ok(catalogoBasesService.buscarDetalhes(idBase, page, size));
    }
}
