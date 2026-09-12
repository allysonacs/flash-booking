-- ---------------------------------------------------------------------------
-- V6 — A confirmação passa a existir; os comentários do schema acompanham.
--
-- A transição PENDING -> CONFIRMED era, até aqui, apenas documentada: o valor
-- já constava na constraint ck_reservations_status, mas nenhum caminho do
-- código o alcançava. Agora alcança, e o que o banco diz sobre as próprias
-- colunas precisa dizer o que o sistema realmente faz — é o que alguém lê ao
-- inspecionar a tabela em produção, sem acesso ao repositório.
--
-- Vale registrar aqui o que a constraint não consegue expressar: a confirmação
-- é a única transição que NÃO mexe em event_inventory. Os assentos já foram
-- comprometidos na criação da reserva; confirmar apenas impede que voltem.
--
-- Migração sem mudança estrutural: nenhuma tabela, coluna ou índice é alterado.
-- Nenhum dado precisa ser migrado — nunca existiu linha em CONFIRMED.
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN reservations.status IS
    'PENDING | CONFIRMED | CANCELLED | EXPIRED. Transições saem de PENDING por UPDATE guardado por estado; só CANCELLED e EXPIRED devolvem assentos';

COMMENT ON COLUMN reservations.expires_at IS
    'Prazo da reserva PENDING: a confirmação só é aceita antes dele, e a varredura de expiração aplica PENDING -> EXPIRED depois dele, devolvendo os assentos';
