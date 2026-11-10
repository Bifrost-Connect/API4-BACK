package com.bifrostconnect.api_geo.controller;

import com.bifrostconnect.api_geo.dto.LinhagemResponseDTO;
import com.bifrostconnect.api_geo.entity.Auditoria;
import com.bifrostconnect.api_geo.repository.AuditoriaRepository;
import com.bifrostconnect.api_geo.service.LinhagemService;
import com.bifrostconnect.api_geo.service.ProcessoService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.bifrostconnect.api_geo.security.Permissao;
import com.bifrostconnect.api_geo.security.RequirePermission;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/indicadores")
@RequiredArgsConstructor
public class IndicadorController {

    // Tarefa 14: código gravado em auditoria.acao para cada exportação gerada
    private static final String ACAO_EXPORTACAO = "EXPORTACAO_GERADA";
    private static final String VERSAO_NAO_FECHADA = "NAO_FECHADA";
    private static final int TAMANHO_MAX_VALOR_FILTRO = 1000;

    private final LinhagemService linhagemService;
    private final ProcessoService processoService;
    private final JdbcTemplate jdbcTemplate;
    private final AuditoriaRepository auditoriaRepository;
    private final ObjectMapper objectMapper;

    @RequirePermission(Permissao.LINHAGEM_CONSULTAR)
    @GetMapping("/{id}/linhagem")
    public ResponseEntity<LinhagemResponseDTO> consultarLinhagem(@PathVariable Long id) {
        LinhagemResponseDTO linhagem = linhagemService.buscarLinhagem(id);
        return ResponseEntity.ok(linhagem);
    }

    @RequirePermission(Permissao.VERSAO_PUBLICAR)
    @PatchMapping("/{id}/fechar")
    public ResponseEntity<Void> fecharIndicador(
            @PathVariable Long id,
            @RequestHeader(value = "X-Gestor-ID", required = false) Long gestorIdHeader,
            @RequestParam(value = "gestorId", required = false) Long gestorIdParam) {
        
        Long gestorId = gestorIdHeader != null ? gestorIdHeader : (gestorIdParam != null ? gestorIdParam : 1L);
        processoService.fecharIndicador(id, gestorId);
        return ResponseEntity.noContent().build();
    }

    // Tarefa 7 (API de Exportação): Rota GET /api/indicadores/{id}/exportar (ou /versoes/{id}/exportar)
<<<<<<< Updated upstream
    @RequirePermission(Permissao.RELATORIO_EXPORTAR)
=======
    // Tarefa 14: toda exportação gerada é registrada na tabela auditoria (gestor, versão, formato,
    // filtros, contagem de linhas e data/hora). Se o registro falhar, o arquivo não é entregue.
>>>>>>> Stashed changes
    @GetMapping("/{id}/exportar")
    public ResponseEntity<Resource> exportarVersao(
            @PathVariable Long id,
            @RequestParam(value = "formato", defaultValue = "geojson") String formato,
            @RequestHeader(value = "X-Gestor-ID", required = false) Long gestorIdHeader,
            @RequestParam(value = "gestorId", required = false) Long gestorIdParam,
            Authentication authentication,
            HttpServletRequest request) {

        Long gestorId = identificarGestorSolicitante(gestorIdHeader, gestorIdParam, authentication);

        Resource arquivo = processoService.exportarArquivoConsolidado(id, formato);

        registrarAuditoriaExportacao(id, gestorId, formato, request.getParameterMap(), arquivo, request.getRemoteAddr());
        
        String contentType = formato.equalsIgnoreCase("csv") ? "text/csv" : "application/geo+json";
        String extensao = formato.equalsIgnoreCase("csv") ? "csv" : "geojson";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"exportado_" + id + "." + extensao + "\"")
                .body(arquivo);
    }

    // ------------------------------------------------------------------
    // Tarefa 14: auditoria da exportação
    // ------------------------------------------------------------------

    // Ordem: cabeçalho X-Gestor-ID, parâmetro gestorId (mesmo padrão do /fechar) e, por último,
    // o usuário autenticado (e-mail ou parte antes do @). O gestor precisa existir e estar ativo.
    private Long identificarGestorSolicitante(
            Long gestorIdCabecalho,
            Long gestorIdParametro,
            Authentication authentication) {

        Long informado = gestorIdCabecalho != null ? gestorIdCabecalho : gestorIdParametro;

        if (informado != null) {
            List<Boolean> situacao = jdbcTemplate.queryForList(
                    "SELECT ativo FROM usuario WHERE id = ?", Boolean.class, informado);

            if (situacao.isEmpty()) {
                throw new ExportacaoAuditoriaException(HttpStatus.BAD_REQUEST,
                        "O gestor solicitante informado não existe: " + informado);
            }

            if (!Boolean.TRUE.equals(situacao.get(0))) {
                throw new ExportacaoAuditoriaException(HttpStatus.FORBIDDEN,
                        "O gestor solicitante está inativo e não pode exportar.");
            }

            return informado;
        }

        if (authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getName() != null
                && !authentication.getName().isBlank()) {

            String login = authentication.getName().trim().toLowerCase();

            List<Long> ids = jdbcTemplate.queryForList("""
                    SELECT id
                    FROM usuario
                    WHERE ativo = TRUE
                      AND (lower(email) = ? OR split_part(lower(email), '@', 1) = ?)
                    ORDER BY id
                    LIMIT 1
                    """, Long.class, login, login);

            if (!ids.isEmpty()) {
                return ids.get(0);
            }
        }

        throw new ExportacaoAuditoriaException(HttpStatus.BAD_REQUEST,
                "Informe o gestor solicitante da exportação (cabeçalho X-Gestor-ID ou parâmetro gestorId). "
                        + "Toda exportação precisa ser auditada.");
    }

    // Usa só a tabela auditoria (sem migration): usuario_id = gestor, processo_id = carga exportada,
    // data_hora = carimbo; versão, formato, filtros e linhas vão em detalhes (JSON).
    private void registrarAuditoriaExportacao(
            Long processoId,
            Long gestorId,
            String formatoSolicitado,
            Map<String, String[]> parametros,
            Resource arquivo,
            String ip) {

        // mesma regra do ProcessoService: tudo que não é csv sai como GeoJSON
        boolean csv = formatoSolicitado != null && formatoSolicitado.trim().equalsIgnoreCase("csv");
        String formato = csv ? "CSV" : "GEOJSON";

        String versao = buscarVersao(processoId);
        long linhas = contarLinhas(arquivo, csv);
        LocalDateTime agora = LocalDateTime.now();

        Map<String, Object> detalhes = new LinkedHashMap<>();
        detalhes.put("gestorId", gestorId);
        detalhes.put("processoId", processoId);
        detalhes.put("versao", versao);
        detalhes.put("formato", formato);
        detalhes.put("filtros", extrairFiltros(parametros));
        detalhes.put("linhas", linhas);
        detalhes.put("dataHoraExportacao", agora.toString());

        String detalhesJson;
        try {
            detalhesJson = objectMapper.writeValueAsString(detalhes);
        } catch (JsonProcessingException e) {
            throw new ExportacaoAuditoriaException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Não foi possível montar o registro de auditoria da exportação.", e);
        }

        Auditoria auditoria = new Auditoria();
        auditoria.setUsuarioId(gestorId);
        auditoria.setProcessoId(processoId);
        auditoria.setAcao(ACAO_EXPORTACAO);
        auditoria.setDescricao("Exportação em " + formato + " da versão " + versao
                + " do processo " + processoId + " (" + linhas + " linha(s)).");
        auditoria.setDetalhes(detalhesJson);
        auditoria.setDataHora(agora);
        auditoria.setIp(ip != null && ip.length() > 45 ? ip.substring(0, 45) : ip);

        auditoriaRepository.save(auditoria);
    }

    private String buscarVersao(Long processoId) {
        List<String> versoes = jdbcTemplate.queryForList(
                "SELECT versao_oficial FROM processo WHERE id = ?", String.class, processoId);

        if (versoes.isEmpty() || versoes.get(0) == null || versoes.get(0).isBlank()) {
            return VERSAO_NAO_FECHADA;
        }

        return versoes.get(0);
    }

    // todos os parâmetros da requisição, menos os que não são filtro
    private Map<String, Object> extrairFiltros(Map<String, String[]> parametros) {
        Map<String, Object> filtros = new LinkedHashMap<>();

        if (parametros == null) {
            return filtros;
        }

        for (Map.Entry<String, String[]> entrada : parametros.entrySet()) {
            String nome = entrada.getKey();

            if (nome == null
                    || nome.equalsIgnoreCase("gestorId")
                    || nome.equalsIgnoreCase("formato")) {
                continue;
            }

            String[] valores = entrada.getValue() == null ? new String[0] : entrada.getValue();
            List<String> lista = Arrays.stream(valores)
                    .map(v -> v != null && v.length() > TAMANHO_MAX_VALOR_FILTRO
                            ? v.substring(0, TAMANHO_MAX_VALOR_FILTRO)
                            : v)
                    .toList();

            filtros.put(nome, lista.size() == 1 ? lista.get(0) : lista);
        }

        return filtros;
    }

    // CSV: linhas de dados (sem o cabeçalho). GeoJSON: quantidade de features.
    private long contarLinhas(Resource arquivo, boolean csv) {
        try (InputStream entrada = arquivo.getInputStream()) {
            return csv ? contarLinhasCsv(entrada) : contarFeaturesGeoJson(entrada);
        } catch (IOException e) {
            throw new ExportacaoAuditoriaException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Não foi possível contar as linhas da exportação para registrar a auditoria.", e);
        }
    }

    private long contarLinhasCsv(InputStream entrada) throws IOException {
        long total = 0;

        try (BufferedReader leitor = new BufferedReader(
                new InputStreamReader(entrada, StandardCharsets.UTF_8))) {

            String linha;
            while ((linha = leitor.readLine()) != null) {
                if (!linha.isBlank()) {
                    total++;
                }
            }
        }

        return Math.max(0, total - 1);
    }

    // leitura em streaming: não carrega o GeoJSON inteiro na memória
    private long contarFeaturesGeoJson(InputStream entrada) throws IOException {
        try (JsonParser parser = objectMapper.getFactory().createParser(entrada)) {

            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return 0;
            }

            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String campo = parser.currentName();
                JsonToken valor = parser.nextToken();

                if ("features".equals(campo) && valor == JsonToken.START_ARRAY) {
                    long total = 0;
                    while (parser.nextToken() == JsonToken.START_OBJECT) {
                        total++;
                        parser.skipChildren();
                    }
                    return total;
                }

                parser.skipChildren();
            }
        }

        return 0;
    }

    // devolve o motivo no formato { "erro": ... } (só vale para este controller)
    @ExceptionHandler(ExportacaoAuditoriaException.class)
    public ResponseEntity<Map<String, String>> handleExportacaoAuditoria(ExportacaoAuditoriaException ex) {
        Map<String, String> error = new HashMap<>();
        error.put("erro", ex.getMessage());

        return ResponseEntity
                .status(ex.getStatus())
                .body(error);
    }

    private static class ExportacaoAuditoriaException extends RuntimeException {

        private final HttpStatus status;

        ExportacaoAuditoriaException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        ExportacaoAuditoriaException(HttpStatus status, String message, Throwable causa) {
            super(message, causa);
            this.status = status;
        }

        HttpStatus getStatus() {
            return status;
        }
    }
}