
package com.bifrostconnect.api_geo.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminUsuarioService {

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder =
            new BCryptPasswordEncoder();

    public AdminUsuarioService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> criar(
            Map<String, Object> body,
            String administrador,
            String ip) {

        String nome = textoObrigatorio(body, "nome");
        String email = textoObrigatorio(body, "email").toLowerCase();
        String senha = textoObrigatorio(body, "senha");
        Long perfilId = numeroObrigatorio(body, "perfilId");

        validarSenha(senha);
        exigirPerfil(perfilId);

        try {
            Long id = jdbc.queryForObject(
                    "INSERT INTO usuario " +
                    "(nome, email, senha_hash, perfil_id, ativo, data_criacao) " +
                    "VALUES (?, ?, ?, ?, TRUE, now()) RETURNING id",
                    Long.class,
                    nome,
                    email,
                    encoder.encode(senha),
                    perfilId);

            registrarAuditoria(
                    administrador,
                    id,
                    "USUARIO_CRIADO",
                    "Usuário criado: " + email,
                    ip);

            return buscar(id);

        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Já existe usuário com esse e-mail.");
        }
    }

    public Map<String, Object> listar(
            String nome,
            String email,
            Long perfilId,
            Boolean ativo,
            int page,
            int size) {

        validarPaginacao(page, size, 100);

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();

        if (nome != null && !nome.isBlank()) {
            where.append(" AND lower(u.nome) LIKE lower(?)");
            args.add("%" + nome.trim() + "%");
        }

        if (email != null && !email.isBlank()) {
            where.append(" AND lower(u.email) LIKE lower(?)");
            args.add("%" + email.trim() + "%");
        }

        if (perfilId != null) {
            where.append(" AND u.perfil_id=?");
            args.add(perfilId);
        }

        if (ativo != null) {
            where.append(" AND u.ativo=?");
            args.add(ativo);
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM usuario u" + where,
                Long.class,
                args.toArray());

        args.add(size);
        args.add((long) page * size);

        List<Map<String, Object>> content = jdbc.queryForList(
                "SELECT u.id, u.nome, u.email, u.perfil_id, " +
                "p.nome AS perfil, u.ativo, u.data_criacao, " +
                "u.data_atualizacao " +
                "FROM usuario u JOIN perfil p ON p.id=u.perfil_id " +
                where +
                " ORDER BY u.id LIMIT ? OFFSET ?",
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

    @Transactional
    public Map<String, Object> alterarPermissao(
            Long id,
            Map<String, Object> body,
            String administrador,
            String ip) {

        exigirUsuario(id);

        Long perfilId = numeroObrigatorio(body, "perfilId");
        exigirPerfil(perfilId);

        boolean excecaoSoD =
                Boolean.TRUE.equals(body.get("excecaoSoD"));

        String justificativa = body.get("justificativa") == null
                ? null
                : body.get("justificativa").toString().trim();

        if (excecaoSoD &&
                (justificativa == null || justificativa.isBlank())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A justificativa é obrigatória para uma exceção SoD.");
        }

        String perfilAnterior = jdbc.queryForObject(
                "SELECT p.nome FROM usuario u " +
                "JOIN perfil p ON p.id=u.perfil_id WHERE u.id=?",
                String.class,
                id);

        String perfilNovo = nomePerfil(perfilId);

        jdbc.update(
                "UPDATE usuario SET perfil_id=?, data_atualizacao=now() " +
                "WHERE id=?",
                perfilId,
                id);

        String detalhes = "Perfil alterado de " + perfilAnterior
                + " para " + perfilNovo + ".";

        if (excecaoSoD) {
            detalhes += " EXCEÇÃO SoD declarada pelo administrador. "
                    + "Justificativa: " + justificativa;
        }

        registrarAuditoria(
                administrador,
                id,
                excecaoSoD
                        ? "PERMISSAO_ALTERADA_COM_EXCECAO_SOD"
                        : "PERMISSAO_ALTERADA",
                detalhes,
                ip);

        return buscar(id);
    }

    @Transactional
    public Map<String, Object> alterarStatus(
            Long id,
            boolean ativo,
            String administrador,
            String ip) {

        exigirUsuario(id);

        jdbc.update(
                "UPDATE usuario SET ativo=?, data_atualizacao=now() " +
                "WHERE id=?",
                ativo,
                id);

        registrarAuditoria(
                administrador,
                id,
                ativo ? "USUARIO_ATIVADO" : "USUARIO_INATIVADO",
                "Status alterado para " + (ativo ? "ativo" : "inativo"),
                ip);

        return buscar(id);
    }

    @Transactional
    public void redefinirSenha(
            Long id,
            String senha,
            String administrador,
            String ip) {

        exigirUsuario(id);
        validarSenha(senha);

        jdbc.update(
                "UPDATE usuario SET senha_hash=?, data_atualizacao=now() " +
                "WHERE id=?",
                encoder.encode(senha),
                id);

        registrarAuditoria(
                administrador,
                id,
                "SENHA_REDEFINIDA",
                "Senha redefinida por administrador.",
                ip);
    }

    public Map<String, Object> buscar(Long id) {
        List<Map<String, Object>> linhas = jdbc.queryForList(
                "SELECT u.id, u.nome, u.email, u.perfil_id, " +
                "p.nome AS perfil, u.ativo, u.data_criacao, " +
                "u.data_atualizacao " +
                "FROM usuario u JOIN perfil p ON p.id=u.perfil_id " +
                "WHERE u.id=?",
                id);

        if (linhas.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Usuário não encontrado.");
        }

        // A senha nunca é incluída na resposta.
        return linhas.get(0);
    }

    private void exigirUsuario(Long id) {
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM usuario WHERE id=?",
                Integer.class,
                id);

        if (total == null || total == 0) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Usuário não encontrado.");
        }
    }

    private void exigirPerfil(Long id) {
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM perfil WHERE id=?",
                Integer.class,
                id);

        if (total == null || total == 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Perfil informado não existe.");
        }
    }

    private String nomePerfil(Long id) {
        return jdbc.queryForObject(
                "SELECT nome FROM perfil WHERE id=?",
                String.class,
                id);
    }

    private void registrarAuditoria(
            String administrador,
            Long alvoId,
            String acao,
            String detalhes,
            String ip) {

        Long administradorId = resolverIdAdministrador(administrador);

        jdbc.update(
                "INSERT INTO auditoria " +
                "(usuario_id, processo_id, acao, descricao, detalhes, data_hora, ip) " +
                "VALUES (?, NULL, ?, ?, ?, now(), ?)",
                administradorId,
                acao,
                "Administração de usuários: " + acao,
                "usuarioAlvoId=" + alvoId + "; " + detalhes,
                ip);
    }

    private Long resolverIdAdministrador(String login) {
        String email = login == null ? "" : login.trim().toLowerCase();

        if (!email.contains("@")) {
            email += "@bifrost.com";
        }

        List<Long> ids = jdbc.query(
                "SELECT u.id FROM usuario u " +
                "JOIN perfil p ON p.id=u.perfil_id " +
                "WHERE lower(u.email)=lower(?) " +
                "AND p.nome='ADMINISTRADOR' AND u.ativo=TRUE " +
                "LIMIT 1",
                (rs, row) -> rs.getLong(1),
                email);

        if (!ids.isEmpty()) {
            return ids.get(0);
        }

        List<Long> administradores = jdbc.query(
                "SELECT u.id FROM usuario u " +
                "JOIN perfil p ON p.id=u.perfil_id " +
                "WHERE p.nome='ADMINISTRADOR' AND u.ativo=TRUE " +
                "ORDER BY u.id LIMIT 1",
                (rs, row) -> rs.getLong(1));

        if (!administradores.isEmpty()) {
            return administradores.get(0);
        }

        throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Não existe usuário ativo com perfil ADMINISTRADOR " +
                "para registrar a auditoria.");
    }

    private void validarSenha(String senha) {
        if (senha == null || senha.length() < 8) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A senha deve possuir no mínimo 8 caracteres.");
        }
    }

    private String textoObrigatorio(
            Map<String, Object> body,
            String campo) {

        Object valor = body.get(campo);

        if (valor == null || valor.toString().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Campo obrigatório: " + campo);
        }

        return valor.toString().trim();
    }

    private Long numeroObrigatorio(
            Map<String, Object> body,
            String campo) {

        try {
            return Long.valueOf(body.get(campo).toString());
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Campo numérico obrigatório: " + campo);
        }
    }

    private void validarPaginacao(int page, int size, int max) {
        if (page < 0 || size < 1 || size > max) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Paginação inválida.");
        }
    }
}
