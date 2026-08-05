-- Hashea los tokens/códigos de verificación de email y recuperación de contraseña (ver
-- M-05 en la auditoría): antes se persistían en texto plano; de acá en más solo su hash
-- SHA-256, mismo criterio que dispositivo_caja.token_hash. Se renombran las columnas para
-- que el nombre delate que ya no contienen el valor crudo.
--
-- Los tokens/códigos vivos al momento del deploy (TTL de 5-15 min) quedan efectivamente
-- invalidados: el valor que el usuario tiene en su email es el crudo, pero la app ahora
-- compara su hash contra lo que quedó en estas columnas (el valor crudo viejo, no su
-- hash). El usuario simplemente vuelve a pedir el link/código, que de cualquier forma ya
-- iba a expirar en minutos.
ALTER TABLE tokens_verificacion_email RENAME COLUMN token TO token_hash;
ALTER TABLE tokens_verificacion_email RENAME COLUMN codigo TO codigo_hash;
ALTER TABLE tokens_recuperacion_password RENAME COLUMN token TO token_hash;
ALTER TABLE tokens_recuperacion_password RENAME COLUMN codigo TO codigo_hash;
ALTER TABLE codigos_verificacion RENAME COLUMN codigo TO codigo_hash;
