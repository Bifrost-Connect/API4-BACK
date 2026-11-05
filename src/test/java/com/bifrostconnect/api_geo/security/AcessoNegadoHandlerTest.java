package com.bifrostconnect.api_geo.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

// US13 - Tarefa 18: a resposta continua 403 mesmo se a auditoria cair
class AcessoNegadoHandlerTest {

    @AfterEach
    void limparContexto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Tarefa 18: falha ao gravar a auditoria não transforma o 403 em 500")
    void falhaNaAuditoriaMantem403() throws Exception {
        UsuarioSessaoService usuarios = mock(UsuarioSessaoService.class);
        when(usuarios.buscarPorLogin(any())).thenThrow(new DataAccessResourceFailureException("banco fora do ar"));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("analista", "x"));

        MockHttpServletResponse resposta = new MockHttpServletResponse();

        new AcessoNegadoHandler(usuarios, mock(JdbcTemplate.class)).handle(
                new MockHttpServletRequest("POST", "/carga/metadados"),
                resposta,
                new AccessDeniedException("Perfil sem a permissão CARGA_ENVIAR."));

        assertEquals(403, resposta.getStatus());
    }
}
