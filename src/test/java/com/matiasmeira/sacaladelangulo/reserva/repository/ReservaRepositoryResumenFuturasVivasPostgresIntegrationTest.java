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
import com.matiasmeira.sacaladelangulo.support.AbstractPostgresIntegrationTest;
import com.matiasmeira.sacaladelangulo.support.Canchas;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Ejercita ReservaRepository.resumenReservasFuturasVivasPorCancha contra un Postgres real
 * (Testcontainers), sobre todo el caso de PENDIENTE_SENA vencida: pgjdbc tiene que resolver
 * el tipo de ":ahora" en la comparación "r.expiraEn > :ahora" sin ambigüedad (a diferencia
 * del bug 42P18 de GastoRepository/VentaRepository, acá ":ahora" siempre aparece también en
 * "r.fechaHoraInicio > :ahora", con tipo ya fijado por esa columna, así que no hace falta
 * cast -- este test es la prueba de que efectivamente no hace falta).
 *
 * <p>ReservaRepositoryTest (H2) ya cubre la misma matriz de estados; este test existe para
 * confirmar que el comportamiento no cambia contra el motor real que usa producción.
 *
 * <p>Requiere Docker. Ver AbstractPostgresIntegrationTest.
 */
@Tag("testcontainers")
@DisplayName("ReservaRepository.resumenReservasFuturasVivasPorCancha - contra Postgres real")
class ReservaRepositoryResumenFuturasVivasPostgresIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private EstablecimientoRepository establecimientoRepository;
    @Autowired
    private CanchaRepository canchaRepository;
    @Autowired
    private ReservaRepository reservaRepository;

    private Cancha cancha;

    @BeforeEach
    void setUp() {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno-resumen-vivas-" + System.nanoTime() + "@test.com")
                .password("hash")
                .nombre("Dueño")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .unsubscribeToken("tok-resumen-vivas-" + System.nanoTime())
                .build());

        Establecimiento establecimiento = establecimientoRepository.save(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Club Resumen Vivas")
                .direccion("Calle Resumen Vivas 123")
                .slug("club-resumen-vivas-" + System.nanoTime())
                .dueno(dueno)));

        cancha = canchaRepository.save(Canchas.canchaDesactivada(establecimiento));
    }

    private Reserva reservaDe(EstadoReserva estado, LocalDateTime fechaHoraInicio) {
        return Reserva.builder()
                .cancha(cancha)
                .deporteSeleccionado(Deporte.PADEL)
                .fechaHoraInicio(fechaHoraInicio)
                .fechaHoraFin(fechaHoraInicio.plusHours(1))
                .estado(estado)
                .precioTotal(BigDecimal.valueOf(1000))
                .senaPagada(BigDecimal.ZERO)
                .build();
    }

    @Test
    @DisplayName("PendienteSenaVencida_NoBloqueaAunqueElJobDeExpiracionNoHayaCorridoTodavia")
    void pendienteSenaVencida_NoBloqueaAunqueElJobDeExpiracionNoHayaCorridoTodavia() {
        LocalDateTime ahora = LocalDateTime.now();

        Reserva pendienteVencida = reservaDe(EstadoReserva.PENDIENTE_SENA, ahora.plusDays(2));
        pendienteVencida.setExpiraEn(ahora.minusMinutes(1));
        reservaRepository.saveAndFlush(pendienteVencida);

        List<Object[]> resultado = reservaRepository.resumenReservasFuturasVivasPorCancha(cancha.getId(), ahora);

        assertEquals(1, resultado.size());
        assertEquals(0L, resultado.get(0)[0]);
        assertNull(resultado.get(0)[1]);
    }

    @Test
    @DisplayName("PendienteSenaVigenteYConfirmada_Cuentan_DevuelveLaFechaMasLejana")
    void pendienteSenaVigenteYConfirmada_Cuentan_DevuelveLaFechaMasLejana() {
        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime masLejana = ahora.plusDays(10);

        Reserva pendienteVigente = reservaDe(EstadoReserva.PENDIENTE_SENA, ahora.plusDays(1));
        pendienteVigente.setExpiraEn(ahora.plusMinutes(10));
        reservaRepository.saveAndFlush(pendienteVigente);

        Reserva pendienteSinExpiracion = reservaDe(EstadoReserva.PENDIENTE_SENA, masLejana);
        pendienteSinExpiracion.setExpiraEn(null);
        reservaRepository.saveAndFlush(pendienteSinExpiracion);

        reservaRepository.saveAndFlush(reservaDe(EstadoReserva.CONFIRMADA, ahora.plusDays(3)));

        List<Object[]> resultado = reservaRepository.resumenReservasFuturasVivasPorCancha(cancha.getId(), ahora);

        assertEquals(1, resultado.size());
        assertEquals(3L, resultado.get(0)[0]);
        assertEquals(masLejana.truncatedTo(ChronoUnit.SECONDS),
                ((LocalDateTime) resultado.get(0)[1]).truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("PasadasCanceladasFinalizadasYAusentes_NoCuentan")
    void pasadasCanceladasFinalizadasYAusentes_NoCuentan() {
        LocalDateTime ahora = LocalDateTime.now();

        // CONFIRMADA, FINALIZADA y AUSENTE viven bajo el mismo constraint de exclusión de la
        // base (excl_reservas_solapadas, ver V10): cada una necesita su propio horario, sin
        // solaparse entre sí, aunque para esta query lo único que importa es que ninguna es
        // futura.
        reservaRepository.saveAndFlush(reservaDe(EstadoReserva.CONFIRMADA, ahora.minusDays(1)));
        reservaRepository.saveAndFlush(reservaDe(EstadoReserva.CANCELADA, ahora.plusDays(1)));
        reservaRepository.saveAndFlush(reservaDe(EstadoReserva.CANCELADA_PRERESERVA, ahora.plusDays(1)));
        reservaRepository.saveAndFlush(reservaDe(EstadoReserva.FINALIZADA, ahora.minusDays(2)));
        reservaRepository.saveAndFlush(reservaDe(EstadoReserva.AUSENTE, ahora.minusDays(3)));

        List<Object[]> resultado = reservaRepository.resumenReservasFuturasVivasPorCancha(cancha.getId(), ahora);

        assertEquals(1, resultado.size());
        assertEquals(0L, resultado.get(0)[0]);
        assertNull(resultado.get(0)[1]);
    }
}
