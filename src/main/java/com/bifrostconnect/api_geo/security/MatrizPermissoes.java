package com.bifrostconnect.api_geo.security;

import static com.bifrostconnect.api_geo.security.Permissao.*;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class MatrizPermissoes {

    private static final Set<Permissao> CONSUMO = EnumSet.of(
            CATALOGO_CONSULTAR, MAPA_CONSULTAR, LINHAGEM_CONSULTAR, DOMINIO_CONSULTAR);

    private static final Map<String, Set<Permissao>> MATRIZ = new LinkedHashMap<>();

    static {
        MATRIZ.put("ADMINISTRADOR", uniao(CONSUMO, EnumSet.of(
                USUARIO_GERENCIAR, GOVERNANCA_CONSULTAR, CARGA_CONSULTAR, PUBLICACAO_CONSULTAR)));

        MATRIZ.put("OPERADOR", uniao(CONSUMO, EnumSet.of(
                CARGA_ENVIAR, CARGA_CANCELAR, CARGA_CONSULTAR, ARQUIVO_BAIXAR,
                QUARENTENA_CONSULTAR, QUARENTENA_EXPORTAR, NOTIFICACAO_ENVIAR,
                ANALISE_EXECUTAR, COMPARAR_SAFRAS, PUBLICACAO_CONSULTAR)));

        MATRIZ.put("AUDITOR", uniao(CONSUMO, EnumSet.of(
                CARGA_CONSULTAR, QUARENTENA_CONSULTAR, PUBLICACAO_CONSULTAR)));

        MATRIZ.put("GESTOR", uniao(CONSUMO, EnumSet.of(
                CARGA_CONSULTAR, CARGA_CANCELAR, CARGA_ATRIBUIR, CARGA_APROVAR, VERSAO_PUBLICAR,
                ARQUIVO_BAIXAR, QUARENTENA_CONSULTAR, QUARENTENA_EXPORTAR, NOTIFICACAO_ENVIAR,
                GEOMETRIA_EDITAR, ANALISE_EXECUTAR, COMPARAR_SAFRAS, PUBLICACAO_CONSULTAR, RELATORIO_EXPORTAR)));

        MATRIZ.put("ANALISTA", uniao(CONSUMO, EnumSet.of(ANALISE_EXECUTAR, COMPARAR_SAFRAS, RELATORIO_EXPORTAR)));

        MATRIZ.put("INTEGRADOR_EXTERNO", EnumSet.of(CATALOGO_CONSULTAR, MAPA_CONSULTAR, RELATORIO_EXPORTAR));
    }

    public static final Set<Permissao> LIBERADAS_POR_EXCECAO_SOD = EnumSet.of(
            GEOMETRIA_EDITAR, CARGA_APROVAR, VERSAO_PUBLICAR);

    public static final String PERFIL_COM_EXCECAO_SOD = "OPERADOR";

    private MatrizPermissoes() {
    }

    public static Set<Permissao> permissoesDo(String perfil) {
        if (perfil == null) {
            return Set.of();
        }
        return MATRIZ.getOrDefault(perfil.toUpperCase(), Set.of());
    }

    public static boolean perfilTem(String perfil, Permissao permissao) {
        return permissoesDo(perfil).contains(permissao);
    }

    public static Map<String, Set<Permissao>> matriz() {
        return MATRIZ;
    }

    private static Set<Permissao> uniao(Set<Permissao> a, Set<Permissao> b) {
        Set<Permissao> resultado = EnumSet.copyOf(a);
        resultado.addAll(b);
        return resultado;
    }
}