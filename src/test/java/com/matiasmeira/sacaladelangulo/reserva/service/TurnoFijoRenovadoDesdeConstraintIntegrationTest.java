package com.matiasmeira.sacaladelangulo.reserva.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoTurnoFijo;
import com.matiasmeira.sacaladelangulo.reserva.model.TurnoFijo;
import com.matiasmeira.sacaladelangulo.reserva.repository.TurnoFijoRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractPostgresIntegrationTest;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
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
 * Verifica el índice único parcial {@code uk_turnos_fijos_renovado_desde} (V24) contra un
 * Postgres real. Protege el invariante de que una serie se renueva UNA sola vez: sin él, dos
 * clicks seguidos en "Renovar" (o dos pestañas del dueño renovando a la vez) crean dos series
 * paralelas para el mismo año, y la segunda recién falla por solapamiento de reservas — un
 * mensaje que no le dice nada al dueño sobre lo que realmente pasó. H2 (la suite rápida) no
 * tiene este índice; requiere Docker. Ver {@link AbstractPostgresIntegrationTest}.
 *
 * <p><b>Por qué unos tests van directo al repositorio y otros a través del servicio:</b>
 * {@code TurnoFijoService.renovar} ya valida con {@code existsByRenovadoDesdeId} antes de
 * persistir, así que un test secuencial contra el service nunca llega a ejercitar el índice. Los
 * casos no concurrentes de acá escriben DIRECTO contra {@link TurnoFijoRepository}
 * ({@code saveAndFlush}), salteando ese guard a propósito — mismo patrón que
 * {@code ReservaExclusionConstraintIntegrationTest} — porque lo que se prueba es el índice, no
 * el service. El caso concurrente sí pasa por {@link TurnoFijoService#renovar}: el guard es
 * check-then-act y sólo dos hilos reales compitiendo (el "doble click") pueden pasarlo ambos
 * antes de que cualquiera commitee.
 *
 * <p><b>Hallazgo de asimetría con V23 (documentado a propósito, no corregido — código de
 * producción fuera de alcance):</b> {@code EmpleadoService.crearEmpleado} captura
 * {@code DataIntegrityViolationException} y la traduce al mismo mensaje de negocio que su guard
 * (ver {@code EmpleadoActivoUnicoPorNombreConstraintIntegrationTest}).
 * {@code TurnoFijoService.renovar}/{@code crearInterno} NO tienen ningún catch equivalente
 * alrededor del insert de la regla. Eso significa que, en el caso concurrente real que este
 * índice existe para cubrir, el segundo click puede terminar mostrándole al dueño exactamente el
 * error crudo de base que el comentario de V24 dice querer evitar ("un mensaje que no le dice
 * nada al dueño") — el guard lo evita en el camino secuencial, pero no en el concurrente. El test
 * {@link #dosRenovacionesSimultaneasDeLaMismaSerie_SoloUnaGana()} de más abajo documenta este
 * comportamiento ACTUAL tal cual es, no lo avala: lo deseable sería que
 * {@code TurnoFijoService.renovar} tradujera esa excepción igual que hace
 * {@code EmpleadoService.crearEmpleado}, para que el día que se corrija, este test le diga a
 * quien lo toque qué esperar en vez de parecer que la asimetría era intencional.
 */
@Tag("testcontainers")
@DisplayName("uk_turnos_fijos_renovado_desde - Postgres real (Testcontainers)")
class TurnoFijoRenovadoDesdeConstraintIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final DayOfWeek DIA_SEMANA = DayOfWeek.WEDNESDAY;

    @Autowired
    private TurnoFijoRepository turnoFijoRepository;
    @Autowired
    private TurnoFijoService turnoFijoService;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private EstablecimientoRepository establecimientoRepository;
    @Autowired
    private CanchaRepository canchaRepository;

    private Usuario dueno;
    private Cancha cancha;

    @BeforeEach
    void setUp() {
        dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno-turno-fijo-renovado-" + UUID.randomUUID() + "@test.com")
                .password("hash")
                .nombre("Dueño")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .unsubscribeToken("tok-" + UUID.randomUUID())
                .build());

        Establecimiento establecimiento = Establecimientos.establecimientoOperativo(b -> b
                .nombre("Club Turno Fijo")
                .direccion("Calle Falsa 321")
                .slug("club-turno-fijo-renovado-" + UUID.randomUUID())
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno));
        // Abierto todo el día ese único día de la semana: alcanza para las ~52 ocurrencias
        // semanales que genera una renovación anual, sin que el horario de atención sea la
        // variable que hace fallar el test.
        establecimiento.setHorariosAtencion(List.of(HorarioAtencion.builder()
                .diaSemana(DIA_SEMANA)
                .horaApertura(LocalTime.MIDNIGHT)
                .horaCierre(LocalTime.of(23, 59))
                .establecimiento(establecimiento)
                .build()));
        establecimiento = establecimientoRepository.save(establecimiento);

        cancha = canchaRepository.save(Cancha.builder()
                .nombre("Cancha Turno Fijo")
                .deportes(new HashSet<>(List.of(Deporte.FUTBOL_5)))
                .precioBase(BigDecimal.valueOf(1000))
                .montoSena(BigDecimal.ZERO)
                .duracionesPermitidas(new ArrayList<>(List.of(60)))
                .permiteInicioMediaHora(true)
                .establecimiento(establecimiento)
                .isActive(true)
                .build());
    }

    /** Regla de turno fijo tal como la arma TurnoFijoService.crearInterno, escrita directo al repositorio. */
    private TurnoFijo turnoFijoActivo(Long renovadoDesdeId) {
        LocalDate inicioPeriodo = LocalDate.now().withDayOfYear(1);
        return turnoFijoRepository.saveAndFlush(TurnoFijo.builder()
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .diaSemana(DIA_SEMANA)
                .horaInicio(LocalTime.of(10, 0))
                .horaFin(LocalTime.of(11, 0))
                .fechaInicioPeriodo(inicioPeriodo)
                .fechaFinPeriodo(inicioPeriodo.withDayOfYear(inicioPeriodo.lengthOfYear()))
                .nombreClienteManual("Cliente de prueba")
                .telefonoClienteManual("111")
                .estado(EstadoTurnoFijo.ACTIVO)
                .renovadoDesdeId(renovadoDesdeId)
                .fechaCreacion(LocalDateTime.now())
                .build());
    }

    @Test
    @DisplayName("dosRenovacionesDeLaMismaSerie_LaSegundaEsRechazada")
    void dosRenovacionesDeLaMismaSerie_LaSegundaEsRechazada() {
        // Invariante: una serie se renueva UNA sola vez. Dos filas con el mismo
        // renovado_desde_id significarían dos series paralelas "renovación de la misma serie
        // original", que es justamente el estado que el índice existe para impedir.
        TurnoFijo original = turnoFijoActivo(null);
        turnoFijoActivo(original.getId());

        assertThrows(DataIntegrityViolationException.class,
                () -> turnoFijoActivo(original.getId()),
                "La base debe rechazar una segunda renovación de la misma serie original");
    }

    @Test
    @DisplayName("renovarSeriesDistintas_Permitido")
    void renovarSeriesDistintas_Permitido() {
        // El índice es sobre el valor de renovado_desde_id, no sobre "que exista una
        // renovación": renovar dos series ORIGINALES distintas no debe interferir entre sí.
        TurnoFijo originalUno = turnoFijoActivo(null);
        TurnoFijo originalDos = turnoFijoActivo(null);

        assertDoesNotThrow(() -> {
            turnoFijoActivo(originalUno.getId());
            turnoFijoActivo(originalDos.getId());
        }, "Renovar series originales distintas debe ser independiente");
    }

    @Test
    @DisplayName("variosRegistrosConRenovadoDesdeIdNulo_ConvivenSinProblema")
    void variosRegistrosConRenovadoDesdeIdNulo_ConvivenSinProblema() {
        // Es un índice PARCIAL (WHERE renovado_desde_id IS NOT NULL): la enorme mayoría de las
        // series NUNCA se renovaron y todas comparten NULL en esa columna. Si el WHERE estuviera
        // mal (por ejemplo, si faltara y el índice fuera sobre toda la tabla), un UNIQUE
        // corriente trataría todos esos NULL como valores a comparar entre sí en algunos
        // motores, o en el mejor de los casos este test simplemente estaría probando lo obvio;
        // en Postgres los NULL de un UNIQUE ya se consideran distintos entre sí, así que lo que
        // este test fija es que la intención (WHERE explícito) siga siendo la que documenta V24.
        assertDoesNotThrow(() -> {
            turnoFijoActivo(null);
            turnoFijoActivo(null);
            turnoFijoActivo(null);
        }, "Varias series nunca renovadas (renovado_desde_id NULL) deben convivir sin chocar");
    }

    @Test
    @DisplayName("dosRenovacionesSimultaneasDeLaMismaSerie_SoloUnaGana")
    void dosRenovacionesSimultaneasDeLaMismaSerie_SoloUnaGana() throws Exception {
        // El "doble click en Renovar" real: dos hilos renovando la misma serie a la vez. El
        // guard existsByRenovadoDesdeId de TurnoFijoService.renovar es check-then-act y no es
        // atómico, así que ambos pueden pasarlo antes de que cualquiera commitee.
        //
        // Se acepta CUALQUIERA de las dos excepciones en el hilo perdedor, y es importante
        // notar que NO es el mismo par de excepciones que en el test análogo de V23
        // (EmpleadoActivoUnicoPorNombreConstraintIntegrationTest):
        //   - IllegalArgumentException: el guard alcanzó a ver la renovación del otro hilo ya
        //     commiteada ("ya fue renovado"), mensaje de negocio normal.
        //   - DataIntegrityViolationException: ambos hilos pasaron el guard antes de que
        //     cualquiera commiteara, y el índice de V24 frenó al segundo insert de la regla SIN
        //     que ningún catch en TurnoFijoService la traduzca (ver el javadoc de la clase).
        //     Aceptarla acá documenta el comportamiento actual; NO significa que un 500/error
        //     crudo llegando al dueño en ese momento sea el resultado deseable.
        // Cualquiera de las dos formas, el dato queda íntegro: una sola renovación persistida.
        TurnoFijo original = turnoFijoActivo(null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Callable<Boolean> renovar = () -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                turnoFijoService.renovar(original.getId(), dueno.getEmail());
                return true;
            } catch (IllegalArgumentException | DataIntegrityViolationException ex) {
                return false;
            }
        };

        List<Future<Boolean>> futures = pool.invokeAll(List.of(renovar, renovar));
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        long exitos = futures.stream().filter(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).count();

        assertEquals(1, exitos, "Solo una de las dos renovaciones concurrentes de la misma serie debe haber ganado");
        assertEquals(1, turnoFijoRepository.findAll().stream()
                        .filter(t -> original.getId().equals(t.getRenovadoDesdeId()))
                        .count(),
                "Debe existir una única serie persistida como renovación de la serie original");
    }
}
