-- =============================================================================
-- V27 — Columna deleted_at en canchas (baja lógica de la cancha)
--
-- Mismo patrón que V14 (usuarios.deleted_at) y V26 (establecimientos.deleted_at):
-- columna simple, nullable, sin default, sin índice. isActive=false no alcanza
-- como discriminador de "eliminada" -- ya se usa para "desactivada por el
-- dueño" (DELETE /canchas/{id}, ver CanchaService.desactivarCancha), que es
-- reversible. deletedAt es el hecho irreversible: sin restauración, la cancha
-- deja de aparecer en cualquier vista, incluso como inactiva reactivable.
-- =============================================================================

ALTER TABLE canchas ADD COLUMN deleted_at TIMESTAMP NULL;
