package com.matiasmeira.sacaladelangulo.reserva.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifica el constraint de exclusión {@code excl_reservas_solapadas} (ver V10) contra un
 * Postgres real. Es la única forma de probarlo: H2 no soporta EXCLUDE USING gist, y un
 * repositorio mockeado no ejecuta constraints.
 *
 * <p>A diferencia de ReservaConcurrenciaIntegrationTest, que ejercita el lock pesimista a
 * través del servicio, acá se escribe DIRECTO contra el repositorio, salteando a propósito
 * toda la validación de ReservaService. Eso es exactamente el escenario que el constraint
 * viene a cubrir: un camino que no toma el lock (un import, una corrección manual, un
 * endpoint futuro mal hecho).
 *
 * <p>Los cuatro casos cubren las decisiones de diseño documentadas en V10 sobre qué estados
 * entran al constraint. Si alguien cambia esa cláusula WHERE, estos tests lo detectan.
 *
 * <p>Requiere Docker. Ver AbstractPostgresIntegrationTest.
 */
@Tag("testcontainers")
@DisplayName("reservas - Constraint de exclusión excl_reservas_solapadas (V10)")
class ReservaExclusionConstraintIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private EstablecimientoRepository establecimientoRepository;
    @Autowired
    private CanchaRepository canchaRepository;
    @Autowired
    private ReservaRepository reservaRepository;

    private Cancha cancha;
    private LocalDateTime inicio;
    private LocalDateTime fin;

    @BeforeEach
    void setUp() {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno-exclusion@test.com")
                .password("hash")
                .nombre("Dueño")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .unsubscribeToken("tok-dueno-exclusion")
                .build());

        Establecimiento establecimiento = establecimientoRepository.save(Establecimiento.builder()
                .nombre("Club Exclusión")
                .direccion("Calle Falsa 456")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());

        cancha = canchaRepository.save(Cancha.builder()
                .nombre("Cancha Única")
                .deportes(new HashSet<>(List.of(Deporte.FUTBOL)))
                .capacidad(10)
                .precioBase(BigDecimal.valueOf(1000))
                .montoSena(BigDecimal.ZERO)
                .duracionesPermitidas(new ArrayList<>(List.of(60)))
                .permiteInicioMediaHora(true)
                .establecimiento(establecimiento)
                .isActive(true)
                .build());

        inicio = LocalDateTime.now().plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0);
        fin = inicio.plusHours(1);
    }

    private Reserva nuevaReserva(LocalDateTime desde, LocalDateTime hasta, EstadoReserva estado) {
        return Reserva.builder()
                .cancha(cancha)
                .jugador(null)
                .nombreClienteManual("Cliente de prueba")
                .deporteSeleccionado(Deporte.FUTBOL)
                .fechaHoraInicio(desde)
                .fechaHoraFin(hasta)
                .estado(estado)
                .precioTotal(BigDecimal.valueOf(1000))
                .senaPagada(BigDecimal.ZERO)
                .build();
    }

    @Test
    @DisplayName("dosConfirmadasSolapadasEnLaMismaCancha_LaBaseRechazaLaSegunda")
    void dosConfirmadasSolapadasEnLaMismaCancha_LaBaseRechazaLaSegunda() {
        reservaRepository.saveAndFlush(nuevaReserva(inicio, fin, EstadoReserva.CONFIRMADA));

        // Se solapa parcialmente (media hora adentro): el constraint tiene que frenarla
        // aunque no sea el mismo rango exacto.
        Reserva solapada = nuevaReserva(inicio.plusMinutes(30), fin.plusMinutes(30), EstadoReserva.CONFIRMADA);

        assertThrows(DataIntegrityViolationException.class,
                () -> reservaRepository.saveAndFlush(solapada),
                "La base debe rechazar una segunda reserva CONFIRMADA solapada sobre la misma cancha");
    }

    @Test
    @DisplayName("reservasAdyacentes_NoSeConsideranSolapadas")
    void reservasAdyacentes_NoSeConsideranSolapadas() {
        reservaRepository.saveAndFlush(nuevaReserva(inicio, fin, EstadoReserva.CONFIRMADA));

        // 14:00-15:00 y 15:00-16:00 NO chocan: el rango es '[)' (fin excluido), igual que la
        // semántica de la app (inicio < :fin AND fin > :inicio).
        assertDoesNotThrow(
                () -> reservaRepository.saveAndFlush(nuevaReserva(fin, fin.plusHours(1), EstadoReserva.CONFIRMADA)),
                "Dos turnos consecutivos que solo comparten el instante de borde deben poder coexistir");
    }

    @Test
    @DisplayName("canceladaNoBloqueaElRebooking_DelMismoSlot")
    void canceladaNoBloqueaElRebooking_DelMismoSlot() {
        reservaRepository.saveAndFlush(nuevaReserva(inicio, fin, EstadoReserva.CANCELADA));

        assertDoesNotThrow(
                () -> reservaRepository.saveAndFlush(nuevaReserva(inicio, fin, EstadoReserva.CONFIRMADA)),
                "Una reserva CANCELADA libera el slot y no debe impedir volver a venderlo");
    }

    @Test
    @DisplayName("pendienteSenaNoBloqueaElRebooking_AunqueTodaviaNoLaHayaLimpiadoElJob")
    void pendienteSenaNoBloqueaElRebooking_AunqueTodaviaNoLaHayaLimpiadoElJob() {
        // Este es el caso que obligó a dejar PENDIENTE_SENA FUERA del constraint (ver V10):
        // la app libera una pre-reserva vencida por expiraEn < now(), pero un constraint no
        // puede expresar "vencida" (now() no es inmutable). Si PENDIENTE_SENA estuviera
        // adentro, esta pre-reserva abandonada bloquearía el horario hasta que corriera
        // ReservaExpiracionService, y el rebooking legítimo fallaría.
        Reserva preReservaVencida = nuevaReserva(inicio, fin, EstadoReserva.PENDIENTE_SENA);
        preReservaVencida.setExpiraEn(LocalDateTime.now().minusMinutes(30));
        reservaRepository.saveAndFlush(preReservaVencida);

        assertDoesNotThrow(
                () -> reservaRepository.saveAndFlush(nuevaReserva(inicio, fin, EstadoReserva.CONFIRMADA)),
                "Una pre-reserva vencida no debe bloquear a nivel base el rebooking que la app sí permite");
    }
}
