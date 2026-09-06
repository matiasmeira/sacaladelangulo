package com.matiasmeira.sacaladelangulo.cliente.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.cliente.dto.ClienteDetalleResponse;
import com.matiasmeira.sacaladelangulo.cliente.dto.ClienteResponse;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoJugador;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoJugadorRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaMapper;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaResponse;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClienteService")
class ClienteServiceTest {

    @Mock
    private ReservaRepository reservaRepository;
    @Mock
    private UsuarioRepository usuarioRepository;
    @Mock
    private BloqueoJugadorRepository bloqueoJugadorRepository;
    @Mock
    private EstablecimientoRepository establecimientoRepository;
    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;
    @Mock
    private ReservaMapper reservaMapper;

    @InjectMocks
    private ClienteService clienteService;

    @Test
    @DisplayName("Cuenta reservasTotales/totalGastado solo con FINALIZADA y ausencias solo con AUSENTE")
    void listarClientes_CuentaSoloFinalizadaParaTotalesYGastado_AusenteParaAusencias() {
        Long establecimientoId = 5L;
        when(establecimientoRepository.findById(establecimientoId)).thenReturn(Optional.of(mock(Establecimiento.class)));

        Usuario jugador = Usuario.builder().id(1L).nombre("Juan Perez").telefono("1122334455").email("juan@test.com").build();

        when(reservaRepository.jugadorIdsDelEstablecimiento(establecimientoId)).thenReturn(List.of(1L));
        when(reservaRepository.historicoAgregadoPorJugador(establecimientoId)).thenReturn(List.<Object[]>of(
                new Object[]{1L, 3L, new BigDecimal("300.00"), LocalDateTime.of(2026, 1, 10, 10, 0)}
        ));
        when(reservaRepository.countAusenciasPorJugador(establecimientoId)).thenReturn(List.<Object[]>of(
                new Object[]{1L, 1L}
        ));
        when(usuarioRepository.findAllById(List.of(1L))).thenReturn(List.of(jugador));
        when(bloqueoJugadorRepository.findByEstablecimientoIdOrderByFechaBloqueoDesc(establecimientoId)).thenReturn(List.of());

        Page<ClienteResponse> resultado = clienteService.listarClientes(
                establecimientoId, null, null, PageRequest.of(0, 20), "dueno@test.com");

        assertEquals(1, resultado.getTotalElements());
        ClienteResponse cliente = resultado.getContent().get(0);
        assertEquals(3L, cliente.reservasTotales());
        assertEquals(1L, cliente.ausencias());
        assertEquals(new BigDecimal("300.00"), cliente.totalGastado());
        assertEquals(Boolean.FALSE, cliente.bloqueado());
    }

    @Test
    @DisplayName("buscar matchea nombre, telefono o email, case-insensitive")
    void listarClientes_Buscar_MatcheaNombreTelefonoEmailCaseInsensitive() {
        Long establecimientoId = 5L;
        when(establecimientoRepository.findById(establecimientoId)).thenReturn(Optional.of(mock(Establecimiento.class)));

        Usuario juan = Usuario.builder().id(1L).nombre("Juan Perez").telefono("1122334455").email("juan@test.com").build();
        Usuario maria = Usuario.builder().id(2L).nombre("Maria Lopez").telefono("5566778899").email("maria@test.com").build();

        when(reservaRepository.jugadorIdsDelEstablecimiento(establecimientoId)).thenReturn(List.of(1L, 2L));
        when(reservaRepository.historicoAgregadoPorJugador(establecimientoId)).thenReturn(List.of());
        when(reservaRepository.countAusenciasPorJugador(establecimientoId)).thenReturn(List.of());
        when(usuarioRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(juan, maria));
        when(bloqueoJugadorRepository.findByEstablecimientoIdOrderByFechaBloqueoDesc(establecimientoId)).thenReturn(List.of());

        Page<ClienteResponse> resultado = clienteService.listarClientes(
                establecimientoId, "PEREZ", null, PageRequest.of(0, 20), "dueno@test.com");

        assertEquals(1, resultado.getTotalElements());
        assertEquals("Juan Perez", resultado.getContent().get(0).nombre());
    }

    @Test
    @DisplayName("soloBloqueados=true devuelve unicamente los jugadores con BloqueoJugador vigente")
    void listarClientes_SoloBloqueados_FiltraPorBloqueoVigente() {
        Long establecimientoId = 5L;
        when(establecimientoRepository.findById(establecimientoId)).thenReturn(Optional.of(mock(Establecimiento.class)));

        Usuario juan = Usuario.builder().id(1L).nombre("Juan Perez").email("juan@test.com").build();
        Usuario maria = Usuario.builder().id(2L).nombre("Maria Lopez").email("maria@test.com").build();
        Usuario jugadorBloqueado = Usuario.builder().id(2L).nombre("Maria Lopez").build();
        BloqueoJugador bloqueo = BloqueoJugador.builder().jugador(jugadorBloqueado).motivo("Reincidente").build();

        when(reservaRepository.jugadorIdsDelEstablecimiento(establecimientoId)).thenReturn(List.of(1L, 2L));
        when(reservaRepository.historicoAgregadoPorJugador(establecimientoId)).thenReturn(List.of());
        when(reservaRepository.countAusenciasPorJugador(establecimientoId)).thenReturn(List.of());
        when(usuarioRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(juan, maria));
        when(bloqueoJugadorRepository.findByEstablecimientoIdOrderByFechaBloqueoDesc(establecimientoId)).thenReturn(List.of(bloqueo));

        Page<ClienteResponse> resultado = clienteService.listarClientes(
                establecimientoId, null, true, PageRequest.of(0, 20), "dueno@test.com");

        assertEquals(1, resultado.getTotalElements());
        assertEquals(2L, resultado.getContent().get(0).jugadorId());
        assertEquals(Boolean.TRUE, resultado.getContent().get(0).bloqueado());
    }

    @Test
    @DisplayName("El tamanio de pagina se limita a 100 aunque se pida mas")
    void listarClientes_CapaTamanioDePaginaA100() {
        Long establecimientoId = 5L;
        when(establecimientoRepository.findById(establecimientoId)).thenReturn(Optional.of(mock(Establecimiento.class)));
        when(reservaRepository.jugadorIdsDelEstablecimiento(establecimientoId)).thenReturn(List.of());

        Page<ClienteResponse> resultado = clienteService.listarClientes(
                establecimientoId, null, null, PageRequest.of(0, 500), "dueno@test.com");

        assertEquals(100, resultado.getPageable().getPageSize());
    }

    @Test
    @DisplayName("Propaga AccessDeniedException si el usuario no es dueno del establecimiento")
    void listarClientes_Fallo_NoEsDuenoDelEstablecimiento() {
        Long establecimientoId = 5L;
        Establecimiento establecimiento = mock(Establecimiento.class);
        when(establecimientoRepository.findById(establecimientoId)).thenReturn(Optional.of(establecimiento));
        org.mockito.Mockito.doThrow(new org.springframework.security.access.AccessDeniedException("No autorizado en este establecimiento"))
                .when(autorizacionEmpleadoService).validarPropietarioOAdmin(establecimiento, "otro@test.com");

        assertThrows(org.springframework.security.access.AccessDeniedException.class, () ->
                clienteService.listarClientes(establecimientoId, null, null, PageRequest.of(0, 20), "otro@test.com"));
    }

    @Test
    @DisplayName("obtenerDetalle: 404 si el jugador nunca reservo en este establecimiento")
    void obtenerDetalle_Fallo_JugadorNuncaReservoEnEsteEstablecimiento() {
        Long establecimientoId = 5L;
        Long jugadorId = 99L;
        when(establecimientoRepository.findById(establecimientoId)).thenReturn(Optional.of(mock(Establecimiento.class)));
        when(reservaRepository.existsByJugador_IdAndCancha_Establecimiento_Id(jugadorId, establecimientoId)).thenReturn(false);

        assertThrows(EntityNotFoundException.class, () ->
                clienteService.obtenerDetalle(establecimientoId, jugadorId, "dueno@test.com"));
    }

    @Test
    @DisplayName("obtenerDetalle: trae motivoBloqueo y fechaPrimeraReserva del jugador")
    void obtenerDetalle_TraeMotivoBloqueoYFechaPrimeraReserva() {
        Long establecimientoId = 5L;
        Long jugadorId = 1L;
        LocalDateTime primeraReserva = LocalDateTime.of(2025, 6, 1, 9, 0);

        when(establecimientoRepository.findById(establecimientoId)).thenReturn(Optional.of(mock(Establecimiento.class)));
        when(reservaRepository.existsByJugador_IdAndCancha_Establecimiento_Id(jugadorId, establecimientoId)).thenReturn(true);
        when(usuarioRepository.findById(jugadorId)).thenReturn(Optional.of(
                Usuario.builder().id(jugadorId).nombre("Juan Perez").email("juan@test.com").build()));
        when(reservaRepository.historicoAgregadoDeJugador(establecimientoId, jugadorId))
                .thenReturn(List.<Object[]>of(new Object[]{3L, new BigDecimal("300.00"), LocalDateTime.of(2026, 1, 10, 10, 0)}));
        when(reservaRepository.countAusenciasDeJugador(establecimientoId, jugadorId)).thenReturn(1L);
        when(bloqueoJugadorRepository.findByEstablecimientoIdAndJugadorId(establecimientoId, jugadorId))
                .thenReturn(Optional.of(BloqueoJugador.builder().motivo("Reincidente").build()));
        when(reservaRepository.primeraReservaPorJugador(establecimientoId)).thenReturn(List.<Object[]>of(
                new Object[]{jugadorId, primeraReserva},
                new Object[]{2L, LocalDateTime.of(2020, 1, 1, 0, 0)}
        ));

        ClienteDetalleResponse detalle = clienteService.obtenerDetalle(establecimientoId, jugadorId, "dueno@test.com");

        assertEquals("Reincidente", detalle.motivoBloqueo());
        assertEquals(primeraReserva, detalle.fechaPrimeraReserva());
        assertEquals(3L, detalle.cliente().reservasTotales());
        assertEquals(1L, detalle.cliente().ausencias());
        assertEquals(Boolean.TRUE, detalle.cliente().bloqueado());
    }

    @Test
    @DisplayName("listarReservasDeCliente devuelve todas las reservas del jugador en el establecimiento, sin filtrar por estado")
    void listarReservasDeCliente_DevuelveTodasLasReservasDelJugadorEnElEstablecimiento() {
        Long establecimientoId = 5L;
        Long jugadorId = 1L;
        when(establecimientoRepository.findById(establecimientoId)).thenReturn(Optional.of(mock(Establecimiento.class)));

        Reserva reserva = mock(Reserva.class);
        ReservaResponse reservaResponse = new ReservaResponse(
                10L, jugadorId, "Juan Perez", 2L, "Cancha 1",
                LocalDateTime.of(2026, 1, 5, 18, 0), LocalDateTime.of(2026, 1, 5, 19, 0),
                "AUSENTE", new BigDecimal("100.00"), BigDecimal.ZERO, null, null, null, null, null, null);

        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "fechaHoraInicio"));
        when(reservaRepository.findByJugador_IdAndCancha_Establecimiento_Id(jugadorId, establecimientoId, pageable))
                .thenReturn(new PageImpl<>(List.of(reserva)));
        when(reservaMapper.mapToResponse(reserva)).thenReturn(reservaResponse);

        Page<ReservaResponse> resultado = clienteService.listarReservasDeCliente(establecimientoId, jugadorId, pageable, "dueno@test.com");

        assertEquals(1, resultado.getTotalElements());
        assertEquals("AUSENTE", resultado.getContent().get(0).estado());
    }
}
