package com.bifrostconnect.api_geo.repository;

import com.bifrostconnect.api_geo.dto.LogEspacialResponse;
import com.bifrostconnect.api_geo.dto.ProcessoDashboardResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ProcessoDashboardRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public Page<ProcessoDashboardResponse> listarProcessos(
            LocalDateTime dataInicio,
            LocalDateTime dataFim,
            Long conjuntoId,
            Long etapaId,
            Long situacaoId,
            Pageable pageable) {

        StringBuilder sql = new StringBuilder("""
                SELECT
                    p.id,
                    p.operador_id,
                    p.conjunto_id,
                    p.orgao_id,
                    p.ano_safra,
                    p.epsg_origem,
                    p.etapa_atual_id,
                    e.nome AS etapa,
                    p.situacao_atual_id,
                    s.nome AS situacao,
                    p.data_criacao
                FROM processo p
                LEFT JOIN etapa e
                    ON e.id = p.etapa_atual_id
                LEFT JOIN situacao s
                    ON s.id = p.situacao_atual_id
                WHERE 1 = 1
                """);

        adicionarFiltros(
                sql,
                dataInicio,
                dataFim,
                conjuntoId,
                etapaId,
                situacaoId
        );

        sql.append("""
                ORDER BY p.data_criacao DESC
                """);

        Query query = entityManager.createNativeQuery(
                sql.toString()
        );

        adicionarParametros(
                query,
                dataInicio,
                dataFim,
                conjuntoId,
                etapaId,
                situacaoId
        );

        query.setFirstResult(
                (int) pageable.getOffset()
        );

        query.setMaxResults(
                pageable.getPageSize()
        );

        @SuppressWarnings("unchecked")
        List<Object[]> resultados = query.getResultList();

        List<ProcessoDashboardResponse> processos =
                new ArrayList<>();

        for (Object[] resultado : resultados) {
            processos.add(
                    mapearProcesso(resultado)
            );
        }

        long total = contarProcessos(
                dataInicio,
                dataFim,
                conjuntoId,
                etapaId,
                situacaoId
        );

        return new PageImpl<>(
                processos,
                pageable,
                total
        );
    }

    private ProcessoDashboardResponse mapearProcesso(
            Object[] resultado) {

        return new ProcessoDashboardResponse(
                toLong(resultado[0]),
                toLong(resultado[1]),
                toLong(resultado[2]),
                toLong(resultado[3]),
                resultado[4] != null
                        ? resultado[4].toString()
                        : null,
                resultado[5] != null
                        ? resultado[5].toString()
                        : null,
                toLong(resultado[6]),
                toLong(resultado[8]),
                toLocalDateTime(resultado[10])
        );
    }

    private long contarProcessos(
            LocalDateTime dataInicio,
            LocalDateTime dataFim,
            Long conjuntoId,
            Long etapaId,
            Long situacaoId) {

        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                FROM processo p
                WHERE 1 = 1
                """);

        adicionarFiltros(
                sql,
                dataInicio,
                dataFim,
                conjuntoId,
                etapaId,
                situacaoId
        );

        Query query = entityManager.createNativeQuery(
                sql.toString()
        );

        adicionarParametros(
                query,
                dataInicio,
                dataFim,
                conjuntoId,
                etapaId,
                situacaoId
        );

        Number resultado =
                (Number) query.getSingleResult();

        return resultado.longValue();
    }

    private void adicionarFiltros(
            StringBuilder sql,
            LocalDateTime dataInicio,
            LocalDateTime dataFim,
            Long conjuntoId,
            Long etapaId,
            Long situacaoId) {

        if (dataInicio != null) {
            sql.append(
                    " AND p.data_criacao >= :dataInicio "
            );
        }

        if (dataFim != null) {
            sql.append(
                    " AND p.data_criacao < :dataFim "
            );
        }

        if (conjuntoId != null) {
            sql.append(
                    " AND p.conjunto_id = :conjuntoId "
            );
        }

        if (etapaId != null) {
            sql.append(
                    " AND p.etapa_atual_id = :etapaId "
            );
        }

        if (situacaoId != null) {
            sql.append(
                    " AND p.situacao_atual_id = :situacaoId "
            );
        }
    }

    private void adicionarParametros(
            Query query,
            LocalDateTime dataInicio,
            LocalDateTime dataFim,
            Long conjuntoId,
            Long etapaId,
            Long situacaoId) {

        if (dataInicio != null) {
            query.setParameter(
                    "dataInicio",
                    Timestamp.valueOf(dataInicio)
            );
        }

        if (dataFim != null) {
            query.setParameter(
                    "dataFim",
                    Timestamp.valueOf(dataFim)
            );
        }

        if (conjuntoId != null) {
            query.setParameter(
                    "conjuntoId",
                    conjuntoId
            );
        }

        if (etapaId != null) {
            query.setParameter(
                    "etapaId",
                    etapaId
            );
        }

        if (situacaoId != null) {
            query.setParameter(
                    "situacaoId",
                    situacaoId
            );
        }
    }

    public Long contarTodosOsProcessos() {

        Query query = entityManager.createNativeQuery("""
                SELECT COUNT(*)
                FROM processo
                """);

        Number resultado =
                (Number) query.getSingleResult();

        return resultado.longValue();
    }

    public Map<String, Long> contarPorSituacao() {

        Query query = entityManager.createNativeQuery("""
                SELECT
                    COALESCE(s.nome, 'SEM_SITUACAO') AS nome,
                    COUNT(p.id) AS quantidade
                FROM processo p
                LEFT JOIN situacao s
                    ON s.id = p.situacao_atual_id
                GROUP BY s.nome
                ORDER BY s.nome
                """);

        return transformarResultados(
                query.getResultList()
        );
    }

    public Map<String, Long> contarPorEtapa() {

        Query query = entityManager.createNativeQuery("""
                SELECT
                    COALESCE(e.nome, 'SEM_ETAPA') AS nome,
                    COUNT(p.id) AS quantidade
                FROM processo p
                LEFT JOIN etapa e
                    ON e.id = p.etapa_atual_id
                GROUP BY e.nome
                ORDER BY e.nome
                """);

        return transformarResultados(
                query.getResultList()
        );
    }

    public Map<String, Long> contarPorConjunto() {

        Query query = entityManager.createNativeQuery("""
                SELECT
                    COALESCE(c.nome, 'SEM_CONJUNTO') AS nome,
                    COUNT(p.id) AS quantidade
                FROM processo p
                LEFT JOIN conjunto c
                    ON c.id = p.conjunto_id
                GROUP BY c.nome
                ORDER BY c.nome
                """);

        return transformarResultados(
                query.getResultList()
        );
    }

    private Map<String, Long> transformarResultados(
            List<?> resultados) {

        Map<String, Long> resultadoFinal =
                new LinkedHashMap<>();

        for (Object item : resultados) {

            Object[] linha = (Object[]) item;

            String nome = linha[0] != null
                    ? linha[0].toString()
                    : null;

            Number quantidade =
                    (Number) linha[1];

            resultadoFinal.put(
                    nome,
                    quantidade.longValue()
            );
        }

        return resultadoFinal;
    }

    public List<LogEspacialResponse> buscarLogEspacial(
            Long processoId) {

        Query query = entityManager.createNativeQuery("""
                SELECT
                    lp.id,
                    lp.processo_id,
                    lp.processo_etapa_id,
                    pe.etapa_id,
                    e.nome AS etapa,
                    pe.situacao_id,
                    s.nome AS situacao,
                    pe.tentativa,
                    lp.nivel,
                    lp.mensagem,
                    lp.codigo_erro,
                    lp.detalhes,
                    lp.data_hora
                FROM log_processamento lp
                LEFT JOIN processo_etapa pe
                    ON pe.id = lp.processo_etapa_id
                LEFT JOIN etapa e
                    ON e.id = pe.etapa_id
                LEFT JOIN situacao s
                    ON s.id = pe.situacao_id
                WHERE lp.processo_id = :processoId
                ORDER BY lp.data_hora ASC
                """);

        query.setParameter(
                "processoId",
                processoId
        );

        @SuppressWarnings("unchecked")
        List<Object[]> resultados =
                query.getResultList();

        List<LogEspacialResponse> logs =
                new ArrayList<>();

        for (Object[] resultado : resultados) {

            logs.add(
                    new LogEspacialResponse(
                            toLong(resultado[0]),
                            toLong(resultado[1]),
                            toLong(resultado[2]),
                            toLong(resultado[3]),
                            resultado[4] != null
                                    ? resultado[4].toString()
                                    : null,
                            toLong(resultado[5]),
                            resultado[6] != null
                                    ? resultado[6].toString()
                                    : null,
                            toInteger(resultado[7]),
                            resultado[8] != null
                                    ? resultado[8].toString()
                                    : null,
                            resultado[9] != null
                                    ? resultado[9].toString()
                                    : null,
                            resultado[10] != null
                                    ? resultado[10].toString()
                                    : null,
                            resultado[11] != null
                                    ? resultado[11].toString()
                                    : null,
                            toLocalDateTime(resultado[12])
                    )
            );
        }

        return logs;
    }

    private Long toLong(Object valor) {

        if (valor == null) {
            return null;
        }

        return ((Number) valor).longValue();
    }

    private Integer toInteger(Object valor) {

        if (valor == null) {
            return null;
        }

        return ((Number) valor).intValue();
    }

    private LocalDateTime toLocalDateTime(Object valor) {

        if (valor == null) {
            return null;
        }

        if (valor instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }

        if (valor instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }

        return null;
    }
}