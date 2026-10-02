package com.matiasmeira.sacaladelangulo.reserva.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoTurnoFijo;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.model.TurnoFijo;
import com.matiasmeira.sacaladelangulo.support.Canchas;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Valida contra una base real (H2) consultas de ReservaRepository cuyo JPQL un test con el
 * repositorio mockeado no ejercitaría: countReservasFuturasActivas (que
 * PoliticaCancelacionService necesita filtrado a CONFIRMADA/PENDIENTE_SENA con
 * fechaHoraInicio futura) y agregadosPorTurnoFijo (el GROUP BY agregado que usa
 * TurnoFijoService.listar).
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("ReservaRepository - Consultas agregadas contra una base real")
class ReservaRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ReservaRepository reservaRepository;

    @Test
    @DisplayName("countReservasFuturasActivas_CuentaSoloConfirmadaYPendienteSenaFuturas")
    void countReservasFuturasActivas_CuentaSoloConfirmadaYPendienteSenaFuturas() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-politica@test.com")
                .password("hash")
                .nombre("Dueno")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento establecimiento = entityManager.persist(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo Politica")
                .direccion("Calle Politica 123")
                .slug("complejo-politica")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));

        Cancha cancha = entityManager.persist(Cancha.builder()
                .nombre("Cancha 1")
                .deportes(Set.of(Deporte.PADEL))
                .isActive(true)
                .precioBase(BigDecimal.valueOf(1000))
                .montoSena(BigDecimal.valueOf(200))
                .establecimiento(establecimiento)
                .build());

        LocalDateTime ahora = LocalDateTime.now();

        entityManager.persist(reservaDe(cancha, EstadoReserva.CONFIRMADA, ahora.plusDays(2)));
        entityManager.persist(reservaDe(cancha, EstadoReserva.PENDIENTE_SENA, ahora.plusDays(3)));
        // No deben contarse: cancelada futura y confirmada ya pasada.
        entityManager.persist(reservaDe(cancha, EstadoReserva.CANCELADA, ahora.plusDays(1)));
        entityManager.persist(reservaDe(cancha, EstadoReserva.CONFIRMADA, ahora.minusDays(1)));
        entityManager.flush();

        long resultado = reservaRepository.countReservasFuturasActivas(establecimiento.getId(), ahora);

        assertEquals(2, resultado);
    }

    /**
     * Usada por EstablecimientoEliminacionService para la precondición "sin reservas futuras
     * confirmadas": tiene que contar sólo CONFIRMADA futura y devolver, en la misma fila, la
     * fecha más lejana -- excluyendo canceladas (futuras o no) y confirmadas ya pasadas, que
     * no bloquean la eliminación.
     */
    @Test
    @DisplayName("resumenReservasFuturasConfirmadas_CuentaSoloConfirmadaFutura_ExcluyeCanceladasYPasadas")
    void resumenReservasFuturasConfirmadas_CuentaSoloConfirmadaFutura_ExcluyeCanceladasYPasadas() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-elim-resumen@test.com")
                .password("hash")
                .nombre("Dueno")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento establecimiento = entityManager.persist(Establecimientos.establecimientoDeshabilitado(b -> b
                .nombre("Complejo Resumen")
                .direccion("Calle Resumen 123")
                .slug("complejo-resumen")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));

        Cancha cancha = entityManager.persist(Cancha.builder()
                .nombre("Cancha 1")
                .deportes(Set.of(Deporte.PADEL))
                .isActive(true)
                .precioBase(BigDecimal.valueOf(1000))
                .montoSena(BigDecimal.valueOf(200))
                .establecimiento(establecimiento)
                .build());

        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime masLejana = ahora.plusDays(10);

        entityManager.persist(reservaDe(cancha, EstadoReserva.CONFIRMADA, ahora.plusDays(2)));
        entityManager.persist(reservaDe(cancha, EstadoReserva.CONFIRMADA, masLejana));
        // No deben contar: cancelada futura, confirmada ya pasada, pendiente de seña futura.
        entityManager.persist(reservaDe(cancha, EstadoReserva.CANCELADA, ahora.plusDays(5)));
        entityManager.persist(reservaDe(cancha, EstadoReserva.CONFIRMADA, ahora.minusDays(1)));
        entityManager.persist(reservaDe(cancha, EstadoReserva.PENDIENTE_SENA, ahora.plusDays(1)));
        entityManager.flush();

        List<Object[]> resultado = reservaRepository.resumenReservasFuturasConfirmadas(establecimiento.getId(), ahora);

        assertEquals(1, resultado.size());
        assertEquals(2L, resultado.get(0)[0]);
        // Truncado a segundos: H2 redondea los nanosegundos del TIMESTAMP al releer, y la
        // precisión de sub-segundo no es lo que este test verifica.
        assertEquals(masLejana.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                ((LocalDateTime) resultado.get(0)[1]).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("resumenReservasFuturasConfirmadas_SinReservas_DevuelveCeroYFechaNula")
    void resumenReservasFuturasConfirmadas_SinReservas_DevuelveCeroYFechaNula() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-elim-resumen-vacio@test.com")
                .password("hash")
                .nombre("Dueno")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento establecimiento = entityManager.persist(Establecimientos.establecimientoDeshabilitado(b -> b
                .nombre("Complejo Vacio")
                .direccion("Calle Vacio 123")
                .slug("complejo-resumen-vacio")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));
        entityManager.flush();

        List<Object[]> resultado = reservaRepository.resumenReservasFuturasConfirmadas(establecimiento.getId(), LocalDateTime.now());

        assertEquals(1, resultado.size());
        assertEquals(0L, resultado.get(0)[0]);
        org.junit.jupiter.api.Assertions.assertNull(resultado.get(0)[1]);
    }

    /**
     * Igual que resumenReservasFuturasConfirmadas pero a nivel de una cancha puntual: usada
     * por CanchaEliminacionService para la precondición "sin reservas futuras vivas" de
     * ESA cancha. Tiene que contar sólo CONFIRMADA futura de esta cancha y devolver, en la
     * misma fila, la fecha más lejana -- ignorando otra cancha del mismo establecimiento.
     * Los casos de PENDIENTE_SENA (vigente, sin expiraEn, vencida) están más abajo.
     */
    @Test
    @DisplayName("resumenReservasFuturasVivasPorCancha_CuentaSoloDeEsaCancha_ExcluyeCanceladasPasadasYOtraCancha")
    void resumenReservasFuturasVivasPorCancha_CuentaSoloDeEsaCancha_ExcluyeCanceladasPasadasYOtraCancha() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-elim-cancha-resumen@test.com")
                .password("hash")
                .nombre("Dueno")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento establecimiento = entityManager.persist(Establecimientos.establecimientoDeshabilitado(b -> b
                .nombre("Complejo Resumen Cancha")
                .direccion("Calle Resumen Cancha 123")
                .slug("complejo-resumen-cancha")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));

        Cancha canchaAEliminar = entityManager.persist(Canchas.canchaDesactivada(establecimiento));
        Cancha otraCancha = entityManager.persist(Canchas.canchaActiva(establecimiento));

        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime masLejana = ahora.plusDays(10);

        entityManager.persist(reservaDe(canchaAEliminar, EstadoReserva.CONFIRMADA, ahora.plusDays(2)));
        entityManager.persist(reservaDe(canchaAEliminar, EstadoReserva.CONFIRMADA, masLejana));
        // No deben contar: cancelada futura y confirmada ya pasada de la misma cancha.
        entityManager.persist(reservaDe(canchaAEliminar, EstadoReserva.CANCELADA, ahora.plusDays(5)));
        entityManager.persist(reservaDe(canchaAEliminar, EstadoReserva.CONFIRMADA, ahora.minusDays(1)));
        // No debe contar: confirmada futura, pero de OTRA cancha del mismo establecimiento.
        entityManager.persist(reservaDe(otraCancha, EstadoReserva.CONFIRMADA, ahora.plusDays(20)));
        entityManager.flush();

        List<Object[]> resultado = reservaRepository.resumenReservasFuturasVivasPorCancha(canchaAEliminar.getId(), ahora);

        assertEquals(1, resultado.size());
        assertEquals(2L, resultado.get(0)[0]);
        assertEquals(masLejana.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                ((LocalDateTime) resultado.get(0)[1]).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("resumenReservasFuturasVivasPorCancha_SinReservas_DevuelveCeroYFechaNula")
    void resumenReservasFuturasVivasPorCancha_SinReservas_DevuelveCeroYFechaNula() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-elim-cancha-resumen-vacio@test.com")
                .password("hash")
                .nombre("Dueno")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento establecimiento = entityManager.persist(Establecimientos.establecimientoDeshabilitado(b -> b
                .nombre("Complejo Resumen Cancha Vacio")
                .direccion("Calle Resumen Cancha Vacio 123")
                .slug("complejo-resumen-cancha-vacio")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));

        Cancha cancha = entityManager.persist(Canchas.canchaDesactivada(establecimiento));
        entityManager.flush();

        List<Object[]> resultado = reservaRepository.resumenReservasFuturasVivasPorCancha(cancha.getId(), LocalDateTime.now());

        assertEquals(1, resultado.size());
        assertEquals(0L, resultado.get(0)[0]);
        org.junit.jupiter.api.Assertions.assertNull(resultado.get(0)[1]);
    }

    /**
     * Confirma con datos reales de TurnoFijo (no sólo Reserva sueltas) un hallazgo del
     * análisis previo de CanchaEliminacionService: un turno fijo ACTIVO con una ocurrencia futura
     * CONFIRMADA queda atrapado por resumenReservasFuturasVivasPorCancha, sin que
     * CanchaEliminacionService necesite consultar TurnoFijoRepository. Esto es así porque
     * TurnoFijoService.crearInterno persiste TODAS las ocurrencias del período como Reserva
     * CONFIRMADA en la misma transacción que la regla -- no hay materialización diferida.
     */
    @Test
    @DisplayName("resumenReservasFuturasVivasPorCancha_CapturaOcurrenciaDeTurnoFijoActivo")
    void resumenReservasFuturasVivasPorCancha_CapturaOcurrenciaDeTurnoFijoActivo() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-elim-turno-fijo@test.com")
                .password("hash")
                .nombre("Dueno")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento establecimiento = entityManager.persist(Establecimientos.establecimientoDeshabilitado(b -> b
                .nombre("Complejo Turno Fijo Elim")
                .direccion("Calle Turno Fijo Elim 123")
                .slug("complejo-turno-fijo-elim")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));

        Cancha cancha = entityManager.persist(Canchas.canchaDesactivada(establecimiento));

        TurnoFijo serieActiva = entityManager.persist(TurnoFijo.builder()
                .cancha(cancha)
                .deporteSeleccionado(Deporte.PADEL)
                .diaSemana(DayOfWeek.TUESDAY)
                .horaInicio(LocalTime.of(20, 0))
                .horaFin(LocalTime.of(21, 0))
                .fechaInicioPeriodo(LocalDate.of(2030, 1, 1))
                .fechaFinPeriodo(LocalDate.of(2030, 12, 31))
                .estado(EstadoTurnoFijo.ACTIVO)
                .nombreClienteManual("Cliente Fijo")
                .build());

        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime ocurrenciaFutura = ahora.plusDays(9);
        entityManager.persist(reservaDeTurnoFijo(cancha, serieActiva, EstadoReserva.CONFIRMADA, ocurrenciaFutura));
        entityManager.flush();

        List<Object[]> resultado = reservaRepository.resumenReservasFuturasVivasPorCancha(cancha.getId(), ahora);

        assertEquals(1, resultado.size());
        assertEquals(1L, resultado.get(0)[0]);
        assertEquals(ocurrenciaFutura.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                ((LocalDateTime) resultado.get(0)[1]).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    /**
     * El caso que motivó el fix: antes esta query sólo miraba CONFIRMADA, así que una
     * PENDIENTE_SENA futura (vigente o sin expiraEn) no bloqueaba la eliminación de la cancha
     * y quedaba colgada sobre una cancha eliminada (ver diagnóstico de CanchaEliminacionService).
     * Cubre en una sola tabla los estados que sí y los que no cuentan como "viva".
     */
    @Test
    @DisplayName("resumenReservasFuturasVivasPorCancha_PendienteSenaVigenteOSinExpiraEn_Cuenta_VencidaPasadaCanceladaFinalizadaAusente_NoCuentan")
    void resumenReservasFuturasVivasPorCancha_PendienteSenaVigenteOSinExpiraEn_Cuenta_VencidaPasadaCanceladaFinalizadaAusente_NoCuentan() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-elim-cancha-pendiente-sena@test.com")
                .password("hash")
                .nombre("Dueno")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento establecimiento = entityManager.persist(Establecimientos.establecimientoDeshabilitado(b -> b
                .nombre("Complejo Pendiente Sena")
                .direccion("Calle Pendiente Sena 123")
                .slug("complejo-pendiente-sena")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));

        Cancha cancha = entityManager.persist(Canchas.canchaDesactivada(establecimiento));

        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime masLejana = ahora.plusDays(4);

        // Cuentan: PENDIENTE_SENA todavía vigente, PENDIENTE_SENA sin expiraEn (nula) y
        // CONFIRMADA -- las tres futuras.
        Reserva pendienteVigente = reservaDe(cancha, EstadoReserva.PENDIENTE_SENA, ahora.plusDays(1));
        pendienteVigente.setExpiraEn(ahora.plusMinutes(10));
        entityManager.persist(pendienteVigente);

        Reserva pendienteSinExpiracion = reservaDe(cancha, EstadoReserva.PENDIENTE_SENA, masLejana);
        pendienteSinExpiracion.setExpiraEn(null);
        entityManager.persist(pendienteSinExpiracion);

        entityManager.persist(reservaDe(cancha, EstadoReserva.CONFIRMADA, ahora.plusDays(2)));

        // No cuentan: PENDIENTE_SENA con expiraEn ya vencido (aunque ReservaExpiracionService
        // todavía no la haya pasado a CANCELADA_PRERESERVA), pasada, cancelada, finalizada y
        // ausente.
        Reserva pendienteVencida = reservaDe(cancha, EstadoReserva.PENDIENTE_SENA, ahora.plusDays(3));
        pendienteVencida.setExpiraEn(ahora.minusMinutes(1));
        entityManager.persist(pendienteVencida);

        entityManager.persist(reservaDe(cancha, EstadoReserva.CONFIRMADA, ahora.minusDays(1)));
        entityManager.persist(reservaDe(cancha, EstadoReserva.CANCELADA, ahora.plusDays(5)));
        entityManager.persist(reservaDe(cancha, EstadoReserva.CANCELADA_PRERESERVA, ahora.plusDays(5)));
        entityManager.persist(reservaDe(cancha, EstadoReserva.FINALIZADA, ahora.minusDays(2)));
        entityManager.persist(reservaDe(cancha, EstadoReserva.AUSENTE, ahora.minusDays(2)));
        entityManager.flush();

        List<Object[]> resultado = reservaRepository.resumenReservasFuturasVivasPorCancha(cancha.getId(), ahora);

        assertEquals(1, resultado.size());
        assertEquals(3L, resultado.get(0)[0]);
        assertEquals(masLejana.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                ((LocalDateTime) resultado.get(0)[1]).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    /**
     * El caso más importante de CanchaEliminacionService: una reserva
     * HISTÓRICA (FINALIZADA, ya cobrada) sobre una cancha que HOY está eliminada tiene que
     * seguir resolviendo el nombre y el precio de esa cancha sin romperse -- es exactamente lo
     * que necesitan los reportes y los cierres de caja de hace meses. Cancha no se borra
     * físicamente (soft delete, sin cascada) y Reserva.cancha es un FK directo sin ningún
     * filtro por deletedAt, así que esto funciona "gratis": no hace falta ningún cambio de
     * código en reportes para que siga andando.
     */
    @Test
    @DisplayName("reservaHistorica_SobreCanchaEliminada_SigueResolviendoNombreYPrecio")
    void reservaHistorica_SobreCanchaEliminada_SigueResolviendoNombreYPrecio() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-historial-cancha-eliminada@test.com")
                .password("hash")
                .nombre("Dueno")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento establecimiento = entityManager.persist(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo Historial")
                .direccion("Calle Historial 123")
                .slug("complejo-historial-cancha-eliminada")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));

        Cancha cancha = entityManager.persist(Canchas.canchaActiva(establecimiento, b -> b.nombre("Cancha Historica")));

        Reserva reservaFinalizada = entityManager.persist(Reserva.builder()
                .cancha(cancha)
                .deporteSeleccionado(Deporte.PADEL)
                .fechaHoraInicio(LocalDateTime.now().minusMonths(3))
                .fechaHoraFin(LocalDateTime.now().minusMonths(3).plusHours(1))
                .estado(EstadoReserva.FINALIZADA)
                .precioTotal(BigDecimal.valueOf(5000))
                .build());
        entityManager.flush();

        // Baja lógica de la cancha, mismo efecto que CanchaEliminacionService: sólo deletedAt,
        // sin tocar ni borrar la reserva ni la fila de canchas.
        cancha.setDeletedAt(LocalDateTime.now());
        entityManager.persist(cancha);
        entityManager.flush();
        entityManager.clear();

        Reserva releida = entityManager.find(Reserva.class, reservaFinalizada.getId());

        assertEquals("Cancha Historica", releida.getCancha().getNombre());
        assertEquals(0, BigDecimal.valueOf(5000).compareTo(releida.getPrecioTotal()));
        assertEquals(EstadoReserva.FINALIZADA, releida.getEstado());
        org.junit.jupiter.api.Assertions.assertNotNull(releida.getCancha().getPrecioBase());
        org.junit.jupiter.api.Assertions.assertNotNull(releida.getCancha().getDeletedAt());
    }

    /**
     * liberarReservasVencidas sólo filtra por estado='PENDIENTE_SENA', así que una reserva
     * que nació CONFIRMADA (sin seña correspondiente, ver ReservaService.correspondeSena) y
     * por lo tanto tiene expiraEn null nunca puede entrar al UPDATE, sin importar cuánto
     * tiempo pase. Este test lo deja explícito contra el JPQL real, no sólo por lectura del
     * WHERE.
     */
    @Test
    @DisplayName("liberarReservasVencidas_LiberaSoloPendienteSenaVencida_NoTocaConfirmadaNiPendienteVigente")
    void liberarReservasVencidas_LiberaSoloPendienteSenaVencida_NoTocaConfirmadaNiPendienteVigente() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-liberacion@test.com")
                .password("hash")
                .nombre("Dueno")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento establecimiento = entityManager.persist(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo Liberacion")
                .direccion("Calle Liberacion 123")
                .slug("complejo-liberacion")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));

        Cancha cancha = entityManager.persist(Cancha.builder()
                .nombre("Cancha 1")
                .deportes(Set.of(Deporte.PADEL))
                .isActive(true)
                .precioBase(BigDecimal.valueOf(1000))
                .montoSena(BigDecimal.valueOf(200))
                .establecimiento(establecimiento)
                .build());

        LocalDateTime ahora = LocalDateTime.now();

        Reserva pendienteVencida = entityManager.persist(Reserva.builder()
                .cancha(cancha).deporteSeleccionado(Deporte.PADEL)
                .fechaHoraInicio(ahora.plusDays(1)).fechaHoraFin(ahora.plusDays(1).plusHours(1))
                .estado(EstadoReserva.PENDIENTE_SENA).precioTotal(BigDecimal.valueOf(1000))
                .expiraEn(ahora.minusMinutes(5))
                .build());
        Reserva pendienteVigente = entityManager.persist(Reserva.builder()
                .cancha(cancha).deporteSeleccionado(Deporte.PADEL)
                .fechaHoraInicio(ahora.plusDays(2)).fechaHoraFin(ahora.plusDays(2).plusHours(1))
                .estado(EstadoReserva.PENDIENTE_SENA).precioTotal(BigDecimal.valueOf(1000))
                .expiraEn(ahora.plusMinutes(5))
                .build());
        // Nacida CONFIRMADA sin seña correspondiente: expiraEn null, exactamente como la
        // arma ahora ReservaService.crearReserva cuando correspondeSena es false.
        Reserva confirmadaSinExpiracion = entityManager.persist(Reserva.builder()
                .cancha(cancha).deporteSeleccionado(Deporte.PADEL)
                .fechaHoraInicio(ahora.plusDays(3)).fechaHoraFin(ahora.plusDays(3).plusHours(1))
                .estado(EstadoReserva.CONFIRMADA).precioTotal(BigDecimal.valueOf(1000))
                .expiraEn(null)
                .build());
        entityManager.flush();

        int liberadas = reservaRepository.liberarReservasVencidas(ahora);
        entityManager.clear();

        assertEquals(1, liberadas);
        assertEquals(EstadoReserva.CANCELADA_PRERESERVA,
                entityManager.find(Reserva.class, pendienteVencida.getId()).getEstado());
        assertEquals(EstadoReserva.PENDIENTE_SENA,
                entityManager.find(Reserva.class, pendienteVigente.getId()).getEstado());
        assertEquals(EstadoReserva.CONFIRMADA,
                entityManager.find(Reserva.class, confirmadaSinExpiracion.getId()).getEstado());
    }

    private Reserva reservaDe(Cancha cancha, EstadoReserva estado, LocalDateTime fechaHoraInicio) {
        return Reserva.builder()
                .cancha(cancha)
                .deporteSeleccionado(Deporte.PADEL)
                .fechaHoraInicio(fechaHoraInicio)
                .fechaHoraFin(fechaHoraInicio.plusHours(1))
                .estado(estado)
                .precioTotal(BigDecimal.valueOf(1000))
                .build();
    }

    /**
     * Valida contra una base real que agregadosPorTurnoFijo (usada por
     * TurnoFijoService.listar para resolver cantidadOcurrenciasActivas/proximaOcurrencia de
     * toda una página en una sola consulta) cuente y agrupe bien: TurnoFijoServiceTest la
     * mockea, así que nunca ejercita el JPQL en sí (el GROUP BY, el filtro de estados y el
     * de fecha futura).
     */
    @Test
    @DisplayName("agregadosPorTurnoFijo_CuentaSoloOcurrenciasFuturasActivasYLasAgrupaPorSerie")
    void agregadosPorTurnoFijo_CuentaSoloOcurrenciasFuturasActivasYLasAgrupaPorSerie() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-turnofijo@test.com")
                .password("hash")
                .nombre("Dueno")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento establecimiento = entityManager.persist(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo Turno Fijo")
                .direccion("Calle Fija 456")
                .slug("complejo-turno-fijo")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));

        Cancha cancha = entityManager.persist(Cancha.builder()
                .nombre("Cancha 1")
                .deportes(Set.of(Deporte.PADEL))
                .isActive(true)
                .precioBase(BigDecimal.valueOf(1000))
                .montoSena(BigDecimal.valueOf(200))
                .establecimiento(establecimiento)
                .build());

        TurnoFijo conOcurrenciasFuturas = entityManager.persist(TurnoFijo.builder()
                .cancha(cancha)
                .deporteSeleccionado(Deporte.PADEL)
                .diaSemana(DayOfWeek.TUESDAY)
                .horaInicio(LocalTime.of(20, 0))
                .horaFin(LocalTime.of(21, 0))
                .fechaInicioPeriodo(LocalDate.of(2030, 1, 1))
                .fechaFinPeriodo(LocalDate.of(2030, 12, 31))
                .estado(EstadoTurnoFijo.ACTIVO)
                .nombreClienteManual("Cliente Fijo")
                .build());

        TurnoFijo sinOcurrenciasFuturas = entityManager.persist(TurnoFijo.builder()
                .cancha(cancha)
                .deporteSeleccionado(Deporte.PADEL)
                .diaSemana(DayOfWeek.WEDNESDAY)
                .horaInicio(LocalTime.of(19, 0))
                .horaFin(LocalTime.of(20, 0))
                .fechaInicioPeriodo(LocalDate.of(2030, 1, 1))
                .fechaFinPeriodo(LocalDate.of(2030, 12, 31))
                .estado(EstadoTurnoFijo.ACTIVO)
                .nombreClienteManual("Otro Cliente")
                .build());

        LocalDateTime ahora = LocalDateTime.now();

        // Cuentan: dos ocurrencias futuras vivas de conOcurrenciasFuturas.
        entityManager.persist(reservaDeTurnoFijo(cancha, conOcurrenciasFuturas, EstadoReserva.CONFIRMADA, ahora.plusDays(9)));
        entityManager.persist(reservaDeTurnoFijo(cancha, conOcurrenciasFuturas, EstadoReserva.PENDIENTE_SENA, ahora.plusDays(2)));
        // No cuentan, del mismo turno fijo: cancelada futura y confirmada ya pasada.
        entityManager.persist(reservaDeTurnoFijo(cancha, conOcurrenciasFuturas, EstadoReserva.CANCELADA, ahora.plusDays(5)));
        entityManager.persist(reservaDeTurnoFijo(cancha, conOcurrenciasFuturas, EstadoReserva.CONFIRMADA, ahora.minusDays(3)));
        // No debe filtrarse hacia conOcurrenciasFuturas: es de otra serie, y encima cancelada.
        entityManager.persist(reservaDeTurnoFijo(cancha, sinOcurrenciasFuturas, EstadoReserva.CANCELADA, ahora.plusDays(1)));
        entityManager.flush();

        List<Object[]> filas = reservaRepository.agregadosPorTurnoFijo(
                List.of(conOcurrenciasFuturas.getId(), sinOcurrenciasFuturas.getId()), ahora);

        // sinOcurrenciasFuturas no tiene ninguna ocurrencia viva: no aparece ninguna fila
        // para esa serie (a diferencia de un LEFT JOIN, que traería 0/null).
        assertEquals(1, filas.size());
        Object[] fila = filas.get(0);
        assertEquals(conOcurrenciasFuturas.getId(), fila[0]);
        assertEquals(2L, fila[1]);
        // truncatedTo(MILLIS): H2 redondea a microsegundos al persistir, así que comparar el
        // LocalDateTime completo (con nanosegundos) es flaky por precisión, no por el JPQL.
        assertEquals(ahora.plusDays(2).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                ((LocalDateTime) fila[2]).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("findByJugadorIdAndEstadoNot_ExcluyeSoloElEstadoDadoYSoloDelJugador")
    void findByJugadorIdAndEstadoNot_ExcluyeSoloElEstadoDadoYSoloDelJugador() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-mis@test.com").password("hash").nombre("Dueno").rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL).isActive(true).emailVerified(true)
                .telefonoVerificado(false).build());
        Usuario jugador = entityManager.persist(Usuario.builder()
                .email("jugador-mis@test.com").password("hash").nombre("Jugador").rol(Role.PLAYER)
                .planSuscripcion(PlanSuscripcion.TRIAL).isActive(true).emailVerified(true)
                .telefonoVerificado(false).build());
        Usuario otro = entityManager.persist(Usuario.builder()
                .email("otro-mis@test.com").password("hash").nombre("Otro").rol(Role.PLAYER)
                .planSuscripcion(PlanSuscripcion.TRIAL).isActive(true).emailVerified(true)
                .telefonoVerificado(false).build());
        Establecimiento establecimiento = entityManager.persist(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo Mis").direccion("Calle Mis 123").slug("complejo-mis")
                .latitud(-34.6).longitud(-58.4).requiereSena(false).dueno(dueno)));
        Cancha cancha = entityManager.persist(Cancha.builder()
                .nombre("Cancha 1").deportes(Set.of(Deporte.PADEL)).isActive(true)
                .precioBase(BigDecimal.valueOf(1000)).montoSena(BigDecimal.valueOf(200))
                .establecimiento(establecimiento).build());
        LocalDateTime base = LocalDateTime.now().plusDays(1);
        int hora = 0;
        for (EstadoReserva estado : EstadoReserva.values()) {
            Reserva r = reservaDe(cancha, estado, base.plusHours(hora++));
            r.setJugador(jugador);
            entityManager.persist(r);
        }
        Reserva ajena = reservaDe(cancha, EstadoReserva.CONFIRMADA, base.plusHours(hora));
        ajena.setJugador(otro);
        entityManager.persist(ajena);
        entityManager.flush();
        entityManager.clear();

        var pagina = reservaRepository.findByJugadorIdAndEstadoNot(
                jugador.getId(), EstadoReserva.CANCELADA_PRERESERVA, org.springframework.data.domain.PageRequest.of(0, 50));

        assertEquals(EstadoReserva.values().length - 1, pagina.getTotalElements());
        assertEquals(false, pagina.getContent().stream()
                .anyMatch(r -> r.getEstado() == EstadoReserva.CANCELADA_PRERESERVA));
        assertEquals(true, pagina.getContent().stream()
                .allMatch(r -> r.getJugador().getId().equals(jugador.getId())));
    }

    private Reserva reservaDeTurnoFijo(Cancha cancha, TurnoFijo turnoFijo, EstadoReserva estado, LocalDateTime fechaHoraInicio) {
        return Reserva.builder()
                .cancha(cancha)
                .turnoFijo(turnoFijo)
                .deporteSeleccionado(Deporte.PADEL)
                .fechaHoraInicio(fechaHoraInicio)
                .fechaHoraFin(fechaHoraInicio.plusHours(1))
                .estado(estado)
                .precioTotal(BigDecimal.valueOf(1000))
                .build();
    }
}
