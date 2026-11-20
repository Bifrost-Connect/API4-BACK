package com.bifrostconnect.api_geo.service;

import com.bifrostconnect.api_geo.dto.LinhagemResponseDTO;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// US08 - Tarefa 12: quando o gestor aprova o Tratamento, junta tudo que a revisão de
// Publicação precisa (geometrias tratadas + hectares + linhagem) num GeoJSON só.
// Usa as tabelas da V22 (historico_aprovacoes_gestor e processo.versao_fechada),
// que vêm da branch do banco/US06 no merge.
@Service
@RequiredArgsConstructor
public class ConsolidacaoPublicacaoService {

    private static final Path PASTA_PROCESSADA = Path.of("zona_processada_storage");
    static final String STATUS_AGUARDANDO_REVISAO = "AGUARDANDO_REVISAO";

    private final JdbcTemplate jdbcTemplate;
    private final ProcessoRepository processoRepository;
    private final ProcessoEstadoService processoEstadoService;
    private final LinhagemService linhagemService;
    private final AuditoriaLogService auditoriaLogService;
    private final ObjectMapper objectMapper;

    @Transactional
    public Map<String, Object> aprovarTratamento(Long processoId, Long gestorId, String parecer) {
        Processo processo = processoRepository.findById(processoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Carga/Processo não encontrado."));

        validarSePodeConsolidar(processoId);
        registrarAprovacao(processoId, gestorId, parecer);

        Map<String, Object> resultado = consolidar(processo, gestorId);

        // por último, porque o atualizarEstado roda em REQUIRES_NEW e commita sozinho:
        // se algo der errado antes, a aprovação e o pacote voltam e a carga nem sai do lugar
        processoEstadoService.atualizarEstado(
                processo,
                "PUBLICACAO",
                "EM_ANDAMENTO",
                "Tratamento aprovado pelo gestor. Pacote de publicação pronto para revisão.");

        auditoriaLogService.registrarAuditoria(
                gestorId,
                "APROVACAO_TRATAMENTO",
                "Tratamento aprovado e pacote de publicação consolidado para o processo ID: " + processoId);

        return resultado;
    }

    private void validarSePodeConsolidar(Long processoId) {
        Map<String, Object> estado = jdbcTemplate.queryForMap("""
            SELECT p.versao_fechada, s.nome AS situacao
            FROM processo p
            LEFT JOIN situacao s ON s.id = p.situacao_atual_id
            WHERE p.id = ?
            """, processoId);

        if (Boolean.TRUE.equals(estado.get("versao_fechada"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Essa carga já foi publicada (versão fechada), não dá pra aprovar o Tratamento de novo.");
        }

        String situacao = (String) estado.get("situacao");
        if ("EM_VALIDACAO".equals(situacao) || "FALHOU".equals(situacao)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A carga tá em quarentena ou falhou (" + situacao + "), não pode seguir pra Publicação.");
        }

        Integer pendentes = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM publicacao WHERE processo_id = ? AND status = ?",
                Integer.class, processoId, STATUS_AGUARDANDO_REVISAO);
        if (pendentes != null && pendentes > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Já tem um pacote dessa carga aguardando a revisão de Publicação.");
        }
    }

    private void registrarAprovacao(Long processoId, Long gestorId, String parecer) {
        try {
            jdbcTemplate.update("""
                INSERT INTO historico_aprovacoes_gestor
                    (processo_id, gestor_id, etapa_avaliada, decisao, parecer_justificativa)
                VALUES (?, ?, 'TRATAMENTO', 'APROVADO', ?)
                """, processoId, gestorId, parecer);
        } catch (DataAccessException e) {
            // a trigger da V22 barra gestor inexistente ou inativo
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Gestor inválido ou inativo (id " + gestorId + ").", e);
        }
    }

    Map<String, Object> consolidar(Processo processo, Long gestorId) {
        Long processoId = processo.getId();

        // mesma regra da vw_copia_vigente (V26): maior versão entre as cópias que têm polígonos
        List<Map<String, Object>> copias = jdbcTemplate.queryForList("""
            SELECT ap.id, ap.arquivo_original_id, ap.versao
            FROM arquivo_processado ap
            WHERE ap.processo_id = ?
              AND EXISTS (SELECT 1 FROM feicao_carga fc WHERE fc.arquivo_processado_id = ap.id)
            ORDER BY ap.versao DESC, ap.id DESC
            LIMIT 1
            """, processoId);

        if (copias.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A carga não tem geometrias tratadas pra consolidar.");
        }

        Long copiaId = ((Number) copias.get(0).get("id")).longValue();
        Long arquivoOriginalId = ((Number) copias.get(0).get("arquivo_original_id")).longValue();

        Map<String, Object> totais = calcularTotais(copiaId);
        LinhagemResponseDTO linhagem = linhagemService.buscarLinhagem(processoId);

        LocalDateTime agora = LocalDateTime.now();

        Map<String, Object> metadados = new LinkedHashMap<>();
        metadados.put("processoId", processoId);
        metadados.put("anoSafra", processo.getAnoSafra());
        metadados.put("conjuntoId", processo.getConjuntoId());
        metadados.put("orgaoId", processo.getOrgaoId());
        metadados.put("arquivoProcessadoOrigemId", copiaId);
        metadados.put("versaoCopiaTratada", copias.get(0).get("versao"));
        metadados.put("gestorAprovacaoTratamentoId", gestorId);
        metadados.put("dataConsolidacao", agora);
        metadados.putAll(totais);

        Integer proximaVersao = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(versao), 0) + 1 FROM arquivo_processado WHERE processo_id = ?",
                Integer.class, processoId);
        String nomeArquivo = processoId + "_pacote_publicacao_v" + proximaVersao + ".geojson";
        Path caminho = PASTA_PROCESSADA.resolve(nomeArquivo);
        Path caminhoTemp = PASTA_PROCESSADA.resolve(nomeArquivo + ".tmp");

        String hash;
        Long pacoteId;
        Long publicacaoId;
        try {
            Files.createDirectories(PASTA_PROCESSADA);
            hash = gravarPacoteEmStreaming(copiaId, metadados, linhagem, caminhoTemp);

            // o pacote vira uma cópia nova gerada na etapa PUBLICACAO. Ela não tem feicao_carga,
            // então a vw_copia_vigente continua apontando pra cópia tratada (de propósito)
            pacoteId = jdbcTemplate.queryForObject("""
                INSERT INTO arquivo_processado
                    (arquivo_original_id, processo_id, etapa_geracao_id, nome_arquivo, formato,
                     url_armazenamento, hash_sha256, epsg, versao)
                VALUES (?, ?, (SELECT id FROM etapa WHERE nome = 'PUBLICACAO'), ?, 'GEOJSON', ?, ?, '4326', ?)
                RETURNING id
                """, Long.class,
                    arquivoOriginalId, processoId, nomeArquivo, caminho.toString(), hash, proximaVersao);

            publicacaoId = jdbcTemplate.queryForObject("""
                INSERT INTO publicacao
                    (processo_id, arquivo_processado_id, usuario_responsavel_id, status, destino, mensagem)
                VALUES (?, ?, ?, ?, 'REVISAO_GESTOR', 'Pacote consolidado após aprovação do Tratamento.')
                RETURNING id
                """, Long.class,
                    processoId, pacoteId, gestorId, STATUS_AGUARDANDO_REVISAO);

            Files.move(caminhoTemp, caminho, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            apagarSeExistir(caminhoTemp);
            apagarSeExistir(caminho);
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Não deu pra gravar o pacote em " + caminho, e);
        }

        Map<String, Object> resposta = new LinkedHashMap<>();
        resposta.put("processoId", processoId);
        resposta.put("publicacaoId", publicacaoId);
        resposta.put("arquivoProcessadoId", pacoteId);
        resposta.put("status", STATUS_AGUARDANDO_REVISAO);
        resposta.put("arquivo", caminho.toString());
        resposta.put("hashSha256", hash);
        resposta.putAll(totais);
        return resposta;
    }

    private String gravarPacoteEmStreaming(
            Long copiaId,
            Map<String, Object> metadados,
            LinhagemResponseDTO linhagem,
            Path caminhoTemp) throws IOException {

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }

        // ST_Transform pra 4326 porque a RFC 7946 pede WGS84 (o banco guarda em SIRGAS 2000 / 4674,
        // na prática a diferença é desprezível, mas o arquivo sai no padrão).
        // Área em geography pra sair em m² de verdade, igual ao CalculoAnaliticoService da US06.
        String sqlFeatures = """
            SELECT json_build_object(
                'type', 'Feature',
                'id', fc.id,
                'geometry', ST_AsGeoJSON(ST_Transform(fc.geom, 4326), 6)::json,
                'properties', json_build_object(
                    'feicaoId', fc.id,
                    'codigoImovel', fc.codigo_imovel,
                    'areaHectares', ROUND((ST_Area(fc.geom::geography) / 10000)::numeric, 4),
                    'hectaresSobrepostosTotal', COALESCE(rc.total, 0),
                    'sobreposicoes', COALESCE(rc.itens, '[]'::json),
                    'atributos', fc.atributos
                )
            )::text AS feature_json
            FROM feicao_carga fc
            LEFT JOIN (
                SELECT r.codigo_imovel,
                       SUM(r.hectares_sobrepostos) AS total,
                       json_agg(json_build_object(
                           'fonteCamada', r.fonte_camada,
                           'codigoAlerta', r.codigo_alerta_app,
                           'hectaresSobrepostos', r.hectares_sobrepostos
                       )) AS itens
                FROM resultados_calculos r
                WHERE r.arquivo_processado_id = ?
                GROUP BY r.codigo_imovel
            ) rc ON rc.codigo_imovel = fc.codigo_imovel
            WHERE fc.arquivo_processado_id = ?
            ORDER BY fc.id
            """;

        try (BufferedOutputStream bos = new BufferedOutputStream(Files.newOutputStream(caminhoTemp), 65536);
             DigestOutputStream dos = new DigestOutputStream(bos, digest);
             JsonGenerator jg = objectMapper.getFactory().createGenerator(dos, JsonEncoding.UTF8)) {

            jg.setCodec(objectMapper);
            jg.writeStartObject();
            jg.writeStringField("type", "FeatureCollection");
            jg.writeArrayFieldStart("features");

            jdbcTemplate.query(
                    con -> {
                        PreparedStatement ps = con.prepareStatement(sqlFeatures);
                        ps.setLong(1, copiaId);
                        ps.setLong(2, copiaId);
                        ps.setFetchSize(1000);
                        return ps;
                    },
                    rs -> {
                        String featureJson = rs.getString(1);
                        if (featureJson != null) {
                            try {
                                jg.writeRawValue(featureJson);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        }
                    });

            jg.writeEndArray();
            // "metadados" e "linhagem" entram como foreign members, que a RFC 7946 permite
            // (o leitor de GeoJSON que não conhece ignora e o mapa continua funcionando)
            jg.writeObjectField("metadados", metadados);
            jg.writeObjectField("linhagem", linhagem);
            jg.writeEndObject();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private Map<String, Object> calcularTotais(Long copiaId) {
        Map<String, Object> linha = jdbcTemplate.queryForMap("""
            SELECT COUNT(*) AS qtd,
                   COALESCE(ROUND((SUM(ST_Area(fc.geom::geography)) / 10000)::numeric, 4), 0) AS area,
                   (SELECT COALESCE(SUM(r.hectares_sobrepostos), 0)
                      FROM resultados_calculos r
                     WHERE r.arquivo_processado_id = ?) AS sobreposto
            FROM feicao_carga fc
            WHERE fc.arquivo_processado_id = ?
            """, copiaId, copiaId);

        Map<String, Object> totais = new LinkedHashMap<>();
        totais.put("quantidadeFeicoes", ((Number) linha.get("qtd")).longValue());
        totais.put("areaTotalHectares", (BigDecimal) linha.get("area"));
        totais.put("hectaresSobrepostosTotal", (BigDecimal) linha.get("sobreposto"));
        return totais;
    }

    private static void apagarSeExistir(Path caminho) {
        try {
            Files.deleteIfExists(caminho);
        } catch (IOException ignored) {
        }
    }
}
