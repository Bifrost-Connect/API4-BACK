package com.bifrostconnect.api_geo.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class CatalogoDesempenhoIntegrationTest {

    private static final int QTD_CARGAS = 5000;
    private static final double LIMITE_MS = 200.0;

    private static final Pattern EXECUTION_TIME = Pattern.compile("Execution Time: ([0-9.]+) ms");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Busca combinada do catálogo responde abaixo de 200ms com 5 mil cargas")
    void buscaCombinadaDeveFicarAbaixoDe200ms() {
        String sufixo = String.valueOf(ThreadLocalRandom.current().nextInt(100_000_000, 999_999_999));

        jdbcTemplate.update("INSERT INTO orgao (id, nome, sigla, ativo) VALUES (1, 'Órgão', 'OT', true) ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.update("INSERT INTO perfil (id, nome, descricao) VALUES (1, 'OPERADOR', 'Perfil') ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.update("INSERT INTO usuario (id, nome, email, senha_hash, perfil_id, ativo) VALUES (1, 'Op', 'op@test.com', 'hash', 1, true) ON CONFLICT (id) DO NOTHING");

        Long conjuntoId = jdbcTemplate.queryForObject(
                "INSERT INTO conjunto (nome, ativo) VALUES (?, true) RETURNING id", Long.class, "Desempenho " + sufixo);

        // cargas espalhadas por etapas, situações e safras diferentes
        jdbcTemplate.update("""
                INSERT INTO processo (operador_id, conjunto_id, orgao_id, etapa_atual_id, situacao_atual_id,
                                      ano_safra, epsg_origem, cobertura_espacial, classificacao_acesso)
                SELECT 1, ?, 1,
                       (SELECT id FROM etapa ORDER BY id OFFSET (g % 5) LIMIT 1),
                       (SELECT id FROM situacao ORDER BY id OFFSET (g % 5) LIMIT 1),
                       (2015 + g % 10)::text, '4674', 'Região ' || (g % 27), 'Pública'
                  FROM generate_series(1, ?) g
                """, conjuntoId, QTD_CARGAS);
        jdbcTemplate.execute("ANALYZE processo");

        // mesma consulta que a listagem monta com busca + conjunto + ano + situação + ordenação + página
        List<String> plano = jdbcTemplate.queryForList("""
                EXPLAIN ANALYZE
                SELECT v.*
                  FROM vw_catalogo_bases_operacional v
                 WHERE (v.conjunto ILIKE '%desempenho%' OR v.orgao ILIKE '%desempenho%' OR v.orgao_sigla ILIKE '%desempenho%'
                        OR v.ano_safra ILIKE '%desempenho%' OR v.cobertura_espacial ILIKE '%desempenho%')
                   AND v.conjunto_id = ?
                   AND v.ano_safra = '2020'
                   AND (v.status_operacional = 'EM_PROCESSAMENTO' OR v.situacao = 'EM_PROCESSAMENTO')
                 ORDER BY v.data_publicacao DESC NULLS LAST, v.id DESC
                 LIMIT 20 OFFSET 0
                """, String.class, conjuntoId);

        double tempo = plano.stream()
                .map(EXECUTION_TIME::matcher)
                .filter(Matcher::find)
                .mapToDouble(m -> Double.parseDouble(m.group(1)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("EXPLAIN ANALYZE não trouxe o Execution Time:\n" + String.join("\n", plano)));

        System.out.println("Busca combinada do catálogo: " + tempo + "ms com " + QTD_CARGAS + " cargas");
        assertTrue(tempo < LIMITE_MS,
                "Busca do catálogo levou " + tempo + "ms (limite " + LIMITE_MS + "ms). Plano:\n" + String.join("\n", plano));
    }
}
