-- Agrega el código de 6 dígitos y el contador de intentos al token de verificación de
-- registro (ver Fase 1: además del link, el usuario puede tipear el código a mano).
-- Se agregan nullable primero, se backfillean filas existentes con un placeholder y recién
-- después se marcan NOT NULL, para no romper si la tabla ya tiene filas en una base
-- de dev/prod real. Cualquier token pre-existente que quede con un código placeholder
-- simplemente fallará la verificación por código (y el usuario puede pedir uno nuevo); su
-- flujo por link (token) no se ve afectado.

ALTER TABLE tokens_verificacion_email ADD COLUMN codigo VARCHAR(255);
ALTER TABLE tokens_verificacion_email ADD COLUMN intentos INTEGER;

UPDATE tokens_verificacion_email
SET codigo = LPAD(CAST(FLOOR(RANDOM() * 1000000) AS TEXT), 6, '0')
WHERE codigo IS NULL;

UPDATE tokens_verificacion_email
SET intentos = 0
WHERE intentos IS NULL;

ALTER TABLE tokens_verificacion_email ALTER COLUMN codigo SET NOT NULL;
ALTER TABLE tokens_verificacion_email ALTER COLUMN intentos SET NOT NULL;
