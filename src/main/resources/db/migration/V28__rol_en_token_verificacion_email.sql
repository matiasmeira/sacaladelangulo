-- =============================================================================
-- V28 -- Columna rol en tokens_verificacion_email
--
-- El registro en 2 pasos ahora sirve también para dueños: el rol que va a tener la
-- cuenta se guarda con el token pendiente (PLAYER u OWNER) y completarRegistro crea el
-- usuario con ese rol. Los tokens vivos al momento del deploy (TTL de 15 min) quedan
-- como PLAYER, que es lo que eran.
-- =============================================================================

ALTER TABLE tokens_verificacion_email ADD COLUMN rol VARCHAR(20) NOT NULL DEFAULT 'PLAYER';
