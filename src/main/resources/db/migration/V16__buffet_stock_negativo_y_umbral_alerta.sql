-- =============================================================================
-- BUFFET — el stock pasa a ser informativo, y cada producto define su umbral
-- de alerta.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. El stock puede quedar negativo.
--
-- V12 agregó chk_productos_buffet_stock_no_negativo para respaldar la resta de
-- VentaService. En la práctica esa restricción hacía lo contrario de lo que el
-- negocio necesita: bloqueaba una venta REAL, ya cobrada en el mostrador,
-- porque el inventario del sistema estaba desactualizado. El cajero veía un
-- "conflicto de integridad" y no podía cerrar la venta.
--
-- La decisión es que el stock es un dato informativo que puede quedar
-- desfasado, no una condición para vender: si un producto se vendió, se
-- registra; que el número quede en negativo es justamente la señal de que hay
-- que reponer o corregir la carga.
--
-- El precio SÍ mantiene su restricción: un precio negativo no tiene lectura
-- posible y siempre es un error de carga.
-- -----------------------------------------------------------------------------
ALTER TABLE productos_buffet
    DROP CONSTRAINT IF EXISTS chk_productos_buffet_stock_no_negativo;

-- -----------------------------------------------------------------------------
-- 2. Umbral de alerta por producto.
--
-- Hasta ahora el aviso de "stock bajo" era un número fijo en el frontend, igual
-- para todos los productos. No sirve: no se repone igual una caja de agua que
-- se vende de a decenas que un producto que rota una vez por semana.
--
-- DEFAULT 5 para las filas existentes, que es el umbral fijo que venía usando
-- el frontend: así el comportamiento no cambia hasta que el dueño lo ajuste.
-- -----------------------------------------------------------------------------
ALTER TABLE productos_buffet
    ADD COLUMN umbral_alerta INTEGER NOT NULL DEFAULT 5;

ALTER TABLE productos_buffet
    ADD CONSTRAINT chk_productos_buffet_umbral_no_negativo CHECK (umbral_alerta >= 0);
