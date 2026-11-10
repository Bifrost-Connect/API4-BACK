package com.bifrostconnect.api_geo.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// US13 - Tarefa 14: o requirePermission(permissao_necessaria) das rotas.
// Vai no método (ou na classe, valendo pra todos os métodos dela) e é checado pelo
// RbacInterceptor antes de o Spring ler o corpo da requisição
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    Permissao value();
}
