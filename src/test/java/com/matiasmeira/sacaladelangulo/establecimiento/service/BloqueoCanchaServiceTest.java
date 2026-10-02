package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.BloqueoCanchaRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.BloqueoCanchaResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaMapper;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaResponse;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BloqueoCanchaService - Tests de bloqueos y reubicación de reservas afectadas")
class BloqueoCanchaServiceTest {

    @Mock
    private BloqueoCanchaRepository bloqueoCanchaRepository;

    @Mock
    private CanchaRepository canchaRepository;

    @Mock
    private com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository establecimientoRepository;

    @Mock
    private ReservaRepository reservaRepository;

    @Mock
    private ReservaMapper reservaMapper;

    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;

    @Mock
    private EstablecimientoOperativoGuard establecimientoOperativoGuard;

    @InjectMocks
    private BloqueoCanchaService bloqueoCanchaService;

    private Usuario dueno;
    private Usuario jugador;
    private Establecimiento establecimiento;
    private Cancha cancha5A;
    private Cancha cancha5B;

    @BeforeEach
    void setUp() {
        dueno = Usuario.builder()
                .id(2L)
                .email("dueno@test.com")
                .password("password")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build();

        jugador = Usuario.builder()
                .id(1L)
                .email("jugador@test.com")
                .password("password")
                .nombre("Juan")
                .rol(Role.PLAYER)
                .planSuscripcion(PlanSuscripcion.FREE)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build();

        establecimiento = Establecimientos.establecimientoOperativo(b -> b
                .id(10L)
                .nombre("Establecimiento Test")
                .direccion("Calle Test 123")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .dueno(dueno)
                .requiereSena(true));

        cancha5A = Cancha.builder()
                .id(100L)
                .nombre("Cancha 5A")
                .deportes(Set.of(Deporte.FUTBOL_5))
                .precioBase(BigDecimal.valueOf(1500))
                .montoSena(BigDecimal.valueOf(500))
                .duracionesPermitidas(new ArrayList<>(List.of(60)))
                .permiteInicioMediaHora(false)
                .establecimiento(establecimiento)
                .isActive(true)
                .tarifas(new ArrayList<>())
                .canchasFisicas(new java.util.LinkedHashSet<>())
                .build();

        cancha5B = Cancha.builder()
                .id(101L)
                .nombre("Cancha 5B")
                .deportes(Set.of(Deporte.FUTBOL_5))
                .precioBase(BigDecimal.valueOf(1500))
                .montoSena(BigDecimal.valueOf(500))
                .duracionesPermitidas(new ArrayList<>(List.of(60)))
                .permiteInicioMediaHora(false)
                .establecimiento(establecimiento)
                .isActive(true)
                .tarifas(new ArrayList<>())
                .canchasFisicas(new java.util.LinkedHashSet<>())
                .build();

        lenient().when(reservaMapper.mapToResponse(any(Reserva.class))).thenAnswer(invocation -> {
            Reserva reserva = invocation.getArgument(0);
            return new ReservaResponse(
                    reserva.getId(),
                    reserva.getJugador() != null ? reserva.getJugador().getId() : null,
                    reserva.getJugador() != null ? reserva.getJugador().getNombre() : null,
                    reserva.getCancha().getId(),
                    reserva.getCancha().getNombre(),
                    reserva.getFechaHoraInicio(),
                    reserva.getFechaHoraFin(),
                    reserva.getEstado().name(),
                    reserva.getPrecioTotal(),
                    reserva.getSenaPagada(),
                    reserva.getNombreClienteManual(),
                    reserva.getTelefonoClienteManual(),
                    reserva.getDeporteSeleccionado(),
                    reserva.getExpiraEn(),
                    reserva.getMetodoPago() != null ? reserva.getMetodoPago().name() : null,
                    reserva.getTurnoFijo() != null ? reserva.getTurnoFijo().getId() : null
            );
        });

        lenient().when(bloqueoCanchaRepository.save(any(BloqueoCancha.class))).thenAnswer(invocation -> {
            BloqueoCancha bloqueo = invocation.getArgument(0);
            bloqueo.setId(1L);
            return bloqueo;
        });
    }

    @Test
    @DisplayName("crearBloqueo_Exito_SinReservasAfectadas")
    void crearBloqueo_Exito_SinReservasAfectadas() {
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        // Arrange
        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 10, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 12, 0);
        BloqueoCanchaRequest request = new BloqueoCanchaRequest(fechaInicio, fechaFin, "Mantenimiento");

        when(canchaRepository.findById(cancha5A.getId())).thenReturn(Optional.of(cancha5A));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(reservaRepository.findOverlappingByCanchaId(cancha5A.getId(), fechaInicio, fechaFin)).thenReturn(List.of());

        // Act
        BloqueoCanchaResponse response = assertDoesNotThrow(
                () -> bloqueoCanchaService.crearBloqueo(establecimiento.getId(), cancha5A.getId(), request, dueno.getEmail()));

        // Assert
        assert response.reservasAfectadas().isEmpty();
        verify(bloqueoCanchaRepository).save(any(BloqueoCancha.class));
    }

    /**
     * A diferencia de una cancha simplemente inactiva (ver el comentario de
     * buscarCanchaDelEstablecimiento: un bloqueo sobre una inactiva es redundante, no
     * peligroso), una cancha ELIMINADA desapareció de todas las vistas -- no tiene sentido
     * dejar crear un bloqueo nuevo sobre algo que el dueño ya no puede ver ni administrar.
     */
    @Test
    @DisplayName("crearBloqueo_Fallo_CanchaEliminada")
    void crearBloqueo_Fallo_CanchaEliminada() {
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        Cancha canchaEliminada = com.matiasmeira.sacaladelangulo.support.Canchas.canchaEliminada(
                establecimiento, b -> b.id(cancha5A.getId()).nombre(cancha5A.getNombre()));
        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 10, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 12, 0);
        BloqueoCanchaRequest request = new BloqueoCanchaRequest(fechaInicio, fechaFin, "Mantenimiento");

        when(canchaRepository.findById(canchaEliminada.getId())).thenReturn(Optional.of(canchaEliminada));

        assertThrows(
                com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException.class,
                () -> bloqueoCanchaService.crearBloqueo(establecimiento.getId(), canchaEliminada.getId(), request, dueno.getEmail())
        );
        verify(bloqueoCanchaRepository, never()).save(any());
    }

    @Test
    @DisplayName("crearBloqueo_Fallo_FechaInicioIgualFechaFin")
    void crearBloqueo_Fallo_FechaInicioIgualFechaFin() {
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(canchaRepository.findById(cancha5A.getId())).thenReturn(Optional.of(cancha5A));
        // Arrange: bloqueo de duración cero, antes se aceptaba y no bloqueaba nada realmente
        LocalDateTime fecha = LocalDateTime.of(2030, 1, 15, 10, 0);
        BloqueoCanchaRequest request = new BloqueoCanchaRequest(fecha, fecha, "Mantenimiento");

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> bloqueoCanchaService.crearBloqueo(establecimiento.getId(), cancha5A.getId(), request, dueno.getEmail())
        );
        assert exception.getMessage().contains("anterior");
        verify(bloqueoCanchaRepository, never()).save(any());
    }

    @Test
    @DisplayName("crearBloqueo_Fallo_UsuarioNoEsDuenoDelEstablecimiento")
    void crearBloqueo_Fallo_UsuarioNoEsDuenoDelEstablecimiento() {
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        // Arrange
        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 10, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 12, 0);
        BloqueoCanchaRequest request = new BloqueoCanchaRequest(fechaInicio, fechaFin, "Mantenimiento");

        Usuario otroDueno = Usuario.builder()
                .id(3L)
                .email("otro-dueno@test.com")
                .password("password")
                .nombre("Otro")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build();

        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, otroDueno.getEmail()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("No autorizado en este establecimiento"));

        // Act & Assert
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> bloqueoCanchaService.crearBloqueo(establecimiento.getId(), cancha5A.getId(), request, otroDueno.getEmail())
        );
        verify(canchaRepository, never()).findById(any());
        verify(bloqueoCanchaRepository, never()).save(any());
    }

    @Test
    @DisplayName("crearBloqueo_Fallo_EstablecimientoDeshabilitado")
    void crearBloqueo_Fallo_EstablecimientoDeshabilitado() {
        Establecimiento deshabilitado = Establecimientos.establecimientoDeshabilitado(b -> b.id(20L).dueno(dueno));
        Cancha canchaDelDeshabilitado = Cancha.builder().id(200L).nombre("Cancha A").establecimiento(deshabilitado).isActive(true).build();
        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 10, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 12, 0);
        BloqueoCanchaRequest request = new BloqueoCanchaRequest(fechaInicio, fechaFin, "Mantenimiento");

        when(establecimientoRepository.findById(deshabilitado.getId())).thenReturn(Optional.of(deshabilitado));
        when(canchaRepository.findById(canchaDelDeshabilitado.getId())).thenReturn(Optional.of(canchaDelDeshabilitado));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(deshabilitado, dueno.getEmail())).thenReturn(dueno);
        org.mockito.Mockito.doThrow(new org.springframework.security.access.AccessDeniedException("Este establecimiento está deshabilitado."))
                .when(establecimientoOperativoGuard).validarPuedeGenerarCompromisosNuevos(deshabilitado);

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> bloqueoCanchaService.crearBloqueo(deshabilitado.getId(), canchaDelDeshabilitado.getId(), request, dueno.getEmail()));
        verify(bloqueoCanchaRepository, never()).save(any());
    }

    @Test
    @DisplayName("crearBloqueo_ConReservaAfectada_EncuentraCanchaHermanaDisponible")
    void crearBloqueo_ConReservaAfectada_EncuentraCanchaHermanaDisponible() {
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        // Arrange
        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 10, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 12, 0);
        BloqueoCanchaRequest request = new BloqueoCanchaRequest(fechaInicio, fechaFin, "Mantenimiento");

        Reserva reservaAfectada = Reserva.builder()
                .id(50L)
                .jugador(jugador)
                .cancha(cancha5A)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(canchaRepository.findById(cancha5A.getId())).thenReturn(Optional.of(cancha5A));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(reservaRepository.findOverlappingByCanchaId(cancha5A.getId(), fechaInicio, fechaFin))
                .thenReturn(List.of(reservaAfectada));
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId()))
                .thenReturn(List.of(cancha5A, cancha5B));
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(eq(establecimiento.getId()), any(), any()))
                .thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(eq(establecimiento.getId()), any(), any(), any()))
                .thenReturn(List.of());

        // Act
        BloqueoCanchaResponse response = assertDoesNotThrow(
                () -> bloqueoCanchaService.crearBloqueo(establecimiento.getId(), cancha5A.getId(), request, dueno.getEmail()));

        // Assert
        assert response.reservasAfectadas().size() == 1;
        assert response.reservasAfectadas().get(0).canchasAlternativasDisponibles().size() == 1;
        assert response.reservasAfectadas().get(0).canchasAlternativasDisponibles().get(0).id().equals(cancha5B.getId());
    }

    @Test
    @DisplayName("crearBloqueo_ConReservaAfectada_SinAlternativaSiLaHermanaTambienEstaOcupada")
    void crearBloqueo_ConReservaAfectada_SinAlternativaSiLaHermanaTambienEstaOcupada() {
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        // Arrange
        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 10, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 12, 0);
        BloqueoCanchaRequest request = new BloqueoCanchaRequest(fechaInicio, fechaFin, "Mantenimiento");

        Reserva reservaAfectada = Reserva.builder()
                .id(50L)
                .jugador(jugador)
                .cancha(cancha5A)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        Reserva otraReservaEnCancha5B = Reserva.builder()
                .id(51L)
                .jugador(jugador)
                .cancha(cancha5B)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(canchaRepository.findById(cancha5A.getId())).thenReturn(Optional.of(cancha5A));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(reservaRepository.findOverlappingByCanchaId(cancha5A.getId(), fechaInicio, fechaFin))
                .thenReturn(List.of(reservaAfectada));
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId()))
                .thenReturn(List.of(cancha5A, cancha5B));
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(eq(establecimiento.getId()), any(), any()))
                .thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(eq(establecimiento.getId()), any(), any(), any()))
                .thenReturn(List.of(otraReservaEnCancha5B));

        // Act
        BloqueoCanchaResponse response = assertDoesNotThrow(
                () -> bloqueoCanchaService.crearBloqueo(establecimiento.getId(), cancha5A.getId(), request, dueno.getEmail()));

        // Assert
        assert response.reservasAfectadas().size() == 1;
        assert response.reservasAfectadas().get(0).canchasAlternativasDisponibles().isEmpty();
    }

    @Test
    @DisplayName("eliminarBloqueo_Fallo_BloqueoNoPerteneceACancha")
    void eliminarBloqueo_Fallo_BloqueoNoPerteneceACancha() {
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        // Arrange
        BloqueoCancha bloqueo = BloqueoCancha.builder()
                .id(1L)
                .cancha(cancha5A)
                .fechaInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaFin(LocalDateTime.of(2030, 1, 15, 12, 0))
                .motivo("Mantenimiento")
                .build();

        when(bloqueoCanchaRepository.findById(bloqueo.getId())).thenReturn(Optional.of(bloqueo));

        // Act & Assert: se pide eliminar pasando el id de la cancha 5B, que no es la del bloqueo: mismo 404
        // que un bloqueo inexistente
        assertThrows(
                com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException.class,
                () -> bloqueoCanchaService.eliminarBloqueo(establecimiento.getId(), cancha5B.getId(), bloqueo.getId(), dueno.getEmail())
        );
        verify(bloqueoCanchaRepository, never()).delete(any());
    }

    @Test
    @DisplayName("eliminarBloqueo_Exito")
    void eliminarBloqueo_Exito() {
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        // Arrange
        BloqueoCancha bloqueo = BloqueoCancha.builder()
                .id(1L)
                .cancha(cancha5A)
                .fechaInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaFin(LocalDateTime.of(2030, 1, 15, 12, 0))
                .motivo("Mantenimiento")
                .build();

        when(bloqueoCanchaRepository.findById(bloqueo.getId())).thenReturn(Optional.of(bloqueo));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);

        // Act
        assertDoesNotThrow(() -> bloqueoCanchaService.eliminarBloqueo(
                establecimiento.getId(), cancha5A.getId(), bloqueo.getId(), dueno.getEmail()));

        // Assert
        verify(bloqueoCanchaRepository).delete(bloqueo);
    }

    private BloqueoCancha bloqueoConMotivo() {
        return BloqueoCancha.builder()
                .id(1L)
                .cancha(cancha5A)
                .fechaInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaFin(LocalDateTime.of(2030, 1, 15, 12, 0))
                .motivo("Reclamo del proveedor de mantenimiento")
                .build();
    }

    @Test
    @DisplayName("listarPorEstablecimientoYFecha_ConAccesoDePanel_IncluyeElMotivo")
    void listarPorEstablecimientoYFecha_ConAccesoDePanel_IncluyeElMotivo() {
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(eq(establecimiento.getId()), any(), any()))
                .thenReturn(List.of(bloqueoConMotivo()));
        when(autorizacionEmpleadoService.tieneAccesoDePanel(
                eq(establecimiento), eq(dueno.getEmail()), eq(EnumSet.allOf(PermisoEmpleado.class))))
                .thenReturn(true);

        List<BloqueoCanchaResponse> respuesta = bloqueoCanchaService.listarPorEstablecimientoYFecha(
                establecimiento.getId(), LocalDate.of(2030, 1, 15), dueno.getEmail());

        assertEquals(1, respuesta.size());
        assertEquals("Reclamo del proveedor de mantenimiento", respuesta.get(0).motivo());
    }

    @Test
    @DisplayName("listarPorEstablecimientoYFecha_SinAccesoDePanel_OcultaElMotivo")
    void listarPorEstablecimientoYFecha_SinAccesoDePanel_OcultaElMotivo() {
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(eq(establecimiento.getId()), any(), any()))
                .thenReturn(List.of(bloqueoConMotivo()));
        when(autorizacionEmpleadoService.tieneAccesoDePanel(
                eq(establecimiento), eq(jugador.getEmail()), eq(EnumSet.allOf(PermisoEmpleado.class))))
                .thenReturn(false);

        List<BloqueoCanchaResponse> respuesta = bloqueoCanchaService.listarPorEstablecimientoYFecha(
                establecimiento.getId(), LocalDate.of(2030, 1, 15), jugador.getEmail());

        assertEquals(1, respuesta.size());
        assertNull(respuesta.get(0).motivo());
        verify(autorizacionEmpleadoService).tieneAccesoDePanel(
                establecimiento, jugador.getEmail(), EnumSet.allOf(PermisoEmpleado.class));
    }

    @Test
    @DisplayName("listarPorEstablecimientoYFecha_SinBloqueos_DevuelveVacioSinConsultarLaRegla")
    void listarPorEstablecimientoYFecha_SinBloqueos_DevuelveVacioSinConsultarLaRegla() {
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(eq(establecimiento.getId()), any(), any()))
                .thenReturn(List.of());

        List<BloqueoCanchaResponse> respuesta = bloqueoCanchaService.listarPorEstablecimientoYFecha(
                establecimiento.getId(), LocalDate.of(2030, 1, 15), dueno.getEmail());

        assertEquals(List.of(), respuesta);
        verifyNoInteractions(autorizacionEmpleadoService);
    }
}
