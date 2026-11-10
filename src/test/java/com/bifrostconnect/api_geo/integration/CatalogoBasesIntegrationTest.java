package com.bifrostconnect.api_geo.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class CatalogoBasesIntegrationTest {

    private static final String POLIGONO = "POLYGON((-9.8 -9.8, -9.8 -9.2, -9.2 -9.2, -9.2 -9.8, -9.8 -9.8))";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    // cada teste usa um conjunto e um ano_safra próprios, assim o filtro isola só as bases
    // criadas aqui e o resto do banco local não interfere
    private String anoSafra;
    private Long conjuntoId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        anoSafra = "T" + ThreadLocalRandom.current().nextInt(100_000_000, 999_999_999);

        jdbcTemplate.update("INSERT INTO orgao (id, nome, sigla, ativo) VALUES (1, 'Órgão', 'OT', true) ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.update("INSERT INTO perfil (id, nome, descricao) VALUES (1, 'OPERADOR', 'Perfil') ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.update("INSERT INTO usuario (id, nome, email, senha_hash, perfil_id, ativo) VALUES (1, 'Op', 'op@test.com', 'hash', 1, true) ON CONFLICT (id) DO NOTHING");
        conjuntoId = jdbcTemplate.queryForObject(
                "INSERT INTO conjunto (nome, ativo) VALUES (?, true) RETURNING id",
                Long.class, "Conjunto Catálogo " + anoSafra);
    }

    // ---------- listagem, filtros e paginação ----------

    @Test
    @DisplayName("Listagem devolve página com total_items e total_pages")
    @WithMockUser(roles = "ANALISTA")
    void deveListarPaginado() throws Exception {
        for (int i = 0; i < 5; i++) {
            fecharVersao(criarBase("PUBLICACAO", "CONCLUIDA"));
        }

        mockMvc.perform(get("/catalogo/bases")
                        .param("conjunto", conjuntoId.toString())
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total_items").value(5))
                .andExpect(jsonPath("$.total_pages").value(3));

        // última página só com o que sobrou
        mockMvc.perform(get("/catalogo/bases")
                        .param("conjunto", conjuntoId.toString())
                        .param("page", "2")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total_items").value(5));
    }

    @Test
    @DisplayName("Filtros combinados (conjunto + ano + situação + apenas_disponiveis) batem com a contagem")
    @WithMockUser(roles = "ANALISTA")
    void deveCombinarFiltros() throws Exception {
        fecharVersao(criarBase("PUBLICACAO", "CONCLUIDA"));
        fecharVersao(criarBase("PUBLICACAO", "CONCLUIDA"));
        criarBase("VALIDACAO", "EM_VALIDACAO");
        criarBase("TRATAMENTO", "EM_ANDAMENTO");

        // sem apenas_disponiveis vem tudo (as não publicadas só com o resumo)
        mockMvc.perform(get("/catalogo/bases")
                        .param("conjunto", conjuntoId.toString())
                        .param("ano", anoSafra))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_items").value(4))
                .andExpect(jsonPath("$.total_pages").value(1));

        mockMvc.perform(get("/catalogo/bases")
                        .param("conjunto", conjuntoId.toString())
                        .param("ano", anoSafra)
                        .param("apenas_disponiveis", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_items").value(2))
                .andExpect(jsonPath("$.items[*].pronta_para_consulta").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(true))));

        mockMvc.perform(get("/catalogo/bases")
                        .param("conjunto", conjuntoId.toString())
                        .param("situacao", "em_quarentena"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_items").value(1))
                .andExpect(jsonPath("$.items[0].status_operacional").value("EM_QUARENTENA"));

        // filtro que não casa com nada continua 204
        mockMvc.perform(get("/catalogo/bases")
                        .param("conjunto", conjuntoId.toString())
                        .param("ano", "ano-que-nao-existe"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("Busca textual pelo nome do conjunto")
    @WithMockUser(roles = "ANALISTA")
    void deveBuscarPorTexto() throws Exception {
        fecharVersao(criarBase("PUBLICACAO", "CONCLUIDA"));

        mockMvc.perform(get("/catalogo/bases").param("busca", "catálogo " + anoSafra.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_items").value(1))
                .andExpect(jsonPath("$.items[0].conjunto_id").value(conjuntoId));
    }

    @Test
    @DisplayName("Paginação e ordenação inválidas devolvem 400")
    @WithMockUser(roles = "ANALISTA")
    void deveRecusarPaginacaoInvalida() throws Exception {
        mockMvc.perform(get("/catalogo/bases").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").exists());
        mockMvc.perform(get("/catalogo/bases").param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/catalogo/bases").param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/catalogo/bases").param("sort", "senha_hash; DROP TABLE processo"))
                .andExpect(status().isBadRequest());
    }

    // ---------- sanitização ----------

    @Test
    @DisplayName("Base não publicada aparece só com o resumo administrativo")
    @WithMockUser(roles = "ANALISTA")
    void deveSanitizarBaseNaoPublicada() throws Exception {
        Long emQuarentena = criarBase("VALIDACAO", "EM_VALIDACAO");
        jdbcTemplate.update("INSERT INTO feicao_carga (arquivo_processado_id, codigo_imovel, geom) VALUES (?, 'X', ST_GeomFromText(?, 4674))",
                criarArquivoProcessado(emQuarentena), POLIGONO);

        mockMvc.perform(get("/catalogo/bases").param("conjunto", conjuntoId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(emQuarentena))
                .andExpect(jsonPath("$.items[0].conjunto").exists())
                .andExpect(jsonPath("$.items[0].ano_safra").value(anoSafra))
                .andExpect(jsonPath("$.items[0].orgao").exists())
                .andExpect(jsonPath("$.items[0].etapa_atual").value("VALIDACAO"))
                .andExpect(jsonPath("$.items[0].status_operacional").value("EM_QUARENTENA"))
                .andExpect(jsonPath("$.items[0].pronta_para_consulta").value(false))
                .andExpect(jsonPath("$.items[0].total_feicoes").doesNotExist())
                .andExpect(jsonPath("$.items[0].epsg_destino").doesNotExist())
                .andExpect(jsonPath("$.items[0].cobertura_espacial").doesNotExist())
                .andExpect(jsonPath("$.items[0].versao_oficial").doesNotExist());
    }

    // ---------- detalhes e blindagem ----------

    @Test
    @DisplayName("Só a carga publicada libera os detalhes; a em quarentena dá 409")
    @WithMockUser(roles = "ANALISTA")
    void deveLiberarDetalhesSomenteDaBasePublicada() throws Exception {
        Long publicada = criarBase("PUBLICACAO", "CONCLUIDA");
        jdbcTemplate.update("INSERT INTO feicao_carga (arquivo_processado_id, codigo_imovel, geom, atributos) VALUES (?, 'IMOVEL_1', ST_GeomFromText(?, 4674), '{\"municipio\": \"Teste\"}'::jsonb)",
                criarArquivoProcessado(publicada), POLIGONO);
        fecharVersao(publicada);

        Long emQuarentena = criarBase("VALIDACAO", "EM_VALIDACAO");

        mockMvc.perform(get("/catalogo/bases/{id}/detalhes", publicada))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ficha_tecnica.id").value(publicada))
                .andExpect(jsonPath("$.ficha_tecnica.versao_oficial").value("v1"))
                .andExpect(jsonPath("$.ficha_tecnica.safra_vigente").value(true))
                .andExpect(jsonPath("$.resumo_areas.total_feicoes").value(1))
                .andExpect(jsonPath("$.resumo_areas.area_total_ha").isNumber())
                .andExpect(jsonPath("$.amostra_registros.total_items").value(1))
                .andExpect(jsonPath("$.amostra_registros.items[0].codigo_imovel").value("IMOVEL_1"))
                .andExpect(jsonPath("$.amostra_registros.items[0].atributos.municipio").value("Teste"));

        mockMvc.perform(get("/catalogo/bases/{id}/detalhes", emQuarentena))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").exists())
                .andExpect(jsonPath("$.etapa_atual").value("VALIDACAO"))
                .andExpect(jsonPath("$.situacao").value("EM_VALIDACAO"));
    }

    @Test
    @DisplayName("Base em Tratamento e publicação sem versão fechada também dão 409")
    @WithMockUser(roles = "ANALISTA")
    void deveBloquearDetalhesDeBaseIndisponivel() throws Exception {
        Long emTratamento = criarBase("TRATAMENTO", "EM_ANDAMENTO");
        Long publicacaoSemFechar = criarBase("PUBLICACAO", "CONCLUIDA");

        mockMvc.perform(get("/catalogo/bases/{id}/detalhes", emTratamento))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/catalogo/bases/{id}/detalhes", publicacaoSemFechar))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("ID inexistente dá 404 tanto na ficha quanto nos detalhes")
    @WithMockUser(roles = "ANALISTA")
    void deveRetornar404ParaBaseInexistente() throws Exception {
        mockMvc.perform(get("/catalogo/bases/{id}", Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erro").exists());
        mockMvc.perform(get("/catalogo/bases/{id}/detalhes", Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erro").exists());
    }

    @Test
    @DisplayName("Ficha resumida da base publicada sai completa")
    @WithMockUser(roles = "ANALISTA")
    void deveRetornarFichaDaBasePublicada() throws Exception {
        Long publicada = criarBase("PUBLICACAO", "CONCLUIDA");
        fecharVersao(publicada);

        mockMvc.perform(get("/catalogo/bases/{id}", publicada))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(publicada))
                .andExpect(jsonPath("$.ano_safra").value(anoSafra))
                .andExpect(jsonPath("$.versao_oficial").value("v1"))
                .andExpect(jsonPath("$.pronta_para_consulta").value(true));
    }

    // ---------- vigência ----------

    @Test
    @DisplayName("Nova publicação do mesmo conjunto vira a vigente e a anterior fica de histórico")
    @WithMockUser(roles = "ANALISTA")
    void deveMarcarSomenteUltimaPublicacaoComoVigente() throws Exception {
        Long antiga = criarBase("PUBLICACAO", "CONCLUIDA");
        fecharVersao(antiga);
        // data_publicacao maior pra nova (now() é o mesmo dentro da transação)
        Long nova = criarBase("PUBLICACAO", "CONCLUIDA");
        jdbcTemplate.update("INSERT INTO historico_aprovacoes_gestor (processo_id, gestor_id, etapa_avaliada, decisao) VALUES (?, 1, 'PUBLICACAO', 'APROVADO')", nova);
        jdbcTemplate.update("UPDATE processo SET versao_oficial = 'v2', data_publicacao = now() + interval '1 day', gestor_publicacao_id = 1, versao_fechada = TRUE WHERE id = ?", nova);

        mockMvc.perform(get("/catalogo/bases/{id}", nova))
                .andExpect(jsonPath("$.safra_vigente").value(true));
        // a antiga continua consultável, só deixa de ser a vigente
        mockMvc.perform(get("/catalogo/bases/{id}/detalhes", antiga))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ficha_tecnica.safra_vigente").value(false));
    }

    // ---------- resumo e opções ----------

    @Test
    @DisplayName("Resumo conta bases por status e por conjunto")
    @WithMockUser(roles = "ANALISTA")
    void deveResumirCatalogo() throws Exception {
        fecharVersao(criarBase("PUBLICACAO", "CONCLUIDA"));
        criarBase("VALIDACAO", "EM_VALIDACAO");
        criarBase("TRATAMENTO", "EM_ANDAMENTO");

        String conjunto = "$.por_conjunto[?(@.conjunto_id == " + conjuntoId + ")]";
        mockMvc.perform(get("/catalogo/resumo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_bases").isNumber())
                .andExpect(jsonPath("$.por_status[*].status_operacional", hasItem("DISPONIVEL")))
                .andExpect(jsonPath(conjunto + ".total").value(3))
                .andExpect(jsonPath(conjunto + ".disponiveis").value(1));
    }

    @Test
    @DisplayName("Opções de filtro vêm do banco")
    @WithMockUser(roles = "ANALISTA")
    void deveListarOpcoesDeFiltro() throws Exception {
        criarBase("TRATAMENTO", "EM_ANDAMENTO");

        mockMvc.perform(get("/catalogo/opcoes-filtros"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orgaos[*].id", hasItem(1)))
                .andExpect(jsonPath("$.conjuntos[*].id", hasItem(conjuntoId.intValue())))
                .andExpect(jsonPath("$.anos", hasItem(anoSafra)))
                .andExpect(jsonPath("$.situacoes", hasItem("EM_PROCESSAMENTO")));
    }

    private Long criarBase(String etapa, String situacao) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO processo (operador_id, conjunto_id, orgao_id, etapa_atual_id, situacao_atual_id, ano_safra, epsg_origem)
            VALUES (1, ?, 1,
                    (SELECT id FROM etapa WHERE nome = ?),
                    (SELECT id FROM situacao WHERE nome = ?),
                    ?, '4674')
            RETURNING id
            """, Long.class, conjuntoId, etapa, situacao, anoSafra);
    }

    private Long criarArquivoProcessado(Long processoId) {
        Long arquivoOriginalId = jdbcTemplate.queryForObject("""
            INSERT INTO arquivo_original (processo_id, usuario_upload_id, nome_original, url_armazenamento, hash_sha256)
            VALUES (?, 1, 'catalogo.geojson', '/tmp/catalogo.geojson', ?)
            RETURNING id
            """, Long.class, processoId, UUID.randomUUID().toString().replace("-", ""));

        return jdbcTemplate.queryForObject("""
            INSERT INTO arquivo_processado (processo_id, arquivo_original_id, etapa_geracao_id, nome_arquivo, url_armazenamento, versao)
            VALUES (?, ?, (SELECT id FROM etapa WHERE nome = 'TRATAMENTO'), 'catalogo_tratado.geojson', '/tmp/catalogo_tratado.geojson', 1)
            RETURNING id
            """, Long.class, processoId, arquivoOriginalId);
    }

    // a V22 só deixa fechar a versão com aprovação do gestor na PUBLICACAO e os dados oficiais preenchidos
    private void fecharVersao(Long processoId) {
        jdbcTemplate.update("""
            INSERT INTO historico_aprovacoes_gestor (processo_id, gestor_id, etapa_avaliada, decisao)
            VALUES (?, 1, 'PUBLICACAO', 'APROVADO')
            """, processoId);
        jdbcTemplate.update("""
            UPDATE processo
               SET versao_oficial = 'v1', data_publicacao = now(), gestor_publicacao_id = 1, versao_fechada = TRUE
             WHERE id = ?
            """, processoId);
    }
}
