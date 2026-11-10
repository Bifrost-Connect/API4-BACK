package com.bifrostconnect.api_geo.security;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// US13 - Tarefa 14: aplica o @RequirePermission em todas as rotas.
//
// Roda no preHandle, antes do Spring converter e validar o corpo. Com @PreAuthorize a validação
// (@Valid) vinha antes, e um perfil proibido recebia 400 em vez de 403 (e ainda ficava sabendo
// o formato esperado do corpo).
//
// Negação por padrão: rota dos nossos controllers sem @RequirePermission e sem @RotaPublica é
// barrada, então rota nova esquecida sem permissão não fica aberta
@Component
public class RbacInterceptor implements HandlerInterceptor {

    private static final String PACOTE_DO_SISTEMA = "com.bifrostconnect.api_geo";

    private final RbacService rbacService;

    public RbacInterceptor(RbacService rbacService) {
        this.rbacService = rbacService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod metodo)
                || !metodo.getBeanType().getPackageName().startsWith(PACOTE_DO_SISTEMA)) {
            // recurso estático, /error do Spring etc.
            return true;
        }

        if (anotacao(metodo, RotaPublica.class) != null) {
            return true;
        }

        RequirePermission exigida = anotacao(metodo, RequirePermission.class);
        if (exigida == null) {
            throw new AccessDeniedException("Rota sem permissão declarada: " + metodo.getMethod().getName());
        }

        // AccessDeniedException sobe até o ExceptionTranslationFilter do Spring Security:
        // 401 se não tem ninguém logado, 403 se o perfil não tem a permissão
        if (!rbacService.requirePermission(exigida.value())) {
            throw new AccessDeniedException("Perfil sem a permissão " + exigida.value().name() + ".");
        }
        return true;
    }

    // a do método vale mais que a da classe
    private <A extends java.lang.annotation.Annotation> A anotacao(HandlerMethod metodo, Class<A> tipo) {
        A doMetodo = AnnotatedElementUtils.findMergedAnnotation(metodo.getMethod(), tipo);
        return doMetodo != null ? doMetodo : AnnotatedElementUtils.findMergedAnnotation(metodo.getBeanType(), tipo);
    }
}
