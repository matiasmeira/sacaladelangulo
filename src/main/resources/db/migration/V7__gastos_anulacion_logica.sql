-- Anulación lógica de gastos (ver M-04 en la auditoría): eliminarGasto pasa de hacer un
-- DELETE físico a marcar is_active=false, para no perder el historial financiero ni
-- descuadrar el arqueo de caja sin dejar rastro. Las filas existentes son todas activas.
ALTER TABLE gastos ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;
