package com.matiasmeira.sacaladelangulo.empleado.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.empleado.dto.EmpleadoRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractPostgresIntegrationTest;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica el índice único parcial {@code uk_empleado_activo_por_nombre_y_establecimiento}
 * (V23) contra un Postgres real. Protege el invariante del que depende el login de mostrador:
 * {@code AuthService.authenticateEmpleado} resuelve el nombre tocado en pantalla con un finder
 * que devuelve {@code Optional} (uno solo); si dos empleados ACTIVOS del mismo establecimiento
 * comparten nombre, ese finder recibe dos filas y Spring Data corta con
 * {@code IncorrectResultSizeDataAccessException} — un 500 permanente en el mostrador, no un
 * error puntual. H2 (la suite rápida con {@code ddl-auto=create-drop}) no tiene este índice, y
 * los tests de {@code EmpleadoService} son 100% Mockito: hoy esta protección no se ejercita en
 * ningún entorno. Requiere Docker. Ver {@link AbstractPostgresIntegrationTest}.
 *
 * <p><b>Por qué unos tests van directo al repositorio y otros a través del servicio:</b>
 * {@code EmpleadoService.crearEmpleado} ya valida la unicidad antes de persistir
 * ({@code existsByEstablecimientoIdAndNombreIgnoreCaseAndRolAndIsActiveTrue}), así que llamarlo
 * en un test secuencial nunca llega a ejercitar el índice: el guard de aplicación corta antes.
 * Los casos no concurrentes de acá escriben DIRECTO contra {@link UsuarioRepository}, salteando
 * ese guard a propósito — mismo patrón que
 * {@code ReservaExclusionConstraintIntegrationTest} — porque lo que se quiere probar es el
 * índice, no el service. El único caso que sí pasa por {@link EmpleadoService} es el
 * concurrente: el guard es un check-then-act no atómico, y sólo dos hilos reales compitiendo
 * pueden hacer que ambos lo pasen antes de que cualquiera persista — ahí es donde el índice deja
 * de ser redundante y se vuelve la única protección real.
 */
@Tag("testcontainers")
@DisplayName("uk_empleado_activo_por_nombre_y_establecimiento - Postgres real (Testcontainers)")
class EmpleadoActivoUnicoPorNombreConstraintIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private EstablecimientoRepository establecimientoRepository;
    @Autowired
    private EmpleadoService empleadoService;

    private Usuario dueno;
    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno-empleado-unico-" + UUID.randomUUID() + "@test.com")
                .password("hash")
                .nombre("Dueño")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .unsubscribeToken("tok-" + UUID.randomUUID())
                .build());

        establecimiento = establecimientoRepository.save(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Club Empleado Único")
                .direccion("Calle Falsa 789")
                .slug("club-empleado-unico-" + UUID.randomUUID())
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));
    }

    /** Empleado ACTIVO tal como lo arma EmpleadoService.crearEmpleado, escrito directo al repositorio. */
    private Usuario empleadoActivo(Establecimiento est, String nombre) {
        return Usuario.builder()
                .email("empleado-" + UUID.randomUUID() + "@empleados.sacaladelangulo.interno")
                .password("hash-pin")
                .nombre(nombre)
                .rol(Role.EMPLOYEE)
                .planSuscripcion(PlanSuscripcion.FREE)
                .establecimiento(est)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build();
    }

    @Test
    @DisplayName("dosEmpleadosActivosMismoNombreEnElMismoEstablecimiento_LaBaseRechazaLaSegunda")
    void dosEmpleadosActivosMismoNombreEnElMismoEstablecimiento_LaBaseRechazaLaSegunda() {
        // Invariante: el login de mostrador no puede tener dos empleados activos homónimos en
        // el mismo establecimiento (rompería el finder Optional de AuthService.authenticateEmpleado).
        usuarioRepository.saveAndFlush(empleadoActivo(establecimiento, "Juan"));

        Usuario segundoJuan = empleadoActivo(establecimiento, "Juan");
        assertThrows(DataIntegrityViolationException.class,
                () -> usuarioRepository.saveAndFlush(segundoJuan),
                "La base debe rechazar un segundo empleado ACTIVO homónimo en el mismo establecimiento");
    }

    @Test
    @DisplayName("mismoNombreConDistintaCapitalizacion_TambienRechazado")
    void mismoNombreConDistintaCapitalizacion_TambienRechazado() {
        // El índice es sobre lower(nombre), igual que el guard IgnoreCase del service y el
        // finder de login: "Juan" y "JUAN" tienen que chocar igual que "Juan" y "Juan".
        usuarioRepository.saveAndFlush(empleadoActivo(establecimiento, "Juan"));

        Usuario mayusculas = empleadoActivo(establecimiento, "JUAN");
        assertThrows(DataIntegrityViolationException.class,
                () -> usuarioRepository.saveAndFlush(mayusculas),
                "El índice normaliza a minúsculas: distinta capitalización no debe esquivar la constraint");
    }

    @Test
    @DisplayName("mismoNombreEnEstablecimientosDistintos_Permitido")
    void mismoNombreEnEstablecimientosDistintos_Permitido() {
        // El nombre de un empleado solo tiene que ser único DENTRO de su establecimiento: dos
        // complejos distintos pueden tener cada uno su propio "Juan" sin pisarse.
        Establecimiento otroEstablecimiento = establecimientoRepository.save(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Otro club")
                .direccion("Otra calle")
                .slug("otro-club-empleado-unico-" + UUID.randomUUID())
                .latitud(-1.0)
                .longitud(-1.0)
                .requiereSena(false)
                .dueno(dueno)));

        usuarioRepository.saveAndFlush(empleadoActivo(establecimiento, "Juan"));

        assertDoesNotThrow(() -> usuarioRepository.saveAndFlush(empleadoActivo(otroEstablecimiento, "Juan")),
                "El mismo nombre en establecimientos distintos no debe chocar: el índice incluye establecimiento_id");
    }

    @Test
    @DisplayName("mismoNombreCuandoElPrimeroEstaInactivo_Permitido")
    void mismoNombreCuandoElPrimeroEstaInactivo_Permitido() {
        // El nombre de un empleado dado de baja tiene que quedar libre para reutilizarlo (ver
        // el comentario de existsBy...AndIsActiveTrue en EmpleadoService): el índice es a
        // propósito solo sobre activos.
        Usuario primerJuan = empleadoActivo(establecimiento, "Juan");
        primerJuan.setIsActive(false);
        usuarioRepository.saveAndFlush(primerJuan);

        assertDoesNotThrow(() -> usuarioRepository.saveAndFlush(empleadoActivo(establecimiento, "Juan")),
                "Un homónimo inactivo no debe bloquear el alta de un nuevo empleado activo con ese nombre");
    }

    @Test
    @DisplayName("mismoNombreCuandoUnoNoEsEmpleado_Permitido")
    void mismoNombreCuandoUnoNoEsEmpleado_Permitido() {
        // El índice filtra por rol='EMPLOYEE': si alguna vez esa condición se saca del WHERE,
        // este test lo agarra. Un OWNER (o cualquier otro rol) con el mismo nombre y el mismo
        // establecimiento_id nunca debería competir con el nombre de un empleado.
        usuarioRepository.saveAndFlush(empleadoActivo(establecimiento, "Juan"));

        Usuario duenoHomonimo = Usuario.builder()
                .email("owner-homonimo-" + UUID.randomUUID() + "@test.com")
                .password("hash")
                .nombre("Juan")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .establecimiento(establecimiento)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .unsubscribeToken("tok-" + UUID.randomUUID())
                .build();

        assertDoesNotThrow(() -> usuarioRepository.saveAndFlush(duenoHomonimo),
                "Un usuario que no es EMPLOYEE no debe chocar con el nombre de un empleado, aunque comparta establecimiento_id");
    }

    @Test
    @DisplayName("dosAltasSimultaneasConElMismoNombre_SoloUnaGana")
    void dosAltasSimultaneasConElMismoNombre_SoloUnaGana() throws Exception {
        // El guard de EmpleadoService.crearEmpleado (existsBy...AndIsActiveTrue) es
        // check-then-act y NO es atómico: esta es la carrera real que el índice viene a cerrar.
        //
        // A diferencia de V24 (ver TurnoFijoRenovadoDesdeConstraintIntegrationTest),
        // EmpleadoService SÍ traduce la violación de la constraint: el catch de
        // DataIntegrityViolationException dentro de crearEmpleado la convierte en el mismo
        // IllegalArgumentException de negocio que tira el guard. Por eso el hilo perdedor de
        // esta carrera recibe SIEMPRE IllegalArgumentException, gane la carrera el guard o la
        // constraint — no hace falta (ni corresponde) capturar DataIntegrityViolationException acá.
        EmpleadoRequest request = new EmpleadoRequest("Juan", "1470", null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Callable<Boolean> crear = () -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                empleadoService.crearEmpleado(establecimiento.getId(), request, dueno.getEmail());
                return true;
            } catch (IllegalArgumentException ex) {
                return false;
            }
        };

        List<Future<Boolean>> futures = pool.invokeAll(List.of(crear, crear));
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long exitos = futures.stream().filter(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).count();

        assertEquals(1, exitos, "Solo una de las dos altas concurrentes con el mismo nombre debe haber ganado");
        assertEquals(1, usuarioRepository.findByEstablecimientoIdAndRolAndIsActiveTrue(establecimiento.getId(), Role.EMPLOYEE)
                        .stream().filter(u -> u.getNombre().equalsIgnoreCase("Juan")).count(),
                "Debe existir un único empleado activo llamado Juan en el establecimiento");
    }
}
