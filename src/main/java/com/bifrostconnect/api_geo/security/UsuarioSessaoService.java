package com.bifrostconnect.api_geo.security;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

// US13: estado ATUAL do usuário direto do banco (ativo, perfil, versão de sessão, exceção SoD).
// O token só diz quem é; o que ele pode fazer agora sempre vem daqui
@Service
public class UsuarioSessaoService {

    public record EstadoUsuario(
            Long id,
            String nome,
            String email,
            String senhaHash,
            String perfil,
            boolean ativo,
            int versaoSessao,
            boolean excecaoSod) {
    }

    private static final String SELECT_ESTADO = """
            SELECT u.id, u.nome, u.email, u.senha_hash, p.nome AS perfil, u.ativo,
                   u.versao_sessao, u.excecao_sod_autorizada
              FROM usuario u
              JOIN perfil p ON p.id = u.perfil_id
            """;

    private final JdbcTemplate jdbc;

    public UsuarioSessaoService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EstadoUsuario> buscarPorId(Long id) {
        return primeiro(jdbc.query(SELECT_ESTADO + " WHERE u.id = ?", (rs, i) -> mapear(rs), id));
    }

    // aceita o e-mail ou só a parte antes do @ (o front ainda loga com "admin", "operador"...)
    public Optional<EstadoUsuario> buscarPorLogin(String login) {
        if (login == null || login.isBlank()) {
            return Optional.empty();
        }
        String identificador = login.trim();
        return primeiro(jdbc.query(SELECT_ESTADO + """
                 WHERE LOWER(u.email) = LOWER(?)
                    OR LOWER(SPLIT_PART(u.email, '@', 1)) = LOWER(?)
                 ORDER BY CASE WHEN LOWER(u.email) = LOWER(?) THEN 0 ELSE 1 END, u.id
                 LIMIT 1
                """, (rs, i) -> mapear(rs), identificador, identificador, identificador));
    }

    private EstadoUsuario mapear(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EstadoUsuario(
                rs.getLong("id"),
                rs.getString("nome"),
                rs.getString("email"),
                rs.getString("senha_hash"),
                rs.getString("perfil"),
                rs.getBoolean("ativo"),
                rs.getInt("versao_sessao"),
                rs.getBoolean("excecao_sod_autorizada"));
    }

    private Optional<EstadoUsuario> primeiro(List<EstadoUsuario> lista) {
        return lista.isEmpty() ? Optional.empty() : Optional.of(lista.get(0));
    }
}
