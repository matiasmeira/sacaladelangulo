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
import com.matiasmeira.sacaladelangulo.support.Canchas;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ejercita ReservaRepository.findByJugadorIdAndEstadoNot ("Mis reservas" sin filtro) contra un
 * Postgres real: mismo caso que ReservaRepositoryTest (H2), para confirmar que la query derivada
 * se comporta igual en el motor de producción. Requiere Docker. Ver AbstractPostgresIntegrationTest.
 */
@Tag("testcontainers")
@DisplayName("ReservaRepository.findByJugadorIdAndEstadoNot - contra Postgres real")
class ReservaRepositoryMisReservasPostgresIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private EstablecimientoRepository establecimientoRepository;
    @Autowired
    private CanchaRepository canchaRepository;
    @Autowired
    private ReservaRepository reservaRepository;

    private Usuario usuario(Role rol, String prefijo) {
        long n = System.nanoTime();
        return usuarioRepository.save(Usuario.builder()
                .email(prefijo + "-" + n + "@test.com")
                .password("hash")
                .nombre(prefijo)
                .rol(rol)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .unsubscribeToken("tok-" + prefijo + "-" + n)
                .build());
    }

    private Reserva reservaDe(Cancha cancha, Usuario jugador, EstadoReserva estado, LocalDateTime inicio) {
        return Reserva.builder()
                .cancha(cancha)
                .jugador(jugador)
                .deporteSeleccionado(Deporte.PADEL)
                .fechaHoraInicio(inicio)
                .fechaHoraFin(inicio.plusHours(1))
                .estado(estado)
                .precioTotal(BigDecimal.valueOf(1000))
                .senaPagada(BigDecimal.ZERO)
                .expiraEn(estado == EstadoReserva.PENDIENTE_SENA ? LocalDateTime.now().plusMinutes(10) : null)
                .build();
    }

    @Test
    @DisplayName("ExcluyeSoloCanceladaPrereservaYSoloDelJugador")
    void excluyeSoloCanceladaPrereservaYSoloDelJugador() {
        Usuario dueno = usuario(Role.OWNER, "dueno-mis");
        Usuario jugador = usuario(Role.PLAYER, "jugador-mis");
        Usuario otro = usuario(Role.PLAYER, "otro-mis");
        Establecimiento establecimiento = establecimientoRepository.save(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Club Mis Reservas")
                .direccion("Calle Mis Reservas 123")
                .slug("club-mis-reservas-" + System.nanoTime())
                .dueno(dueno)));
        Cancha cancha = canchaRepository.save(Canchas.canchaDesactivada(establecimiento));

        LocalDateTime base = LocalDateTime.now().plusDays(1).withMinute(0).withSecond(0).withNano(0);
        int hora = 0;
        for (EstadoReserva estado : EstadoReserva.values()) {
            reservaRepository.saveAndFlush(reservaDe(cancha, jugador, estado, base.plusHours(hora++)));
        }
        reservaRepository.saveAndFlush(reservaDe(cancha, otro, EstadoReserva.CONFIRMADA, base.plusHours(hora)));

        Page<Reserva> pagina = reservaRepository.findByJugadorIdAndEstadoNot(
                jugador.getId(), EstadoReserva.CANCELADA_PRERESERVA, PageRequest.of(0, 50));

        assertEquals(EstadoReserva.values().length - 1, pagina.getTotalElements());
        assertTrue(pagina.getContent().stream().noneMatch(r -> r.getEstado() == EstadoReserva.CANCELADA_PRERESERVA));
        assertTrue(pagina.getContent().stream().allMatch(r -> r.getJugador().getId().equals(jugador.getId())));
    }
}
