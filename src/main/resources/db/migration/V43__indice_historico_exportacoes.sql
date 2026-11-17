-- V43: US11 Tarefa 13 — índice do histórico de exportações
--
-- O GET /exportacoes/historico lista o log_exportacoes_relatorios (V36) do mais recente pro mais
-- antigo, quase sempre sem filtrar por usuário (é a tela de auditoria). O índice da V36 começa
-- por usuario_id, então não serve pra essa ordenação; este aqui deixa a página sair direto do
-- índice, sem ordenar a tabela inteira a cada consulta.

CREATE INDEX IF NOT EXISTS idx_log_exportacoes_data
    ON log_exportacoes_relatorios (data_hora_exportacao DESC, id DESC);
