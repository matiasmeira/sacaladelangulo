-- V19 — requiere_telefono_verificado en establecimientos
--
-- Permite que el dueño de un establecimiento exija que el jugador tenga el teléfono
-- verificado (usuarios.telefono_verificado) para poder reservar en sus canchas.
--
-- NOT NULL DEFAULT false: comportamiento actual sin cambios para todos los
-- establecimientos existentes; el dueño lo activa explícitamente si lo necesita.
ALTER TABLE establecimientos ADD COLUMN requiere_telefono_verificado BOOLEAN NOT NULL DEFAULT false;
