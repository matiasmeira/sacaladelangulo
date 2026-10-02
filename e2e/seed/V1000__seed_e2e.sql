-- Seed del perfil e2e (sólo se aplica contra sacaladelangulo_e2e, ver application-e2e.properties).
-- Contraseña de todos los usuarios: E2e-Canche-2026!  (hash BCrypt generado con BCryptPasswordEncoder).
-- Sin reservas: cada test crea las suyas.

INSERT INTO usuarios (email, password, nombre, telefono, rol, plan_suscripcion, fecha_creacion, is_active,
                      email_verified, fecha_fin_prueba, telefono_verificado, token_version,
                      aviso_fin_prueba_7_enviado, aviso_fin_prueba_3_enviado, aviso_fin_prueba_1_enviado,
                      acepta_marketing, unsubscribe_token)
VALUES
    ('dueno.e2e@canche.test', '$2a$10$ACKmBNByH5Lkx48iaDQ0puUnGVW/EAW6lk7rwevkPYQT7QEdpykX.', 'Dueño E2E', '1155550001',
     'OWNER', 'TRIAL', now(), true, true, now() + interval '30 days', true, 0, false, false, false, false,
     gen_random_uuid()::text),
    ('dueno.vacio.e2e@canche.test', '$2a$10$ACKmBNByH5Lkx48iaDQ0puUnGVW/EAW6lk7rwevkPYQT7QEdpykX.', 'Dueño Vacío E2E', '1155550002',
     'OWNER', 'TRIAL', now(), true, true, now() + interval '30 days', true, 0, false, false, false, false,
     gen_random_uuid()::text),
    ('admin.e2e@canche.test', '$2a$10$ACKmBNByH5Lkx48iaDQ0puUnGVW/EAW6lk7rwevkPYQT7QEdpykX.', 'Admin E2E', '1155550003',
     'ADMIN', NULL, now(), true, true, NULL, true, 0, false, false, false, false,
     gen_random_uuid()::text),
    ('jugador.e2e@canche.test', '$2a$10$ACKmBNByH5Lkx48iaDQ0puUnGVW/EAW6lk7rwevkPYQT7QEdpykX.', 'Jugador E2E', '1155550004',
     'PLAYER', 'FREE', now(), true, true, NULL, true, 0, false, false, false, false,
     gen_random_uuid()::text);

INSERT INTO establecimientos (nombre, direccion, latitud, longitud, requiere_sena, is_active,
                              horas_cancelacion_antes_partido, minutos_gracia_cancelacion, dueno_id, slug,
                              requiere_telefono_verificado, estado_verificacion, cuit, razon_social,
                              telefono_contacto, fecha_solicitud_verificacion, fecha_verificacion, verificado_por_id)
SELECT v.nombre, 'Av. Siempreviva 742, Buenos Aires', -34.6037, -58.3816, v.requiere_sena, true,
       24, 30, d.id, v.slug, false, 'VERIFICADO', '20123456786', v.nombre, '1155550001',
       now() - interval '2 days', now() - interval '1 day', a.id
FROM (VALUES
        ('Complejo E2E Sin Seña', 'e2e-sin-sena', false, 'dueno.e2e@canche.test'),
        ('Complejo E2E Con Seña', 'e2e-con-sena', true, 'dueno.e2e@canche.test'),
        ('Complejo E2E Sin Canchas', 'e2e-sin-canchas', false, 'dueno.vacio.e2e@canche.test')
     ) AS v(nombre, slug, requiere_sena, email_dueno)
JOIN usuarios d ON d.email = v.email_dueno
JOIN usuarios a ON a.email = 'admin.e2e@canche.test';

-- Horarios 08:00-23:00 todos los días.
INSERT INTO horarios_atencion (dia_semana, hora_apertura, hora_cierre, establecimiento_id)
SELECT dia, time '08:00', time '23:00', e.id
FROM establecimientos e
CROSS JOIN unnest(ARRAY['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']) AS dia;

-- Dos canchas activas por complejo con canchas (precio base 12000 la hora; seña 500 sólo en el complejo con seña).
INSERT INTO canchas (nombre, is_active, canchas_necesarias, precio_base, monto_sena, establecimiento_id, permite_inicio_media_hora)
SELECT c.nombre, true, 1, 12000, CASE WHEN e.requiere_sena THEN 500 ELSE 0 END, e.id, true
FROM establecimientos e
CROSS JOIN (VALUES ('Cancha 1'), ('Cancha 2')) AS c(nombre)
WHERE e.slug IN ('e2e-sin-sena', 'e2e-con-sena');

INSERT INTO cancha_deportes (cancha_id, deporte)
SELECT id, 'PADEL' FROM canchas;

INSERT INTO cancha_duraciones (cancha_id, duracion_minutos)
SELECT c.id, d FROM canchas c CROSS JOIN (VALUES (60), (90), (120)) AS x(d);
