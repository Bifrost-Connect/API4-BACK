
package com.bifrostconnect.api_geo.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.bifrostconnect.api_geo.security.MatrizPermissoes;
import com.bifrostconnect.api_geo.security.Permissao;

@Service
public class AdminGovernancaService {

    private final JdbcTemplate jdbc;

    public AdminGovernancaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // a matriz sai da MatrizPermissoes, a mesma que o RbacService usa pra liberar as rotas,
    // então a tela de governança mostra exatamente o que está valendo
    public List<Map<String, Object>> matriz() {
        List<Map<String, Object>> resultado = new ArrayList<>();

        List<Map<String, Object>> perfis =
                jdbc.queryForList(
                        "SELECT id, nome, descricao FROM perfil ORDER BY id");

        for (Map<String, Object> perfil : perfis) {
            String nome = String.valueOf(perfil.get("nome"));

            for (Permissao permissao : MatrizPermissoes.permissoesDo(nome)) {
                Map<String, Object> linha = new LinkedHashMap<>();

                linha.put("perfil_id", perfil.get("id"));
                linha.put("perfil", nome);
                linha.put("descricao", perfil.get("descricao"));
                linha.put("recurso", permissao.getModulo());
                linha.put("permissao", permissao.name());
                linha.put("descricao_permissao", permissao.getDescricao());
                linha.put("liberada_por_excecao_sod", false);

                resultado.add(linha);
            }

            if (MatrizPermissoes.PERFIL_COM_EXCECAO_SOD.equals(nome)) {
                for (Permissao permissao : MatrizPermissoes.LIBERADAS_POR_EXCECAO_SOD) {
                    Map<String, Object> linha = new LinkedHashMap<>();

                    linha.put("perfil_id", perfil.get("id"));
                    linha.put("perfil", nome);
                    linha.put("descricao", perfil.get("descricao"));
                    linha.put("recurso", permissao.getModulo());
                    linha.put("permissao", permissao.name());
                    linha.put("descricao_permissao", permissao.getDescricao());
                    linha.put("liberada_por_excecao_sod", true);

                    resultado.add(linha);
                }
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
                "'SENHA_REDEFINIDA', " +
                "'ALERTA_SOD', " +
                "'ACESSO_NEGADO_403') ");

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
