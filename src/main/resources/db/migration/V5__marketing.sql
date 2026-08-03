-- Agrega el opt-in de marketing y el token de baja a usuarios (ver Fase 6): aceptaMarketing
-- controla si un usuario recibe ofertas/broadcast (default false), y unsubscribeToken es un
-- valor único por usuario usado en el link de "darme de baja" de cada email de marketing.
-- Mismo patrón que V2/V4: se agregan nullable primero, se backfillean las filas existentes
-- y recién después se marcan NOT NULL, para no romper si la tabla ya tiene filas en una base
-- de dev/prod real.
--
-- unsubscribe_token no puede backfillearse con un único valor fijo porque va a quedar
-- UNIQUE: se genera un valor distinto por fila con md5(random()::text || id::text ||
-- clock_timestamp()::text), que no depende de ninguna extensión (a diferencia de
-- gen_random_uuid(), que en versiones de Postgres anteriores a la 13 requiere pgcrypto).

ALTER TABLE usuarios ADD COLUMN acepta_marketing BOOLEAN;
ALTER TABLE usuarios ADD COLUMN unsubscribe_token VARCHAR(255);

UPDATE usuarios
SET acepta_marketing = false
WHERE acepta_marketing IS NULL;

UPDATE usuarios
SET unsubscribe_token = md5(random()::text || id::text || clock_timestamp()::text)
WHERE unsubscribe_token IS NULL;

ALTER TABLE usuarios ALTER COLUMN acepta_marketing SET NOT NULL;
ALTER TABLE usuarios ALTER COLUMN unsubscribe_token SET NOT NULL;

ALTER TABLE usuarios ADD CONSTRAINT uk_usuarios_unsubscribe_token UNIQUE (unsubscribe_token);
