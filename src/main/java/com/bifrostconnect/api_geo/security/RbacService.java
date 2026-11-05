package com.bifrostconnect.api_geo.security;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.bifrostconnect.api_geo.security.UsuarioSessaoService.EstadoUsuario;

import jakarta.servlet.http.HttpServletRequest;

// US13 - Tarefas 14 e 15: o "requirePermission" do sistema. Toda rota declara a permissão que
// exige com @RequirePermission(Permissao.X) e o RbacInterceptor chama este método; negar vira 403.
//
// Confere sempre o estado atual do usuário no banco (ativo + perfil). Usuário que não está no
// banco (os de memória do SecurityConfig e os @WithMockUser dos testes) cai no perfil do token.
@Component
public class RbacService {

    private final UsuarioSessaoService usuarioSessaoService;
    private final JdbcTemplate jdbc;

    public RbacService(UsuarioSessaoService usuarioSessaoService, JdbcTemplate jdbc) {
        this.usuarioSessaoService = usuarioSessaoService;
        this.jdbc = jdbc;
    }

    public boolean requirePermission(Permissao permissao) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return false;
        }

        Optional<EstadoUsuario> estado = usuarioSessaoService.buscarPorLogin(auth.getName());

        if (estado.isPresent()) {
            EstadoUsuario usuario = estado.get();
            if (!usuario.ativo()) {
                return false;
            }
            if (MatrizPermissoes.perfilTem(usuario.perfil(), permissao)) {
                return true;
            }
            return liberarPorExcecaoSod(usuario, permissao);
        }

        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .anyMatch(perfil -> MatrizPermissoes.perfilTem(perfil, permissao));
    }

    // Tarefa 15: Operador com excecao_sod_autorizada = TRUE pode aprovar/publicar/editar geometria,
    // mas cada uso grava um ALERTA_SOD na auditoria (US07). Se o log não gravar, a requisição falha junto
    private boolean liberarPorExcecaoSod(EstadoUsuario usuario, Permissao permissao) {
        if (!usuario.excecaoSod()
                || !MatrizPermissoes.PERFIL_COM_EXCECAO_SOD.equalsIgnoreCase(usuario.perfil())
                || !MatrizPermissoes.LIBERADAS_POR_EXCECAO_SOD.contains(permissao)) {
            return false;
        }

        HttpServletRequest request = requisicaoAtual();
        String rota = request == null ? "?" : request.getMethod() + " " + request.getRequestURI();

        jdbc.update("""
                INSERT INTO auditoria (usuario_id, processo_id, acao, descricao, detalhes, data_hora, ip)
                VALUES (?, NULL, 'ALERTA_SOD', ?, ?, now(), ?)
                """,
                usuario.id(),
                "Operador usou exceção de Segregação de Funções para " + permissao.name(),
                "rota=" + rota + "; usuario=" + usuario.email(),
                request == null ? null : request.getRemoteAddr());
        return true;
    }

    private HttpServletRequest requisicaoAtual() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes atributos) {
            return atributos.getRequest();
        }
        return null;
    }
}
