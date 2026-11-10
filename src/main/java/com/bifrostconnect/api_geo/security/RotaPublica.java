package com.bifrostconnect.api_geo.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// US13: rota que não exige permissão nenhuma (só o login). Sem esta anotação e sem
// @RequirePermission o RbacInterceptor nega o acesso
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RotaPublica {
}
