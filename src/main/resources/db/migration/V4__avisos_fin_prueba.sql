-- Agrega las banderas de aviso de fin de prueba gratuita a usuarios (ver Fase 5): una por
-- cada umbral (7, 3 y 1 día restante) para que AvisoFinPruebaService no reenvíe el mismo
-- aviso en cada corrida del job. Se agregan nullable primero, se backfillean filas
-- existentes en false y recién después se marcan NOT NULL, mismo patrón que V2, para no
-- romper si la tabla ya tiene filas en una base de dev/prod real.

ALTER TABLE usuarios ADD COLUMN aviso_fin_prueba_7_enviado BOOLEAN;
ALTER TABLE usuarios ADD COLUMN aviso_fin_prueba_3_enviado BOOLEAN;
ALTER TABLE usuarios ADD COLUMN aviso_fin_prueba_1_enviado BOOLEAN;

UPDATE usuarios
SET aviso_fin_prueba_7_enviado = false
WHERE aviso_fin_prueba_7_enviado IS NULL;

UPDATE usuarios
SET aviso_fin_prueba_3_enviado = false
WHERE aviso_fin_prueba_3_enviado IS NULL;

UPDATE usuarios
SET aviso_fin_prueba_1_enviado = false
WHERE aviso_fin_prueba_1_enviado IS NULL;

ALTER TABLE usuarios ALTER COLUMN aviso_fin_prueba_7_enviado SET NOT NULL;
ALTER TABLE usuarios ALTER COLUMN aviso_fin_prueba_3_enviado SET NOT NULL;
ALTER TABLE usuarios ALTER COLUMN aviso_fin_prueba_1_enviado SET NOT NULL;
