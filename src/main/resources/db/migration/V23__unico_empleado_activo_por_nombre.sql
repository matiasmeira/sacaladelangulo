-- =============================================================================
-- V23 — Un solo empleado ACTIVO por nombre dentro de cada establecimiento.
--
-- CONTEXTO: EmpleadoService.crearEmpleado valida la unicidad con
-- existsByEstablecimientoIdAndNombreIgnoreCaseAndRolAndIsActiveTrue, y
-- AuthService.authenticateEmpleado resuelve el login de mostrador con el finder
-- espejo (findBy...AndIsActiveTrue, que devuelve Optional). O sea que "un solo
-- empleado activo con ese nombre por establecimiento" ya es un invariante del
-- que depende el login: si se rompe, el finder recibe 2 filas y Spring Data corta
-- con IncorrectResultSizeDataAccessException — un 500 permanente en el mostrador.
--
-- El guard del service es un check-then-act y NO es atómico: dos altas casi
-- simultáneas del mismo nombre pueden pasar ambas el existsBy... antes de que
-- cualquiera persista. Este índice lo cierra en la base, igual que
-- uk_usuarios_email hace con el registro (ver AuthService.registerOwner).
-- EmpleadoService traduce la violación al mismo mensaje de negocio que el guard.
--
-- ALCANCE: sólo empleados ACTIVOS, a propósito. El nombre de un empleado dado de
-- baja tiene que seguir quedando libre para reutilizarlo (ver B18 en la auditoría
-- y el comentario de existsBy...AndIsActiveTrue): incluir a los desactivados haría
-- el índice MÁS restrictivo que la app y rechazaría altas que hoy son válidas.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- PASO 1 — Reparación de datos preexistentes.
--
-- Un CREATE UNIQUE INDEX falla si los datos actuales ya lo violan, y eso abortaría
-- el deploy. Para saber ANTES si hay filas afectadas:
--
--   SELECT establecimiento_id, lower(nombre), count(*), array_agg(id)
--   FROM usuarios
--   WHERE rol = 'EMPLOYEE' AND is_active
--   GROUP BY establecimiento_id, lower(nombre)
--   HAVING count(*) > 1;
--
-- Si devuelve filas, ese establecimiento YA tiene el login roto para ese nombre
-- (es el estado que este índice existe para impedir), así que desactivar los
-- duplicados no quita nada que hoy funcione: los repara. Se conserva el de id más
-- alto —el último que el dueño dio de alta, o sea el vigente— y se desactivan los
-- anteriores, que es exactamente lo que hace desactivarEmpleado.
--
-- Es idempotente y no borra nada: si no hay duplicados, no toca ninguna fila.
-- -----------------------------------------------------------------------------
UPDATE usuarios u
SET is_active = false
WHERE u.rol = 'EMPLOYEE'
  AND u.is_active
  AND EXISTS (
      SELECT 1
      FROM usuarios mas_reciente
      WHERE mas_reciente.rol = 'EMPLOYEE'
        AND mas_reciente.is_active
        AND mas_reciente.establecimiento_id = u.establecimiento_id
        AND lower(mas_reciente.nombre) = lower(u.nombre)
        AND mas_reciente.id > u.id
  );

-- -----------------------------------------------------------------------------
-- PASO 2 — El índice.
--
-- lower(nombre) y no nombre: el guard del service y el finder del login usan
-- IgnoreCase, que Spring Data traduce a lower(nombre) = lower(?). Sin lower() acá,
-- "Juan" y "juan" pasarían el índice pero seguirían chocando en el finder, que es
-- justamente el 500 que esto viene a evitar.
--
-- Parcial (WHERE) y no un UNIQUE constraint: un constraint no admite predicado ni
-- expresiones, así que no puede limitarse a los activos ni normalizar a minúsculas.
-- Mismo patrón que uk_turno_caja_abierto_por_establecimiento en V6.
--
-- establecimiento_id es NULL para todo rol que no sea EMPLOYEE, pero el predicado
-- ya deja esas filas fuera del índice, así que no hace falta contemplarlas.
-- -----------------------------------------------------------------------------
CREATE UNIQUE INDEX uk_empleado_activo_por_nombre_y_establecimiento
    ON usuarios (establecimiento_id, lower(nombre))
    WHERE rol = 'EMPLOYEE' AND is_active;
