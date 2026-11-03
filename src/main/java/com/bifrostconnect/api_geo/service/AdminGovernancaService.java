
package com.bifrostconnect.api_geo.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminGovernancaService {

    private final JdbcTemplate jdbc;

    public AdminGovernancaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A matriz é definida no código para não depender de uma tabela
     * adicional no banco de dados.
     */
    public List<Map<String, Object>> matriz() {
        List<Map<String, Object>> resultado = new ArrayList<>();

        List<Map<String, Object>> perfis =
                jdbc.queryForList(
                        "SELECT id, nome, descricao FROM perfil ORDER BY id");

        Map<String, List<String[]>> regras = new LinkedHashMap<>();

        regras.put("OPERADOR", List.<String[]>of(
                new String[]{"CARGAS", "CRIAR",
                        "Enviar e operar cargas"},
                new String[]{"PUBLICACOES", "CONSULTAR",
                        "Consultar publicações"},
                new String[]{"MAPA_E_CATALOGO", "CONSULTAR",
                        "Consultar mapa e catálogo"}
        ));

        regras.put("AUDITOR", List.<String[]>of(
                new String[]{"CARGAS", "AUDITAR",
                        "Tratar problemas de validação"},
                new String[]{"PUBLICACOES", "CONSULTAR",
                        "Consultar publicações"},
                new String[]{"MAPA_E_CATALOGO", "CONSULTAR",
                        "Consultar mapa e catálogo"}
        ));

        regras.put("ANALISTA", List.<String[]>of(
                new String[]{"MAPA_E_CATALOGO", "CONSULTAR",
                        "Consultar mapa e catálogo"}
        ));

        regras.put("ADMINISTRADOR", List.<String[]>of(
                new String[]{"ADMINISTRACAO", "GERENCIAR",
                        "Gerenciar usuários, perfis e governança"},
                new String[]{"CARGAS", "GERENCIAR",
                        "Gerenciar cargas"},
                new String[]{"PUBLICACOES", "GERENCIAR",
                        "Gerenciar publicações"},
                new String[]{"MAPA_E_CATALOGO", "GERENCIAR",
                        "Gerenciar mapa e catálogo"}
        ));

        for (Map<String, Object> perfil : perfis) {
            String nome = String.valueOf(perfil.get("nome"));

            for (String[] regra :
                    regras.getOrDefault(nome, List.of())) {

                Map<String, Object> linha = new LinkedHashMap<>();

                linha.put("perfil_id", perfil.get("id"));
                linha.put("perfil", nome);
                linha.put("descricao", perfil.get("descricao"));
                linha.put("recurso", regra[0]);
                linha.put("permissao", regra[1]);
                linha.put("descricao_permissao", regra[2]);

                resultado.add(linha);
            }
        }

        return resultado;
    }

    /**
     * Consulta os registros administrativos na tabela auditoria existente.
     */
    public Map<String, Object> logs(
            Long usuarioId,
            String acao,
            String de,
            String ate,
            int page,
            int size) {

        if (page < 0 || size < 1 || size > 200) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Paginação inválida. Use page >= 0 e size entre 1 e 200.");
        }

        StringBuilder where = new StringBuilder(
                " WHERE acao IN (" +
                "'USUARIO_CRIADO', " +
                "'USUARIO_ATIVADO', " +
                "'USUARIO_INATIVADO', " +
                "'PERMISSAO_ALTERADA', " +
                "'PERMISSAO_ALTERADA_COM_EXCECAO_SOD', " +
                "'SENHA_REDEFINIDA') ");

        List<Object> args = new ArrayList<>();

        if (usuarioId != null) {
            where.append(" AND usuario_id=?");
            args.add(usuarioId);
        }

        if (acao != null && !acao.isBlank()) {
            where.append(" AND acao=?");
            args.add(acao.trim());
        }

        if (de != null && !de.isBlank()) {
            where.append(" AND data_hora >= CAST(? AS timestamp)");
            args.add(de.trim());
        }

        if (ate != null && !ate.isBlank()) {
            where.append(" AND data_hora <= CAST(? AS timestamp)");
            args.add(ate.trim());
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM auditoria" + where,
                Long.class,
                args.toArray());

        args.add(size);
        args.add((long) page * size);

        List<Map<String, Object>> content = jdbc.queryForList(
                "SELECT id, usuario_id, processo_id, acao, descricao, " +
                "detalhes, ip, data_hora FROM auditoria" +
                where +
                " ORDER BY data_hora DESC LIMIT ? OFFSET ?",
                args.toArray());

        Map<String, Object> resultado = new LinkedHashMap<>();

        resultado.put("content", content);
        resultado.put("page", page);
        resultado.put("size", size);
        resultado.put("totalElements", total);
        resultado.put(
                "totalPages",
                (int) Math.ceil(total / (double) size));

        return resultado;
    }
}
