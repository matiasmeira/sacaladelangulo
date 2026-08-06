-- =============================================================================
-- V12 — CHECK de montos: respaldo en la base de invariantes que hoy solo vivían
--       en la validación de Java.
--
-- CRITERIO: cada CHECK ESPEJA EXACTAMENTE una validación que ya existe en el
-- código. No se inventa ninguna regla de negocio nueva. Si la app ya rechaza el
-- valor, el CHECK solo impide que entre por un camino que no pase por la app
-- (corrección manual, script de import, endpoint futuro sin validar).
--
-- Deliberadamente NO se agrega: `sena_pagada <= precio_total`. Parece obvio,
-- pero no está validado en Java y no encontré la regla escrita en ningún lado —
-- podría existir un caso legítimo (ajuste, propina, recargo) que lo rompa. Un
-- CHECK mal calibrado tira producción abajo por un caso de negocio válido que no
-- anticipé. Queda para definir con el negocio a la vista.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- GASTOS — espeja GastoRequest:
--     @DecimalMin(value = "0.0", inclusive = false, message = "El monto debe ser mayor a 0")
-- Estrictamente mayor a cero.
-- -----------------------------------------------------------------------------
ALTER TABLE gastos
    ADD CONSTRAINT chk_gastos_monto_positivo CHECK (monto > 0);

-- -----------------------------------------------------------------------------
-- PRODUCTOS DE BUFFET — espeja ProductoBuffetRequest:
--     @Min(value = 0, message = "El precio no puede ser negativo")
--     @Min(value = 0, message = "El stock no puede ser negativo")
-- Cero permitido (producto gratis / sin stock), negativo no.
--
-- El de stock además respalda la resta de stock en VentaService, que corre bajo
-- lock pesimista: si algún camino futuro descontara de más, la base lo frena en
-- vez de dejar el inventario en negativo silenciosamente.
-- -----------------------------------------------------------------------------
ALTER TABLE productos_buffet
    ADD CONSTRAINT chk_productos_buffet_precio_no_negativo CHECK (precio >= 0);

ALTER TABLE productos_buffet
    ADD CONSTRAINT chk_productos_buffet_stock_no_negativo CHECK (stock >= 0);

-- -----------------------------------------------------------------------------
-- DETALLES DE VENTA — espeja DetalleVentaRequest:
--     @Min(value = 1, message = "La cantidad debe ser mayor a 0")
-- -----------------------------------------------------------------------------
ALTER TABLE detalles_venta
    ADD CONSTRAINT chk_detalles_venta_cantidad_positiva CHECK (cantidad >= 1);

-- -----------------------------------------------------------------------------
-- RESERVAS — acá NO hay bean validation que espejar: el precio lo calcula el
-- servidor (PrecioReservaCalculator), no llega del cliente. Se usa el invariante
-- mínimo indiscutible: el dinero no puede ser negativo.
--
-- Se permite CERO a propósito: una reserva de cortesía / promoción con
-- precio_total = 0 es un caso de negocio plausible, y sena_pagada = 0 es
-- directamente el valor por defecto de toda reserva nueva (ver
-- Reserva.prePersist).
-- -----------------------------------------------------------------------------
ALTER TABLE reservas
    ADD CONSTRAINT chk_reservas_precio_no_negativo CHECK (precio_total >= 0);

ALTER TABLE reservas
    ADD CONSTRAINT chk_reservas_sena_no_negativa CHECK (sena_pagada >= 0);

-- -----------------------------------------------------------------------------
-- COHERENCIA TEMPORAL — una reserva no puede terminar antes de empezar.
-- Espeja validarFechas() en ReservaService, que ya lo rechaza.
-- -----------------------------------------------------------------------------
ALTER TABLE reservas
    ADD CONSTRAINT chk_reservas_rango_temporal_valido
    CHECK (fecha_hora_fin > fecha_hora_inicio);
-- =============================================================================
