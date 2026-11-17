package com.bifrostconnect.api_geo.service;

import com.bifrostconnect.api_geo.exception.BaseIndisponivelException;
import com.bifrostconnect.api_geo.exception.RecursoNaoEncontradoException;
import com.bifrostconnect.api_geo.exception.RequisicaoInvalidaException;
import com.bifrostconnect.api_geo.security.UsuarioSessaoService;
import com.bifrostconnect.api_geo.security.UsuarioSessaoService.EstadoUsuario;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// US11 - Tarefas 12 a 14: exportação oficial dos resultados de uma versão publicada.
// Tudo sai da vw_relatorio_exportacao_oficial (V37): uma linha por imóvel da cópia vigente,
// com metadados, hash do arquivo original e as sobreposições agregadas.
//
// A leitura é em lotes por cursor (WHERE feicao_id > último ORDER BY feicao_id LIMIT lote, que usa
// os índices da V38) e cada lote vai direto pro stream da resposta. Nunca tem mais que um lote
// na memória, então dá pra exportar base grande sem estourar a RAM do servidor.
@Service
public class ExportacaoService {

    // Tarefa 14: código gravado em auditoria.acao pra cada exportação gerada
    public static final String ACAO_EXPORTACAO = "EXPORTACAO_GERADA";

    public static final int TAMANHO_LOTE = 1000;
    public static final int TAMANHO_AMOSTRA = 10;
    public static final int TAMANHO_PADRAO_HISTORICO = 20;
    public static final int TAMANHO_MAXIMO_HISTORICO = 100;

    private static final String FROM_RELATORIO = " FROM vw_relatorio_exportacao_oficial v ";

    // colunas que vão pro CSV e pras properties do GeoJSON (a geometria vai à parte)
    private static final List<String> COLUNAS = List.of(
            "processo_id", "feicao_id", "codigo_imovel", "conjunto", "orgao", "orgao_sigla",
            "ano_safra", "versao_oficial", "data_publicacao", "cobertura_espacial",
            "hash_arquivo_original", "hectares_sobrepostos_total", "qtd_sobreposicoes");

    private static final String SELECT_COLUNAS = "SELECT " + String.join(", ", COLUNAS.stream()
            .map(c -> "v." + c).toList()) + ", v.sobreposicoes::text AS sobreposicoes";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final UsuarioSessaoService usuarioSessaoService;
    private final CsvExportService csvExportService;

    public ExportacaoService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            UsuarioSessaoService usuarioSessaoService,
            CsvExportService csvExportService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.usuarioSessaoService = usuarioSessaoService;
        this.csvExportService = csvExportService;
    }

    public enum Formato {
        CSV("text/csv; charset=UTF-8", "csv"),
        GEOJSON("application/geo+json", "geojson");

        private final String contentType;
        private final String extensao;

        Formato(String contentType, String extensao) {
            this.contentType = contentType;
            this.extensao = extensao;
        }

        public String getContentType() {
            return contentType;
        }

        public String getExtensao() {
            return extensao;
        }

        // os mesmos dois valores que a V36 aceita em log_exportacoes_relatorios.formato
        public static Formato de(String valor) {
            if (valor == null || valor.isBlank() || valor.trim().equalsIgnoreCase("geojson")
                    || valor.trim().equalsIgnoreCase("json")) {
                return GEOJSON;
            }
            if (valor.trim().equalsIgnoreCase("csv")) {
                return CSV;
            }
            throw new RequisicaoInvalidaException("Formato inválido: " + valor + ". Use csv ou geojson.");
        }
    }

    // filtros da exportação parametrizada. Só processoId é obrigatório
    public record FiltroExportacao(
            Long processoId,
            String codigoImovel,
            Boolean comSobreposicao,
            BigDecimal hectaresMin,
            String fonteCamada) {

        Map<String, Object> comoMapa() {
            Map<String, Object> mapa = new LinkedHashMap<>();
            mapa.put("processo_id", processoId);
            if (codigoImovel != null) mapa.put("codigo_imovel", codigoImovel);
            if (comSobreposicao != null) mapa.put("com_sobreposicao", comSobreposicao);
            if (hectaresMin != null) mapa.put("hectares_min", hectaresMin);
            if (fonteCamada != null) mapa.put("fonte_camada", fonteCamada);
            return mapa;
        }
    }

    // quem pediu: o login autenticado e, se ele não tiver linha em usuario, o id que veio na requisição
    public record Solicitante(String login, Long usuarioIdInformado, String ip) {
    }

    // o que o controller precisa pra montar a resposta antes de começar a escrever o arquivo
    public record ExportacaoPreparada(
            Long processoId,
            String versaoOficial,
            Formato formato,
            long totalRegistros,
            Long usuarioId,
            String nomeArquivo) {
    }

    // ------------------------------------------------------------------
    // Tarefa 12: exportação via streaming
    // ------------------------------------------------------------------

    // Valida tudo, conta as linhas e grava a auditoria ANTES de mandar o primeiro byte. Se der erro
    // aqui a resposta ainda é um { "erro": ... } normal, e se a auditoria falhar o arquivo não sai
    // (mesma regra da Tarefa 14). Como a versão fechada é imutável (V22), a contagem bate com o que
    // vai ser escrito depois
    @Transactional
    public ExportacaoPreparada preparar(FiltroExportacao filtro, String formatoSolicitado, Solicitante solicitante) {
        Formato formato = Formato.de(formatoSolicitado);
        validarFiltro(filtro);
        String versao = validarVersaoPublicada(filtro.processoId());
        Long usuarioId = identificarSolicitante(solicitante);

        long total = contar(filtro);
        registrarExportacao(filtro, formato, versao, total, usuarioId, solicitante.ip());

        String nomeArquivo = "exportacao_processo_" + filtro.processoId() + "_"
                + versao.replaceAll("[^A-Za-z0-9._-]", "_") + "." + formato.getExtensao();

        return new ExportacaoPreparada(filtro.processoId(), versao, formato, total, usuarioId, nomeArquivo);
    }

    // Escreve o arquivo no stream, lote por lote. Devolve quantas linhas saíram
    public long escrever(ExportacaoPreparada preparada, FiltroExportacao filtro, OutputStream saida) throws IOException {
        return preparada.formato() == Formato.CSV
                ? escreverCsv(filtro, saida)
                : escreverGeoJson(preparada, filtro, saida);
    }

    private long escreverCsv(FiltroExportacao filtro, OutputStream saida) throws IOException {
        // Tarefa 10: BOM pro Excel abrir o UTF-8 direito, separador ';'
        csvExportService.escreverBom(saida);
        Writer writer = new OutputStreamWriter(saida, StandardCharsets.UTF_8);

        List<String> cabecalho = new ArrayList<>(COLUNAS);
        cabecalho.add("sobreposicoes");
        cabecalho.add("geometria_geojson");
        writer.write(String.join(";", cabecalho));
        writer.write("\r\n");

        long total = percorrerEmLotes(filtro, rs -> {
            StringBuilder linha = new StringBuilder();
            for (String coluna : COLUNAS) {
                linha.append(csvExportService.escapar(texto(rs, coluna))).append(';');
            }
            linha.append(csvExportService.escapar(rs.getString("sobreposicoes"))).append(';');
            linha.append(csvExportService.escapar(rs.getString("geometria_geojson")));
            linha.append("\r\n");
            writer.write(linha.toString());
        }, writer::flush);

        writer.flush();
        return total;
    }

    private long escreverGeoJson(ExportacaoPreparada preparada, FiltroExportacao filtro, OutputStream saida)
            throws IOException {
        // Tarefa 11: FeatureCollection. O gerador do Jackson escreve direto no stream, sem montar árvore
        JsonGenerator json = objectMapper.getFactory().createGenerator(saida);
        json.writeStartObject();
        json.writeStringField("type", "FeatureCollection");

        json.writeObjectFieldStart("metadados");
        json.writeNumberField("processo_id", preparada.processoId());
        json.writeStringField("versao_oficial", preparada.versaoOficial());
        json.writeNumberField("total_registros", preparada.totalRegistros());
        json.writeObjectField("filtros", filtro.comoMapa());
        json.writeStringField("data_hora_exportacao", LocalDateTime.now().toString());
        json.writeEndObject();

        json.writeArrayFieldStart("features");

        long total = percorrerEmLotes(filtro, rs -> {
            json.writeStartObject();
            json.writeStringField("type", "Feature");

            json.writeObjectFieldStart("properties");
            for (String coluna : COLUNAS) {
                json.writeObjectField(coluna, valorJson(rs.getObject(coluna)));
            }
            json.writeFieldName("sobreposicoes");
            json.writeRawValue(rs.getString("sobreposicoes"));
            json.writeEndObject();

            // a geometria já vem em GeoJSON do PostGIS (ST_AsGeoJSON(geom, 6) na V37)
            json.writeFieldName("geometry");
            String geometria = rs.getString("geometria_geojson");
            if (geometria == null) {
                json.writeNull();
            } else {
                json.writeRawValue(geometria);
            }

            json.writeEndObject();
        }, json::flush);

        json.writeEndArray();
        json.writeEndObject();
        json.flush();
        return total;
    }

    @FunctionalInterface
    private interface EscritorLinha {
        void escrever(ResultSet rs) throws SQLException, IOException;
    }

    @FunctionalInterface
    private interface FimDeLote {
        void executar() throws IOException;
    }

    // paginação por cursor (keyset): cada lote continua de onde o anterior parou, sem OFFSET
    private long percorrerEmLotes(FiltroExportacao filtro, EscritorLinha escritor, FimDeLote fimDeLote)
            throws IOException {
        List<Object> params = new ArrayList<>();
        String where = montarWhere(filtro, params);
        String sql = SELECT_COLUNAS + ", v.geometria_geojson" + FROM_RELATORIO + where
                + " AND v.feicao_id > ? ORDER BY v.feicao_id LIMIT " + TAMANHO_LOTE;

        long total = 0;
        long ultimoId = 0;

        while (true) {
            List<Object> paramsLote = new ArrayList<>(params);
            paramsLote.add(ultimoId);

            long[] lote = {0, ultimoId};
            try {
                jdbcTemplate.query(sql, (RowCallbackHandler) rs -> {
                    try {
                        escritor.escrever(rs);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    lote[0]++;
                    lote[1] = rs.getLong("feicao_id");
                }, paramsLote.toArray());
            } catch (UncheckedIOException e) {
                // cliente fechou a conexão no meio do download, por exemplo
                throw e.getCause();
            }

            total += lote[0];
            ultimoId = lote[1];
            fimDeLote.executar();

            if (lote[0] < TAMANHO_LOTE) {
                return total;
            }
        }
    }

    // ------------------------------------------------------------------
    // Tarefa 13: preview e histórico
    // ------------------------------------------------------------------

    // quantos registros a exportação vai ter e as 10 primeiras linhas, com os mesmos filtros.
    // A amostra vem sem a geometria pra resposta ficar leve
    public Map<String, Object> preview(FiltroExportacao filtro) {
        validarFiltro(filtro);
        String versao = validarVersaoPublicada(filtro.processoId());

        List<Object> params = new ArrayList<>();
        String where = montarWhere(filtro, params);

        long total = contar(filtro);
        List<Map<String, Object>> amostra = jdbcTemplate.queryForList(
                SELECT_COLUNAS + FROM_RELATORIO + where + " ORDER BY v.feicao_id LIMIT " + TAMANHO_AMOSTRA,
                params.toArray());

        amostra.forEach(linha -> linha.put("sobreposicoes", lerJson((String) linha.get("sobreposicoes"))));

        Map<String, Object> resposta = new LinkedHashMap<>();
        resposta.put("processo_id", filtro.processoId());
        resposta.put("versao_oficial", versao);
        resposta.put("filtros", filtro.comoMapa());
        resposta.put("total_registros", total);
        resposta.put("tamanho_amostra", amostra.size());
        resposta.put("amostra", amostra);
        return resposta;
    }

    public record FiltroHistorico(Long usuarioId, Long processoId, String formato, LocalDate de, LocalDate ate) {
    }

    // downloads registrados no log_exportacoes_relatorios (V36), do mais recente pro mais antigo
    public Map<String, Object> historico(FiltroHistorico filtro, int page, int size) {
        if (page < 0 || size < 1 || size > TAMANHO_MAXIMO_HISTORICO) {
            throw new RequisicaoInvalidaException(
                    "Paginação inválida. Use page >= 0 e size entre 1 e " + TAMANHO_MAXIMO_HISTORICO + ".");
        }
        if (filtro.de() != null && filtro.ate() != null && filtro.de().isAfter(filtro.ate())) {
            throw new RequisicaoInvalidaException("Período inválido: 'de' não pode ser depois de 'ate'.");
        }

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();

        if (filtro.usuarioId() != null) {
            where.append(" AND l.usuario_id = ?");
            params.add(filtro.usuarioId());
        }
        if (filtro.processoId() != null) {
            where.append(" AND l.processo_id = ?");
            params.add(filtro.processoId());
        }
        if (filtro.formato() != null && !filtro.formato().isBlank()) {
            where.append(" AND l.formato = ?");
            params.add(Formato.de(filtro.formato()).name());
        }
        if (filtro.de() != null) {
            where.append(" AND l.data_hora_exportacao >= ?");
            params.add(filtro.de().atStartOfDay());
        }
        if (filtro.ate() != null) {
            where.append(" AND l.data_hora_exportacao < ?");
            params.add(filtro.ate().plusDays(1).atStartOfDay());
        }

        String from = """
                 FROM log_exportacoes_relatorios l
                 JOIN usuario u   ON u.id = l.usuario_id
                 JOIN processo p  ON p.id = l.processo_id
                 JOIN conjunto c  ON c.id = p.conjunto_id
                """;

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + where, Long.class, params.toArray());

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add(size);
        paramsPagina.add((long) page * size);

        List<Map<String, Object>> itens = jdbcTemplate.queryForList("""
                SELECT l.id,
                       l.usuario_id,
                       u.nome  AS usuario_nome,
                       u.email AS usuario_email,
                       l.processo_id,
                       c.nome  AS conjunto,
                       p.ano_safra,
                       p.versao_oficial,
                       l.formato,
                       l.filtros_utilizados::text AS filtros_utilizados,
                       l.total_registros_exportados,
                       l.ip_solicitante,
                       l.data_hora_exportacao
                """ + from + where + " ORDER BY l.data_hora_exportacao DESC, l.id DESC LIMIT ? OFFSET ?",
                paramsPagina.toArray());

        itens.forEach(linha -> linha.put("filtros_utilizados", lerJson((String) linha.get("filtros_utilizados"))));

        long totalItens = total == null ? 0 : total;
        Map<String, Object> pagina = new LinkedHashMap<>();
        pagina.put("items", itens);
        pagina.put("page", page);
        pagina.put("size", size);
        pagina.put("total_items", totalItens);
        pagina.put("total_pages", (int) Math.ceil(totalItens / (double) size));
        return pagina;
    }

    // ------------------------------------------------------------------
    // apoio
    // ------------------------------------------------------------------

    private void validarFiltro(FiltroExportacao filtro) {
        if (filtro.processoId() == null) {
            throw new RequisicaoInvalidaException("Informe o processo_id da versão a exportar.");
        }
        if (filtro.hectaresMin() != null && filtro.hectaresMin().signum() < 0) {
            throw new RequisicaoInvalidaException("hectares_min não pode ser negativo.");
        }
    }

    // só versão publicada (fechada) sai na exportação oficial; devolve a versao_oficial
    private String validarVersaoPublicada(Long processoId) {
        List<Map<String, Object>> linhas = jdbcTemplate.queryForList("""
                SELECT p.versao_fechada, p.versao_oficial, e.nome AS etapa, s.nome AS situacao
                  FROM processo p
                  LEFT JOIN etapa e    ON e.id = p.etapa_atual_id
                  LEFT JOIN situacao s ON s.id = p.situacao_atual_id
                 WHERE p.id = ?
                """, processoId);

        if (linhas.isEmpty()) {
            throw new RecursoNaoEncontradoException("Processo " + processoId + " não encontrado.");
        }

        Map<String, Object> processo = linhas.get(0);
        if (!Boolean.TRUE.equals(processo.get("versao_fechada"))) {
            throw new BaseIndisponivelException(
                    "Só versão publicada (fechada) pode ser exportada. O processo " + processoId + " ainda não foi publicado.",
                    (String) processo.get("etapa"),
                    (String) processo.get("situacao"));
        }

        Object versao = processo.get("versao_oficial");
        return versao == null ? "SEM_VERSAO" : versao.toString();
    }

    // Tarefa 14: o usuário autenticado manda. O X-Gestor-ID / gestorId só vale pros usuários de memória
    // do SecurityConfig, que não têm linha em usuario (o log da V36 exige usuario_id)
    private Long identificarSolicitante(Solicitante solicitante) {
        EstadoUsuario usuario = usuarioSessaoService.buscarPorLogin(solicitante.login())
                .or(() -> solicitante.usuarioIdInformado() == null
                        ? java.util.Optional.empty()
                        : usuarioSessaoService.buscarPorId(solicitante.usuarioIdInformado()))
                .orElseThrow(() -> new RequisicaoInvalidaException(
                        "Não deu pra identificar quem está exportando. Toda exportação precisa ser auditada: "
                                + "faça login com um usuário cadastrado ou informe o X-Gestor-ID."));

        if (!usuario.ativo()) {
            throw new RequisicaoInvalidaException("O usuário " + usuario.id() + " está inativo e não pode exportar.");
        }
        return usuario.id();
    }

    private long contar(FiltroExportacao filtro) {
        List<Object> params = new ArrayList<>();
        String where = montarWhere(filtro, params);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + FROM_RELATORIO + where, Long.class, params.toArray());
        return total == null ? 0 : total;
    }

    private String montarWhere(FiltroExportacao filtro, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE v.processo_id = ?");
        params.add(filtro.processoId());

        if (filtro.codigoImovel() != null && !filtro.codigoImovel().isBlank()) {
            where.append(" AND v.codigo_imovel = ?");
            params.add(filtro.codigoImovel().trim());
        }
        if (filtro.comSobreposicao() != null) {
            where.append(filtro.comSobreposicao() ? " AND v.qtd_sobreposicoes > 0" : " AND v.qtd_sobreposicoes = 0");
        }
        if (filtro.hectaresMin() != null) {
            where.append(" AND v.hectares_sobrepostos_total >= ?");
            params.add(filtro.hectaresMin());
        }
        if (filtro.fonteCamada() != null && !filtro.fonteCamada().isBlank()) {
            // sobreposicoes é o jsonb agregado da V37: [{ "fonte_camada": ..., ... }, ...]
            where.append(" AND v.sobreposicoes @> jsonb_build_array(jsonb_build_object('fonte_camada', ?::text))");
            params.add(filtro.fonteCamada().trim());
        }
        return where.toString();
    }

    // grava nos dois lugares, na mesma transação: log_exportacoes_relatorios (V36, a base do histórico)
    // e auditoria (EXPORTACAO_GERADA da Tarefa 14, junto com o resto dos eventos do sistema)
    private void registrarExportacao(
            FiltroExportacao filtro, Formato formato, String versao, long total, Long usuarioId, String ip) {

        String ipCurto = ip != null && ip.length() > 45 ? ip.substring(0, 45) : ip;
        LocalDateTime agora = LocalDateTime.now();

        String filtrosJson = paraJson(filtro.comoMapa());
        jdbcTemplate.update("""
                INSERT INTO log_exportacoes_relatorios
                    (usuario_id, processo_id, formato, filtros_utilizados, total_registros_exportados,
                     ip_solicitante, data_hora_exportacao)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)
                """, usuarioId, filtro.processoId(), formato.name(), filtrosJson,
                total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total, ipCurto, agora);

        Map<String, Object> detalhes = new LinkedHashMap<>();
        detalhes.put("gestorId", usuarioId);
        detalhes.put("processoId", filtro.processoId());
        detalhes.put("versao", versao);
        detalhes.put("formato", formato.name());
        detalhes.put("filtros", filtro.comoMapa());
        detalhes.put("linhas", total);
        detalhes.put("dataHoraExportacao", agora.toString());

        jdbcTemplate.update("""
                INSERT INTO auditoria (usuario_id, processo_id, acao, descricao, detalhes, data_hora, ip)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, usuarioId, filtro.processoId(), ACAO_EXPORTACAO,
                "Exportação em " + formato.name() + " da versão " + versao + " do processo "
                        + filtro.processoId() + " (" + total + " linha(s)).",
                paraJson(detalhes), agora, ipCurto);
    }

    private String paraJson(Object valor) {
        try {
            return objectMapper.writeValueAsString(valor);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Não deu pra montar o JSON da auditoria da exportação.", e);
        }
    }

    private Object lerJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    private String texto(ResultSet rs, String coluna) throws SQLException {
        Object valor = rs.getObject(coluna);
        return valor == null ? null : valorJson(valor).toString();
    }

    // Timestamp vira ISO (2026-11-13T10:00) em vez do toString do java.sql
    private Object valorJson(Object valor) {
        if (valor instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toString();
        }
        if (valor instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        return valor;
    }
}
