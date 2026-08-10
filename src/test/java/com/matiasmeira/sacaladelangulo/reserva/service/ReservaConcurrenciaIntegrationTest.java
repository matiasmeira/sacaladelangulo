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
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaRequest;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test adversarial de concurrencia REAL (hilos + Postgres real vía Testcontainers) sobre el
 * caso más crítico del sistema: dos usuarios intentando reservar el mismo slot al mismo
 * tiempo. Un test con repositorios mockeados NO puede probar esto: el mock no serializa nada,
 * así que "gana" cualquiera de las dos llamadas según el orden en que Mockito las procese, sin
 * ejercitar el lock pesimista real (SELECT ... FOR UPDATE) que es la protección de verdad (ver
 * ReservaService.bloquearCanchasRelacionadas / CanchaRepository.lockPorIds).
 *
 * <p>Requiere Docker. Ver AbstractPostgresIntegrationTest.
 */
@Tag("testcontainers")
@DisplayName("ReservaService - Concurrencia real con Postgres (Testcontainers)")
class ReservaConcurrenciaIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ReservaService reservaService;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private EstablecimientoRepository establecimientoRepository;
    @Autowired
    private CanchaRepository canchaRepository;
    @Autowired
    private ReservaRepository reservaRepository;

    private Cancha cancha;
    private Usuario jugador1;
    private Usuario jugador2;
    private LocalDateTime inicio;
    private LocalDateTime fin;

    private static LocalDate proximaFecha(DayOfWeek diaSemana) {
        LocalDate fecha = LocalDate.now().plusDays(2);
        while (fecha.getDayOfWeek() != diaSemana) {
            fecha = fecha.plusDays(1);
        }
        return fecha;
    }

    @BeforeEach
    void setUp() {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno-concurrencia@test.com")
                .password("hash")
                .nombre("Dueño")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .unsubscribeToken("tok-dueno-concurrencia")
                .build());

        LocalDate fecha = proximaFecha(DayOfWeek.WEDNESDAY);

        Establecimiento establecimiento = Establecimiento.builder()
                .nombre("Club Concurrencia")
                .direccion("Calle Falsa 123")
                .slug("club-concurrencia")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build();
        establecimiento.setHorariosAtencion(List.of(HorarioAtencion.builder()
                .diaSemana(DayOfWeek.WEDNESDAY)
                .horaApertura(LocalTime.of(8, 0))
                .horaCierre(LocalTime.of(23, 0))
                .establecimiento(establecimiento)
                .build()));
        establecimiento = establecimientoRepository.save(establecimiento);

        cancha = canchaRepository.save(Cancha.builder()
                .nombre("Cancha 1")
                .deportes(new java.util.HashSet<>(List.of(Deporte.FUTBOL)))
                .capacidad(10)
                .precioBase(BigDecimal.valueOf(1000))
                .montoSena(BigDecimal.ZERO)
                .duracionesPermitidas(new ArrayList<>(List.of(60)))
                .permiteInicioMediaHora(true)
                .establecimiento(establecimiento)
                .isActive(true)
                .build());

        jugador1 = usuarioRepository.save(Usuario.builder()
                .email("jugador1-concurrencia@test.com")
                .password("hash")
                .nombre("Jugador Uno")
                .rol(Role.PLAYER)
                .planSuscripcion(PlanSuscripcion.FREE)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .unsubscribeToken("tok-j1-concurrencia")
                .build());

        jugador2 = usuarioRepository.save(Usuario.builder()
                .email("jugador2-concurrencia@test.com")
                .password("hash")
                .nombre("Jugador Dos")
                .rol(Role.PLAYER)
                .planSuscripcion(PlanSuscripcion.FREE)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .unsubscribeToken("tok-j2-concurrencia")
                .build());

        inicio = fecha.atTime(10, 0);
        fin = fecha.atTime(11, 0);
    }

    @Test
    @DisplayName("dobleReservaMismoSlotEnParalelo_SoloUnaGana")
    void dobleReservaMismoSlotEnParalelo_SoloUnaGana() throws Exception {
        ReservaRequest request = new ReservaRequest(cancha.getId(), inicio, fin, Deporte.FUTBOL);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Callable<Boolean> intento1 = () -> intentarReservar(barrier, request, jugador1.getEmail());
        Callable<Boolean> intento2 = () -> intentarReservar(barrier, request, jugador2.getEmail());

        List<Future<Boolean>> futures = pool.invokeAll(List.of(intento1, intento2));
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "El pool no terminó a tiempo");

        long exitos = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                exitos++;
            }
        }

        assertEquals(1, exitos, "Exactamente una de las dos reservas concurrentes debe haber ganado el slot");

        List<com.matiasmeira.sacaladelangulo.reserva.model.Reserva> persistidas =
                reservaRepository.findOverlappingByCanchaId(cancha.getId(), inicio, fin);
        assertEquals(1, persistidas.size(), "Debe quedar UNA sola reserva persistida para ese slot, no dos");
    }

    private boolean intentarReservar(CyclicBarrier barrier, ReservaRequest request, String email) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            reservaService.crearReserva(request, email);
            return true;
        } catch (IllegalArgumentException ex) {
            // La cancha exacta ya está reservada / no hay disponibilidad en el pool: es el
            // resultado esperado para el hilo que pierde la carrera.
            return false;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
