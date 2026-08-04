ALTER TABLE ventas_buffet ADD COLUMN metodo_pago VARCHAR(255);
UPDATE ventas_buffet SET metodo_pago = 'EFECTIVO' WHERE metodo_pago IS NULL;
ALTER TABLE ventas_buffet ALTER COLUMN metodo_pago SET NOT NULL;

ALTER TABLE gastos ADD COLUMN metodo_pago VARCHAR(255);
UPDATE gastos SET metodo_pago = 'EFECTIVO' WHERE metodo_pago IS NULL;
ALTER TABLE gastos ALTER COLUMN metodo_pago SET NOT NULL;
