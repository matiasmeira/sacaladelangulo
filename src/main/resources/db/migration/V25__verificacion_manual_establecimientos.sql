-- V25 — verificación manual de establecimientos
--
-- Agrega el estado de verificación manual (PENDIENTE / EN_REVISION / VERIFICADO / RECHAZADO)
-- que EstablecimientoOperativoGuard suma a isActive para decidir si un establecimiento puede
-- operar de cara al público. Los endpoints de solicitud/aprobación/rechazo son de otro
-- prompt: esta migración sólo prepara el modelo.
--
-- El DEFAULT de la columna queda en 'PENDIENTE' porque así nace todo establecimiento nuevo
-- de acá en más (el dueño recién lo verifica después de crearlo). Pero los establecimientos
-- YA existentes son datos de prueba, no una cola real de verificación pendiente: si se
-- quedaran en PENDIENTE, este mismo deploy los dejaría de golpe fuera de operación (guard
-- bloqueado) sin que nadie haya tocado nada. Por eso el UPDATE de abajo los pasa a
-- VERIFICADO explícitamente, inmediatamente después del ADD COLUMN -- incluidos usando el
-- mismo patrón de V19 (ADD COLUMN NOT NULL con DEFAULT en un solo paso).
ALTER TABLE establecimientos ADD COLUMN estado_verificacion VARCHAR(255) NOT NULL DEFAULT 'PENDIENTE';
UPDATE establecimientos SET estado_verificacion = 'VERIFICADO';

ALTER TABLE establecimientos ADD COLUMN cuit VARCHAR(11);
ALTER TABLE establecimientos ADD COLUMN razon_social VARCHAR(255);
ALTER TABLE establecimientos ADD COLUMN telefono_contacto VARCHAR(255);
ALTER TABLE establecimientos ADD COLUMN url_red_social VARCHAR(255);
ALTER TABLE establecimientos ADD COLUMN fecha_solicitud_verificacion TIMESTAMP;
ALTER TABLE establecimientos ADD COLUMN fecha_verificacion TIMESTAMP;
ALTER TABLE establecimientos ADD COLUMN verificado_por_id BIGINT;
ALTER TABLE establecimientos ADD COLUMN motivo_rechazo VARCHAR(255);

ALTER TABLE establecimientos ADD CONSTRAINT fk_establecimientos_verificado_por
    FOREIGN KEY (verificado_por_id) REFERENCES usuarios (id);
