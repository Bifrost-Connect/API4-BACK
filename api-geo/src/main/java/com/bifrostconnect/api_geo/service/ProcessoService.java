package com.bifrostconnect.api_geo.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.bifrostconnect.api_geo.dto.ResultadoCalculoAnalitico;
import com.bifrostconnect.api_geo.dto.ResultadoValidacaoTopologica;
import com.bifrostconnect.api_geo.entity.ArquivoProcessado;
import com.bifrostconnect.api_geo.entity.Processo;
import com.bifrostconnect.api_geo.exception.FalhaGeoespacialException;
import com.bifrostconnect.api_geo.repository.ProcessoRepository;

@Service
public class ProcessoService {

    private final ProcessoRepository processoRepository;
    private final ValidacaoService validacaoService;
    private final AuditoriaLogService auditoriaLogService;
    private final ProcessoEstadoService processoEstadoService;
    private final GeoJsonEpsgService geoJsonEpsgService;
    private final GeoJsonArquivoService geoJsonArquivoService;
    private final PadronizacaoEpsgService padronizacaoEpsgService;
    private final ArquivoProcessadoService arquivoProcessadoService;
    private final ValidacaoTopologicaService validacaoTopologicaService;
    private final CalculoAnaliticoService calculoAnaliticoService;
    private final NotificacaoService notificacaoService;

    public ProcessoService(
            ProcessoRepository processoRepository,
            ValidacaoService validacaoService,
            AuditoriaLogService auditoriaLogService,
            ProcessoEstadoService processoEstadoService,
            GeoJsonEpsgService geoJsonEpsgService,
            GeoJsonArquivoService geoJsonArquivoService,
            PadronizacaoEpsgService padronizacaoEpsgService,
            ArquivoProcessadoService arquivoProcessadoService,
            ValidacaoTopologicaService validacaoTopologicaService,
            CalculoAnaliticoService calculoAnaliticoService,
            NotificacaoService notificacaoService
        ) {

        this.processoRepository = processoRepository;
        this.validacaoService = validacaoService;
        this.auditoriaLogService = auditoriaLogService;
        this.processoEstadoService = processoEstadoService;
        this.geoJsonEpsgService = geoJsonEpsgService;
        this.geoJsonArquivoService = geoJsonArquivoService;
        this.padronizacaoEpsgService = padronizacaoEpsgService;
        this.arquivoProcessadoService = arquivoProcessadoService;
        this.validacaoTopologicaService = validacaoTopologicaService;
        this.calculoAnaliticoService = calculoAnaliticoService;
        this.notificacaoService = notificacaoService;
    }

    public Processo buscarPorId(Long id) {
        return processoRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Processo não encontrado para o ID: " + id));
    }

    // Retorna 409 (Conflict) caso receba comandos de edição para um ID de versão que já foi fechada
    private void verificarSeFechado(Processo processo) {
        if (processo.getSituacaoAtualId() != null && processo.getSituacaoAtualId().equals(999L)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Conflito: A versão/processo já foi fechada e não permite edições.");
        }
    }

    public List<?> listarAuditoresReais() {
        return List.of();
    }

    @Transactional
    public Processo alocarEditorEAtualizarSituacao(
            Long processoId,
            Long editorId) {

        Processo processo = buscarPorId(processoId);
        verificarSeFechado(processo);

        processoEstadoService.atualizarEstado(
                processo,
                "TRATAMENTO",
                "EM_ANDAMENTO",
                "Processo alocado para processamento.");

        auditoriaLogService.registrarLog(
                processo,
                "INFO",
                "Processo colocado em andamento pelo editor ID: " + editorId);

        return salvarGarantindoCampos(processo);
    }


    // noRollbackFor: quando a etapa termina em falha controlada (422/415) o log de erro, a
    // auditoria e o EPSG detectado já gravados NÃO podem ser desfeitos pelo rollback - senão
    // o processo ficaria FALHOU sem nenhuma explicação registrada
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Processo processarArquivo(
            Processo processo,
            String nomeArquivo,
            Path caminhoArquivo,
            Long usuarioId) {

        verificarSeFechado(processo);

        if (caminhoArquivo == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "O arquivo é obrigatório para iniciar o tratamento.");
        }

        Path caminhoGeoJson = null;

        try {
            caminhoGeoJson = geoJsonArquivoService.preparar(caminhoArquivo);

            return processarArquivoGeoJson(
                    processo,
                    nomeArquivo,
                    caminhoGeoJson,
                    usuarioId);

        } catch (ResponseStatusException e) {
            throw e;

        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    e.getMessage(),
                    e);

        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Não foi possível ler ou preparar o arquivo GeoJSON.",
                    e);

        } finally {
            if (caminhoGeoJson != null && !caminhoGeoJson.equals(caminhoArquivo)) {
                try {
                    Files.deleteIfExists(caminhoGeoJson);
                } catch (IOException e) {
                    auditoriaLogService.registrarLog(
                            processo,
                            "WARNING",
                            "Não foi possível excluir o GeoJSON temporário: "
                                    + e.getMessage());
                }
            }
        }
    }

    // etapa TRATAMENTO: padronização e checagem de EPSG
    private Processo processarArquivoGeoJson(
            Processo processo,
            String nomeOriginal,
            Path caminhoGeoJson,
            Long usuarioId) {

        processoEstadoService.atualizarEstado(
                processo,
                "TRATAMENTO",
                "EM_ANDAMENTO",
                "Tratamento iniciado: padronização e checagem de EPSG.");

        auditoriaLogService.registrarLog(
                processo,
                "INFO",
                "Iniciando fluxo do processo para o arquivo: " + nomeOriginal);

        auditoriaLogService.registrarAuditoria(
                usuarioId,
                "INICIO_PROCESSO",
                "Processo ID: " + processo.getId());

        String epsgDeclarado = null;
        String epsgDetectado = null;
        ArquivoProcessado copiaTrabalho;

        try {
            epsgDeclarado = padronizacaoEpsgService.normalizarEpsg(processo.getEpsgOrigem());
            geoJsonEpsgService.validarCodigoEpsg(epsgDeclarado);

            epsgDetectado = geoJsonEpsgService.detectar(caminhoGeoJson);

            // EPSG detectado fica em coluna própria, separada do declarado (epsg_origem).
            processo.setEpsgDetectado(epsgDetectado);

            PadronizacaoEpsgService.ResultadoChecagem checagem =
                    padronizacaoEpsgService.comparar(epsgDeclarado, epsgDetectado);

            if (checagem.divergente()) {

                String mensagem = "Divergência de EPSG: declarado EPSG:"
                        + epsgDeclarado
                        + ", detectado EPSG:"
                        + epsgDetectado
                        + ". Tratamento bloqueado. Corrija manualmente o cadastro "
                        + "ou o arquivo e envie novamente para validação.";

                processoEstadoService.atualizarEstado(
                        processo,
                        "TRATAMENTO",
                        "COM_RESSALVA",
                        mensagem);

                auditoriaLogService.registrarLog(
                        processo,
                        "WARNING",
                        mensagem);

                auditoriaLogService.registrarAuditoria(
                        usuarioId,
                        "DIVERGENCIA_EPSG",
                        "Processo ID: " + processo.getId()
                                + "; declarado=" + epsgDeclarado
                                + "; detectado=" + epsgDetectado);

                // Interrompido: sem cópia de trabalho e sem validações.
                return salvarGarantindoCampos(processo);
            }

            geoJsonEpsgService.validarCodigoEpsg(epsgDetectado);

            copiaTrabalho = arquivoProcessadoService.criarCopiaPadronizada(
                    processo.getId(),
                    processo.getEtapaAtualId(), // TRATAMENTO: etapa que gerou a cópia
                    caminhoGeoJson,
                    epsgDetectado);

            processo.setEpsgDestino(PadronizacaoEpsgService.EPSG_OFICIAL);

        } catch (FalhaGeoespacialException e) {
            // Tarefa 15: tem que vir antes do ResponseStatusException (ela é filha dele)
            throw enviarParaQuarentenaGeoespacial(processo, usuarioId, e);

        } catch (ResponseStatusException e) {
            throw e;

        } catch (Exception e) {
            String mensagem = "Falha na padronização/verificação do EPSG: "
                    + e.getMessage();

            processoEstadoService.atualizarEstado(
                    processo,
                    "TRATAMENTO",
                    "FALHOU",
                    mensagem);

            auditoriaLogService.registrarLog(
                    processo,
                    "ERROR",
                    mensagem);

            salvarGarantindoCampos(processo);

            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    mensagem,
                    e);
        }

        auditoriaLogService.registrarLog(
                processo,
                "INFO",
                "EPSG padronizado: declarado EPSG:" + epsgDeclarado
                        + ", detectado EPSG:" + epsgDetectado
                        + ", cópia de trabalho em EPSG:" + PadronizacaoEpsgService.EPSG_OFICIAL
                        + " (arquivo_processado ID: " + copiaTrabalho.getId() + ").");

        // 2ª parte do Tratamento: checa a topologia dos polígonos da cópia de trabalho
        processoEstadoService.atualizarEstado(
                processo,
                "TRATAMENTO",
                "EM_ANDAMENTO",
                "Tratamento: validação topológica dos polígonos.");

        ResultadoValidacaoTopologica topologia;

        try {
            topologia = validacaoTopologicaService.validar(processo, copiaTrabalho);

        } catch (FalhaGeoespacialException e) {
            throw enviarParaQuarentenaGeoespacial(processo, usuarioId, e);

        } catch (Exception e) {
            String mensagem = "Falha na validação topológica dos polígonos: "
                    + e.getMessage();

            processoEstadoService.atualizarEstado(
                    processo,
                    "TRATAMENTO",
                    "FALHOU",
                    mensagem);

            auditoriaLogService.registrarLog(
                    processo,
                    "ERROR",
                    mensagem);

            salvarGarantindoCampos(processo);

            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    mensagem,
                    e);
        }

        auditoriaLogService.registrarLog(
                processo,
                topologia.possuiProblemas() ? "WARNING" : "INFO",
                "Validação topológica: " + topologia.feicoesCarregadas() + " de "
                        + topologia.totalFeicoes() + " feições carregadas; "
                        + topologia.contar(ResultadoValidacaoTopologica.TipoProblema.GEOMETRIA_INVALIDA) + " inválidas/abertas, "
                        + topologia.contar(ResultadoValidacaoTopologica.TipoProblema.CODIGO_IMOVEL_DUPLICADO) + " códigos repetidos, "
                        + topologia.contar(ResultadoValidacaoTopologica.TipoProblema.POLIGONO_DUPLICADO) + " duplicados, "
                        + topologia.contar(ResultadoValidacaoTopologica.TipoProblema.POLIGONO_SOBREPOSTO) + " sobreposições.");

        // Tarefa 12: sobreposição/duplicação inaceitável não deixa nem chegar no cálculo
        List<ResultadoValidacaoTopologica.Problema> inaceitaveis = topologia.problemasInaceitaveis();
        if (!inaceitaveis.isEmpty()) {
            return enviarParaQuarentena(
                    processo,
                    usuarioId,
                    "QUARENTENA_TOPOLOGIA",
                    "Validação topológica encontrou " + inaceitaveis.size()
                            + " sobreposição(ões)/duplicação(ões) inaceitável(is).",
                    inaceitaveis.stream().map(ResultadoValidacaoTopologica.Problema::mensagem).toList());
        }

        // 3ª parte do Tratamento: área de cada polígono válido e cruzamento com APP, RL e Alertas
        processoEstadoService.atualizarEstado(
                processo,
                "TRATAMENTO",
                "EM_ANDAMENTO",
                "Tratamento: cálculo de área e cruzamentos espaciais.");

        ResultadoCalculoAnalitico calculo;

        try {
            calculo = calculoAnaliticoService.calcular(processo, copiaTrabalho);

        } catch (FalhaGeoespacialException e) {
            // Tarefa 15: erro do PostGIS na conta continua indo pra Quarentena (Tarefa 12),
            // mas agora a resposta é 422 em vez de 200
            throw enviarParaQuarentenaGeoespacial(processo, usuarioId, e);

        } catch (Exception e) {
            // Tarefa 12: erro do PostGIS no meio da conta (TopologyException etc.) não é falha
            // técnica do sistema, é a geometria da carga que não fecha -> vai pro Gestor decidir
            return enviarParaQuarentena(
                    processo,
                    usuarioId,
                    "QUARENTENA_CALCULO",
                    "Erro no cálculo analítico dos polígonos.",
                    List.of(String.valueOf(e.getMessage())));
        }

        if (calculo.possuiInconsistencias()) {
            return enviarParaQuarentena(
                    processo,
                    usuarioId,
                    "QUARENTENA_CALCULO",
                    "Cálculo analítico encontrou " + calculo.inconsistencias().size()
                            + " inconsistência(s) matemática(s)/geométrica(s).",
                    calculo.inconsistencias());
        }

        auditoriaLogService.registrarLog(
                processo,
                "INFO",
                "Cálculo analítico: " + calculo.poligonosCalculados() + " polígonos válidos somando "
                        + calculo.areaTotalHectares().toPlainString() + " ha; sobreposição com APP "
                        + calculo.hectaresPorCategoria(ResultadoCalculoAnalitico.Categoria.APP).toPlainString() + " ha ("
                        + calculo.contar(ResultadoCalculoAnalitico.Categoria.APP) + " cruzamentos), Reserva Legal "
                        + calculo.hectaresPorCategoria(ResultadoCalculoAnalitico.Categoria.RESERVA_LEGAL).toPlainString() + " ha ("
                        + calculo.contar(ResultadoCalculoAnalitico.Categoria.RESERVA_LEGAL) + " cruzamentos), Alertas "
                        + calculo.hectaresPorCategoria(ResultadoCalculoAnalitico.Categoria.ALERTA).toPlainString() + " ha ("
                        + calculo.contar(ResultadoCalculoAnalitico.Categoria.ALERTA) + " cruzamentos).");

        processoEstadoService.atualizarEstado(
                processo,
                "VALIDACAO",
                "EM_ANDAMENTO",
                "Tratamento concluído. Iniciando validações.");

        Path caminhoCopia = Path.of(copiaTrabalho.getUrlArmazenamento());

        boolean valido = validacaoService.executarValidacoes(
                processo,
                copiaTrabalho.getNomeArquivo(),
                caminhoCopia);

        // quarentena é EM_VALIDACAO no banco (V1: aguardando o auditor), igual a Tarefa 12.
        // FALHOU fica só pra erro técnico; e é o EM_VALIDACAO que faz a V14/V21 travar a carga
        if (!valido) {
            processoEstadoService.atualizarEstado(
                    processo,
                    "VALIDACAO",
                    "EM_VALIDACAO",
                    "Inconsistências encontradas. O processo foi movido para a quarentena.");

            auditoriaLogService.registrarLog(
                    processo,
                    "WARNING",
                    "Inconsistências encontradas. O processo foi movido para a QUARENTENA.");

            return salvarGarantindoCampos(processo);
        }

        // as contas já rodaram no Tratamento (Tarefa 11), aqui só registra a etapa como feita
        // em vez de marcar EM_ANDAMENTO e CONCLUIDA no mesmo instante
        processoEstadoService.atualizarEstado(
                processo,
                "CALCULO_ANALITICO",
                "CONCLUIDA",
                "Validação concluída. Cálculo analítico (áreas e cruzamentos) já gerado no Tratamento: "
                        + calculo.poligonosCalculados() + " polígonos, "
                        + calculo.areaTotalHectares().toPlainString() + " ha. Processo finalizado com sucesso.");

        auditoriaLogService.registrarLog(
                processo,
                "INFO",
                "Fluxo concluído com sucesso.");

        return salvarGarantindoCampos(processo);
    }


    // Tarefa 12: para o Tratamento na hora e deixa a carga parada em
    // TRATAMENTO / EM_VALIDACAO (quarentena) esperando a decisão do Gestor.
    // A V14 trava a cópia de trabalho enquanto ela estiver nesse estado
    private Processo enviarParaQuarentena(
            Processo processo,
            Long usuarioId,
            String acaoAuditoria,
            String motivo,
            List<String> detalhes) {

        return enviarParaQuarentena(processo, usuarioId, acaoAuditoria, motivo, detalhes, null, null);
    }

    private Processo enviarParaQuarentena(
            Processo processo,
            Long usuarioId,
            String acaoAuditoria,
            String motivo,
            List<String> detalhes,
            String codigoErro,
            String detalheTecnico) {

        String mensagem = motivo
                + " Tratamento interrompido e carga enviada para a Quarentena, aguardando decisão do Gestor.";

        processoEstadoService.atualizarEstado(
                processo,
                "TRATAMENTO",
                "EM_VALIDACAO",
                mensagem);

        // detalhe completo já fica em ocorrencia_validacao (topologia); no log vai só uma amostra
        int limite = 10;
        String amostra = String.join(" ", detalhes.subList(0, Math.min(limite, detalhes.size())))
                + (detalhes.size() > limite ? " (+" + (detalhes.size() - limite) + " outros)" : "");

        auditoriaLogService.registrarLog(
                processo,
                "WARNING",
                mensagem + " Detalhes: " + amostra,
                codigoErro,
                detalheTecnico);

        auditoriaLogService.registrarAuditoria(
                usuarioId,
                acaoAuditoria,
                "Processo ID: " + processo.getId() + "; " + motivo);

        notificacaoService.notificarAuditores(
                processo,
                "Carga do processo " + processo.getId() + " está em Quarentena na etapa de Tratamento: " + motivo);

        return salvarGarantindoCampos(processo);
    }

    // Tarefa 15: o PostGIS estourou numa conta geométrica ou na reprojeção (divisão por zero,
    // erro de projeção, TopologyException). O ExecucaoGeoespacial já voltou o savepoint, então a
    // transação ainda grava: manda a carga pra Quarentena com o código GEO_... no log e devolve
    // a própria exceção (422) pro controller. O noRollbackFor do processarArquivo segura o commit
    private FalhaGeoespacialException enviarParaQuarentenaGeoespacial(
            Processo processo,
            Long usuarioId,
            FalhaGeoespacialException falha) {

        enviarParaQuarentena(
                processo,
                usuarioId,
                "QUARENTENA_GEOESPACIAL",
                "Falha geoespacial na " + falha.getOperacao() + " (" + falha.getTipo().getDescricao() + ").",
                List.of(falha.getDetalheTecnico()),
                falha.getCodigoErro(),
                falha.getDetalheTecnico());

        return falha;
    }

    @Transactional
    public void fecharIndicador(Long id, Long gestorId) {
        Processo processo = buscarPorId(id);
        verificarSeFechado(processo);

        processoEstadoService.atualizarEstado(
                processo,
                "FECHAMENTO",
                "FECHADO",
                "Indicador fechado oficialmente."
        );

        auditoriaLogService.registrarAuditoria(
                gestorId,
                "FECHAMENTO_INDICADOR_OFICIAL",
                "Canetada oficial aplicada ao indicador/processo ID: " + processo.getId() + " pelo Gestor ID: " + gestorId
        );

        salvarGarantindoCampos(processo);
    }

    public Resource exportarArquivoConsolidado(Long id, String formato) {
        Processo processo = buscarPorId(id);

        try {
            Path pastaProcessada = Path.of("zona_processada_storage");
            if (!Files.exists(pastaProcessada)) {
                Files.createDirectories(pastaProcessada);
            }

            String extensao = formato != null && formato.equalsIgnoreCase("csv") ? "csv" : "geojson";
            String nomeArquivo = id + "_exportado." + extensao;
            Path caminhoArquivo = pastaProcessada.resolve(nomeArquivo);

            if (!Files.exists(caminhoArquivo)) {
                String conteudo = extensao.equals("csv")
                    ? "id,situacao_atual_id,data_criacao\n" + processo.getId() + "," + processo.getSituacaoAtualId() + "," + processo.getDataCriacao()
                    : "{\"type\": \"FeatureCollection\", \"features\": []}";
                Files.writeString(caminhoArquivo, conteudo);
            }

            Resource recurso = new UrlResource(caminhoArquivo.toUri());
            if (recurso.exists() && recurso.isReadable()) {
                return recurso;
            } else {
                throw new RuntimeException("Não foi possível ler o arquivo exportado.");
            }
        } catch (Exception e) {
            throw new RuntimeException("Erro ao gerar exportação para o processo ID: " + id, e);
        }
    }

    private Processo salvarGarantindoCampos(Processo processo) {
        if (processo.getDataCriacao() == null) {
            processo.setDataCriacao(LocalDateTime.now());
        }
        if (processo.getAnoSafra() == null) {
            processo.setAnoSafra(String.valueOf(LocalDateTime.now().getYear()));
        }
        return processoRepository.save(processo);
    }
}