package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CanchaRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.publico.service.ComplejoDetalleCache;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * isActive es reversible (ver comentario de src/lib/panel/canchas.ts:22-26 en el front): se
 * puede volver a activar una cancha desde actualizarCancha, y desactivar bloquea si alguna
 * reserva futura del GRUPO de pool (no solo el pool propio) deja de tener capacidad al sacar
 * esa cancha. Ver PoolCanchaCalculatorGrupoFisicasTest para el bug de capacidad que esto
 * recalcula (footprint excluye físicas inactivas).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CanchaService - isActive reversible y guard de desactivación por pool")
class CanchaServiceDesactivacionReversibleTest {

    @Mock
    private CanchaRepository canchaRepository;

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;

    @Mock
    private RegistroAuditoriaService registroAuditoriaService;

    @Mock
    private ComplejoDetalleCache complejoDetalleCache;

    @Mock
    private ReservaRepository reservaRepository;

    @InjectMocks
    private CanchaService canchaService;

    private Usuario dueno;
    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        dueno = Usuario.builder().id(2L).email("dueno@test.com").rol(Role.OWNER).planSuscripcion(PlanSuscripcion.PREMIUM).build();
        establecimiento = Establecimiento.builder().id(10L).nombre("Complejo Test").direccion("Calle 123")
                .latitud(-34.6).longitud(-58.4).dueno(dueno).requiereSena(true).isActive(true).build();

        lenient().when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        lenient().when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        lenient().when(canchaRepository.save(any(Cancha.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Cancha fisica(long id, String nombre, boolean activa) {
        return Cancha.builder().id(id).nombre(nombre).establecimiento(establecimiento)
                .precioBase(BigDecimal.TEN).montoSena(BigDecimal.ZERO).isActive(activa)
                .canchasFisicas(new LinkedHashSet<>()).build();
    }

    private Cancha logica(long id, String nombre, Set<Cancha> pool, int canchasNecesarias) {
        return Cancha.builder().id(id).nombre(nombre).establecimiento(establecimiento)
                .precioBase(BigDecimal.TEN).montoSena(BigDecimal.ZERO).isActive(true)
                .canchasFisicas(pool).canchasNecesarias(canchasNecesarias).build();
    }

    private CanchaRequest requestConIsActive(Cancha actual, Boolean isActive) {
        return new CanchaRequest(actual.getNombre(), new HashSet<>(Set.of(Deporte.FUTBOL_5)), actual.getPrecioBase(),
                BigDecimal.ZERO, null, null, true, null, null, null, isActive);
    }

    @Test
    @DisplayName("actualizarCancha_IsActiveNullEnRequest_NoModificaElEstadoActual")
    void actualizarCancha_IsActiveNullEnRequest_NoModificaElEstadoActual() {
        Cancha cancha = fisica(100L, "Cancha A", false);
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));

        canchaService.actualizarCancha(establecimiento.getId(), cancha.getId(), requestConIsActive(cancha, null), dueno.getEmail());

        assertThat(cancha.getIsActive()).isFalse();
        verify(reservaRepository, never()).findFuturasPorCanchaIds(any(), any());
    }

    @Test
    @DisplayName("actualizarCancha_ReactivaCancha_CuandoIsActiveTrueEnRequest")
    void actualizarCancha_ReactivaCancha_CuandoIsActiveTrueEnRequest() {
        Cancha cancha = fisica(100L, "Cancha A", false);
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));

        canchaService.actualizarCancha(establecimiento.getId(), cancha.getId(), requestConIsActive(cancha, true), dueno.getEmail());

        assertThat(cancha.getIsActive()).isTrue();
    }

    @Test
    @DisplayName("actualizarCancha_Desactiva_SinReservasFuturas_Exito")
    void actualizarCancha_Desactiva_SinReservasFuturas_Exito() {
        Cancha cancha = fisica(100L, "Cancha A", true);
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(canchaRepository.findByEstablecimientoId(establecimiento.getId())).thenReturn(List.of(cancha));
        when(reservaRepository.findFuturasPorCanchaIds(any(), any())).thenReturn(List.of());

        canchaService.actualizarCancha(establecimiento.getId(), cancha.getId(), requestConIsActive(cancha, false), dueno.getEmail());

        assertThat(cancha.getIsActive()).isFalse();
    }

    /**
     * El caso del diagnóstico: F1,F2,F3 forman el pool de C9 (necesita 3). C9 tiene una
     * reserva futura. F2 no tiene ninguna reserva propia, así que un guard que sólo mirara
     * "reservas sobre la cancha exacta" no vería nada. Desactivar F2 baja la capacidad real
     * del grupo a 2 (ver PoolCanchaCalculator.footprint), y la reserva de C9 (que necesita 3)
     * deja de ser satisfacible: debe bloquearse.
     */
    @Test
    @DisplayName("actualizarCancha_Fallo_DesactivarFisicaSinReservaPropia_RompeReservaFuturaDelGrupo")
    void actualizarCancha_Fallo_DesactivarFisicaSinReservaPropia_RompeReservaFuturaDelGrupo() {
        Cancha f1 = fisica(1L, "Fisica 1", true);
        Cancha f2 = fisica(2L, "Fisica 2", true);
        Cancha f3 = fisica(3L, "Fisica 3", true);
        Cancha c9 = logica(9L, "Cancha de 9", Set.of(f1, f2, f3), 3);

        LocalDateTime inicio = LocalDateTime.now().plusDays(2).withHour(20).withMinute(0).withSecond(0).withNano(0);
        Reserva reservaFuturaC9 = Reserva.builder().id(500L).cancha(c9).estado(EstadoReserva.CONFIRMADA)
                .fechaHoraInicio(inicio).fechaHoraFin(inicio.plusHours(1))
                .precioTotal(BigDecimal.TEN).senaPagada(BigDecimal.ZERO).build();

        when(canchaRepository.findById(f2.getId())).thenReturn(Optional.of(f2));
        when(canchaRepository.findByEstablecimientoId(establecimiento.getId())).thenReturn(List.of(f1, f2, f3, c9));
        when(reservaRepository.findFuturasPorCanchaIds(any(), any())).thenReturn(List.of(reservaFuturaC9));

        assertThatThrownBy(() -> canchaService.actualizarCancha(
                establecimiento.getId(), f2.getId(), requestConIsActive(f2, false), dueno.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cancha de 9");

        // No debe quedar la entidad mutada tras el rollback en memoria del guard.
        assertThat(f2.getIsActive()).isTrue();
        verify(canchaRepository, never()).save(any());
    }

    @Test
    @DisplayName("desactivarCancha_Fallo_ReservaFuturaDelGrupoQuedaSinCupo")
    void desactivarCancha_Fallo_ReservaFuturaDelGrupoQuedaSinCupo() {
        Cancha f1 = fisica(1L, "Fisica 1", true);
        Cancha f2 = fisica(2L, "Fisica 2", true);
        Cancha f3 = fisica(3L, "Fisica 3", true);
        Cancha c9 = logica(9L, "Cancha de 9", Set.of(f1, f2, f3), 3);

        LocalDateTime inicio = LocalDateTime.now().plusDays(3).withHour(18).withMinute(0).withSecond(0).withNano(0);
        Reserva reservaFuturaC9 = Reserva.builder().id(501L).cancha(c9).estado(EstadoReserva.CONFIRMADA)
                .fechaHoraInicio(inicio).fechaHoraFin(inicio.plusHours(1))
                .precioTotal(BigDecimal.TEN).senaPagada(BigDecimal.ZERO).build();

        when(canchaRepository.findById(f1.getId())).thenReturn(Optional.of(f1));
        when(canchaRepository.findByEstablecimientoId(establecimiento.getId())).thenReturn(List.of(f1, f2, f3, c9));
        when(reservaRepository.findFuturasPorCanchaIds(any(), any())).thenReturn(List.of(reservaFuturaC9));

        assertThatThrownBy(() -> canchaService.desactivarCancha(establecimiento.getId(), f1.getId(), dueno.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cancha de 9");

        assertThat(f1.getIsActive()).isTrue();
        verify(canchaRepository, never()).save(any());
    }

    /**
     * Mismo escenario que el bloqueado, pero desactivando una física que sobra: C9 necesita
     * 3 de un pool de 4 (F1..F4). Sacar F4 deja 3 físicas activas, capacidad suficiente para
     * la reserva existente: no debe bloquear.
     */
    @Test
    @DisplayName("desactivarCancha_Exito_CuandoElGrupoSigueTeniendoCapacidadParaLasReservasFuturas")
    void desactivarCancha_Exito_CuandoElGrupoSigueTeniendoCapacidadParaLasReservasFuturas() {
        Cancha f1 = fisica(1L, "Fisica 1", true);
        Cancha f2 = fisica(2L, "Fisica 2", true);
        Cancha f3 = fisica(3L, "Fisica 3", true);
        Cancha f4 = fisica(4L, "Fisica 4", true);
        Cancha c9 = logica(9L, "Cancha de 9", Set.of(f1, f2, f3, f4), 3);

        LocalDateTime inicio = LocalDateTime.now().plusDays(3).withHour(18).withMinute(0).withSecond(0).withNano(0);
        Reserva reservaFuturaC9 = Reserva.builder().id(502L).cancha(c9).estado(EstadoReserva.CONFIRMADA)
                .fechaHoraInicio(inicio).fechaHoraFin(inicio.plusHours(1))
                .precioTotal(BigDecimal.TEN).senaPagada(BigDecimal.ZERO).build();

        when(canchaRepository.findById(f4.getId())).thenReturn(Optional.of(f4));
        when(canchaRepository.findByEstablecimientoId(establecimiento.getId())).thenReturn(List.of(f1, f2, f3, f4, c9));
        when(reservaRepository.findFuturasPorCanchaIds(any(), any())).thenReturn(List.of(reservaFuturaC9));

        assertDoesNotThrow(() -> canchaService.desactivarCancha(establecimiento.getId(), f4.getId(), dueno.getEmail()));

        assertThat(f4.getIsActive()).isFalse();
        verify(canchaRepository).save(f4);
    }

    @Test
    @DisplayName("obtenerCanchasPorEstablecimiento_IncluirInactivas_UsaElFinderSinFiltroDeIsActive")
    void obtenerCanchasPorEstablecimiento_IncluirInactivas_UsaElFinderSinFiltroDeIsActive() {
        Cancha activa = fisica(1L, "Activa", true);
        Cancha inactiva = fisica(2L, "Inactiva", false);
        when(canchaRepository.findByEstablecimientoId(establecimiento.getId())).thenReturn(List.of(activa, inactiva));

        var respuesta = canchaService.obtenerCanchasPorEstablecimiento(establecimiento.getId(), dueno.getEmail(), true);

        assertThat(respuesta).extracting("nombre").containsExactlyInAnyOrder("Activa", "Inactiva");
    }

    @Test
    @DisplayName("obtenerCanchasPorEstablecimiento_SinIncluirInactivas_SoloTraeActivas")
    void obtenerCanchasPorEstablecimiento_SinIncluirInactivas_SoloTraeActivas() {
        Cancha activa = fisica(1L, "Activa", true);
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId())).thenReturn(List.of(activa));

        var respuesta = canchaService.obtenerCanchasPorEstablecimiento(establecimiento.getId(), dueno.getEmail(), false);

        assertThat(respuesta).extracting("nombre").containsExactly("Activa");
        verify(canchaRepository, never()).findByEstablecimientoId(anyLong());
    }

    /**
     * Un EMPLOYEE con permisos operativos (cobrar/cancelar/marcar ausente) pasa
     * validarLectura y puede ver el listado de canchas ACTIVAS (lo necesita para la agenda),
     * pero no tiene por qué ver las inactivas: no puede reactivarlas ni gestionarlas, y
     * ninguno de sus permisos operativos habilita esa visibilidad. incluirInactivas=true
     * exige dueño/admin (validarPropietarioOAdmin), así que a este empleado se lo rechaza
     * aunque igual pudiera pedir el listado con incluirInactivas=false.
     */
    @Test
    @DisplayName("obtenerCanchasPorEstablecimiento_IncluirInactivas_EmpleadoConPermisoOperativo_Rechaza")
    void obtenerCanchasPorEstablecimiento_IncluirInactivas_EmpleadoConPermisoOperativo_Rechaza() {
        Usuario empleado = Usuario.builder().id(5L).email("empleado@test.com").rol(Role.EMPLOYEE).build();
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, empleado.getEmail()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("No autorizado en este establecimiento"));

        assertThatThrownBy(() -> canchaService.obtenerCanchasPorEstablecimiento(establecimiento.getId(), empleado.getEmail(), true))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verify(canchaRepository, never()).findByEstablecimientoId(anyLong());
    }
}
