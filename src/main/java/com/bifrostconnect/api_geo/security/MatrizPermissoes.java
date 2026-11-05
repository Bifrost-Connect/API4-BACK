package com.bifrostconnect.api_geo.security;

import static com.bifrostconnect.api_geo.security.Permissao.*;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

// US13 - Tarefas 14 e 15: quem pode o quê. A Segregação de Funções (SoD) sai daqui:
//  - ADMINISTRADOR só administra e consulta: não sobe carga, não aprova, não publica, não mexe em dado territorial
//  - AUDITOR é somente leitura: nada de mutação nem download de arquivo/relatório
//  - OPERADOR não edita geometria, não aprova e não publica (a não ser com exceção SoD, ver abaixo)
//  - ANALISTA não vê quarentena, não exclui base e não publica
// Fica no código até a US13-BANCO criar as tabelas permissao / perfil_permissao.
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
                ANALISE_EXECUTAR, PUBLICACAO_CONSULTAR)));

        MATRIZ.put("AUDITOR", uniao(CONSUMO, EnumSet.of(
                CARGA_CONSULTAR, QUARENTENA_CONSULTAR, PUBLICACAO_CONSULTAR)));

        MATRIZ.put("GESTOR", uniao(CONSUMO, EnumSet.of(
                CARGA_CONSULTAR, CARGA_CANCELAR, CARGA_ATRIBUIR, CARGA_APROVAR, VERSAO_PUBLICAR,
                ARQUIVO_BAIXAR, QUARENTENA_CONSULTAR, QUARENTENA_EXPORTAR, NOTIFICACAO_ENVIAR,
                GEOMETRIA_EDITAR, ANALISE_EXECUTAR, PUBLICACAO_CONSULTAR, RELATORIO_EXPORTAR)));

        MATRIZ.put("ANALISTA", uniao(CONSUMO, EnumSet.of(ANALISE_EXECUTAR, RELATORIO_EXPORTAR)));

        MATRIZ.put("INTEGRADOR_EXTERNO", EnumSet.of(CATALOGO_CONSULTAR, MAPA_CONSULTAR, RELATORIO_EXPORTAR));
    }

    // o que a exceção SoD (usuario.excecao_sod_autorizada) libera pro Operador.
    // Toda vez que ela é usada vai um ALERTA_SOD pra auditoria
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
