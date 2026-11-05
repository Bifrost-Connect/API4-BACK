package com.bifrostconnect.api_geo.security;

// US13 - Tarefa 14: permissões granulares que as rotas exigem com @rbac.requirePermission('...').
// O código é o nome da constante, igual ao que a US13-BANCO vai gravar em permissao.codigo_permissao
public enum Permissao {

    // cargas (US01 a US05)
    CARGA_ENVIAR("CARGAS", "Subir carga, cadastrar metadados, processar e reprocessar"),
    CARGA_CANCELAR("CARGAS", "Cancelar / excluir uma carga"),
    CARGA_CONSULTAR("CARGAS", "Acompanhar cargas, logs e cálculos"),
    CARGA_ATRIBUIR("CARGAS", "Alocar auditor/editor responsável pela carga"),
    ARQUIVO_BAIXAR("CARGAS", "Baixar arquivo original da carga"),

    // quarentena (US04 / US06)
    QUARENTENA_CONSULTAR("QUARENTENA", "Ver dados e ocorrências da quarentena"),
    QUARENTENA_EXPORTAR("QUARENTENA", "Exportar relatório de rejeições"),
    NOTIFICACAO_ENVIAR("QUARENTENA", "Notificar operador/auditor sobre a carga"),

    // dados territoriais
    GEOMETRIA_EDITAR("TERRITORIAL", "Editar geometrias e importar camadas de referência"),
    ANALISE_EXECUTAR("TERRITORIAL", "Rodar cruzamentos territoriais"),

    // gates e publicação (US07)
    CARGA_APROVAR("PUBLICACAO", "Aprovar/reprovar etapas da carga"),
    VERSAO_PUBLICAR("PUBLICACAO", "Fechar e publicar versão oficial"),
    PUBLICACAO_CONSULTAR("PUBLICACAO", "Revisar pacote de publicação e histórico de decisões"),
    RELATORIO_EXPORTAR("PUBLICACAO", "Exportar versão publicada"),

    // consumo
    CATALOGO_CONSULTAR("CONSUMO", "Consultar catálogo de bases"),
    MAPA_CONSULTAR("CONSUMO", "Consultar mapa de dados publicados"),
    LINHAGEM_CONSULTAR("CONSUMO", "Consultar linhagem/rastreabilidade"),
    DOMINIO_CONSULTAR("CONSUMO", "Listas de domínio (órgãos, conjuntos, situações)"),

    // administração (US13)
    USUARIO_GERENCIAR("ADMINISTRACAO", "Cadastrar, inativar e trocar perfil de usuários"),
    GOVERNANCA_CONSULTAR("ADMINISTRACAO", "Matriz de permissões e logs de segurança");

    private final String modulo;
    private final String descricao;

    Permissao(String modulo, String descricao) {
        this.modulo = modulo;
        this.descricao = descricao;
    }

    public String getModulo() {
        return modulo;
    }

    public String getDescricao() {
        return descricao;
    }
}
