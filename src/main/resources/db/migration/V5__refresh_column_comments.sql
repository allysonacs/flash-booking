-- ---------------------------------------------------------------------------
-- V5 — Correção de comentários que ficaram desatualizados.
--
-- Comentários de schema são documentação que vive dentro do banco: são o que
-- alguém lê ao inspecionar a tabela em produção, sem acesso ao repositório.
-- Um comentário que descreve um plano já executado ("o job chega na próxima
-- fase") é pior do que nenhum — afirma algo falso com a autoridade do banco.
--
-- Migração sem mudança estrutural: nenhuma tabela, coluna ou índice é alterado.
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN reservations.expires_at IS
    'Prazo da reserva PENDING; a varredura de expiração aplica PENDING -> EXPIRED e devolve os assentos';

COMMENT ON COLUMN reservations.status IS
    'PENDING | CONFIRMED | CANCELLED | EXPIRED. Transições saem de PENDING por UPDATE guardado por estado';
