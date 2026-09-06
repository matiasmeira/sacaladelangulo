package com.matiasmeira.sacaladelangulo.reserva.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.email.EmailRenderer;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
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

import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservaNotificacionListener - Envío de emails de reserva confirmada")
class ReservaNotificacionListenerTest {

    @Mock
    private ReservaRepository reservaRepository;

    @Mock
    private EmailRenderer emailRenderer;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private ReservaNotificacionListener listener;

    private Usuario dueno;
    private Establecimiento establecimiento;
    private Cancha cancha;

    @BeforeEach
    void setUp() {
        dueno = Usuario.builder()
                .id(2L)
                .email("dueno@test.com")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .build();

        establecimiento = Establecimiento.builder()
                .id(10L)
                .nombre("Establecimiento Test")
                .dueno(dueno)
                .build();

        cancha = Cancha.builder()
                .id(100L)
                .nombre("Cancha A")
                .establecimiento(establecimiento)
                .build();
    }

    @Test
    @DisplayName("enviarNotificacionesConfirmacion_ConJugador_EnviaMailAlJugadorYAlDueno")
    void enviarNotificacionesConfirmacion_ConJugador_EnviaMailAlJugadorYAlDueno() {
        Usuario jugador = Usuario.builder()
                .id(1L)
                .email("jugador@test.com")
                .nombre("Juan")
                .rol(Role.PLAYER)
                .build();

        Reserva reserva = Reserva.builder()
                .id(50L)
                .jugador(jugador)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(50L)).thenReturn(Optional.of(reserva));
        when(emailRenderer.render(eq("reserva-confirmada"), anyMap())).thenReturn("<html>jugador</html>");
        when(emailRenderer.render(eq("reserva-nueva-dueno"), anyMap())).thenReturn("<html>dueno</html>");

        listener.enviarNotificacionesConfirmacion(new ReservaConfirmadaEvent(50L));

        verify(emailService).enviar(eq("jugador@test.com"), eq("Tu reserva fue confirmada"), eq("<html>jugador</html>"));
        verify(emailService).enviar(eq("dueno@test.com"), eq("Nueva reserva confirmada en tu establecimiento"), eq("<html>dueno</html>"));
    }

    @Test
    @DisplayName("enviarNotificacionesConfirmacion_ReservaManualSinJugador_EnviaSoloMailAlDueno")
    void enviarNotificacionesConfirmacion_ReservaManualSinJugador_EnviaSoloMailAlDueno() {
        Reserva reserva = Reserva.builder()
                .id(51L)
                .jugador(null)
                .nombreClienteManual("Cliente Mostrador")
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(51L)).thenReturn(Optional.of(reserva));
        when(emailRenderer.render(eq("reserva-nueva-dueno"), anyMap())).thenReturn("<html>dueno</html>");

        listener.enviarNotificacionesConfirmacion(new ReservaConfirmadaEvent(51L));

        verify(emailService, never()).enviar(eq("jugador@test.com"), any(), any());
        verify(emailRenderer, never()).render(eq("reserva-confirmada"), anyMap());
        verify(emailService).enviar(eq("dueno@test.com"), eq("Nueva reserva confirmada en tu establecimiento"), eq("<html>dueno</html>"));
    }

    @Test
    @DisplayName("enviarNotificacionesConfirmacion_ReservaNoEncontrada_NoEnviaNingunMailNiLanzaExcepcion")
    void enviarNotificacionesConfirmacion_ReservaNoEncontrada_NoEnviaNingunMailNiLanzaExcepcion() {
        when(reservaRepository.findByIdConEstablecimientoYDueno(999L)).thenReturn(Optional.empty());

        listener.enviarNotificacionesConfirmacion(new ReservaConfirmadaEvent(999L));

        verify(emailService, never()).enviar(any(), any(), any());
        verify(emailRenderer, never()).render(any(), anyMap());
    }

    @Test
    @DisplayName("enviarNotificacionesCancelacion_CanceladaPorElJugador_EnviaReciboAlJugadorYLiberacionAlDueno")
    void enviarNotificacionesCancelacion_CanceladaPorElJugador_EnviaReciboAlJugadorYLiberacionAlDueno() {
        Usuario jugador = Usuario.builder()
                .id(1L)
                .email("jugador@test.com")
                .nombre("Juan")
                .rol(Role.PLAYER)
                .build();

        Reserva reserva = Reserva.builder()
                .id(50L)
                .jugador(jugador)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CANCELADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(50L)).thenReturn(Optional.of(reserva));
        when(emailRenderer.render(eq("reserva-cancelada-jugador"), anyMap())).thenReturn("<html>jugador</html>");
        when(emailRenderer.render(eq("reserva-liberada-dueno"), anyMap())).thenReturn("<html>dueno</html>");

        listener.enviarNotificacionesCancelacion(new ReservaCanceladaEvent(50L, 1L));

        verify(emailService).enviar(eq("jugador@test.com"), eq("Cancelaste tu reserva"), eq("<html>jugador</html>"));
        verify(emailService).enviar(eq("dueno@test.com"), eq("Se liberó una cancha"), eq("<html>dueno</html>"));
        verify(emailRenderer, never()).render(eq("reserva-cancelada"), anyMap());
    }

    @Test
    @DisplayName("enviarNotificacionesCancelacion_CanceladaPorDuenoConJugadorYSeñaPagada_EnviaMailAlJugadorConAvisoDeReembolso")
    void enviarNotificacionesCancelacion_CanceladaPorDuenoConJugadorYSeñaPagada_EnviaMailAlJugadorConAvisoDeReembolso() {
        Usuario jugador = Usuario.builder()
                .id(1L)
                .email("jugador@test.com")
                .nombre("Juan")
                .rol(Role.PLAYER)
                .build();

        Reserva reserva = Reserva.builder()
                .id(50L)
                .jugador(jugador)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CANCELADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(50L)).thenReturn(Optional.of(reserva));
        when(emailRenderer.render(eq("reserva-cancelada"), anyMap())).thenReturn("<html>jugador</html>");

        listener.enviarNotificacionesCancelacion(new ReservaCanceladaEvent(50L, 2L));

        verify(emailService).enviar(eq("jugador@test.com"), eq("Tu reserva fue cancelada"), eq("<html>jugador</html>"));
        verify(emailService, never()).enviar(eq("dueno@test.com"), any(), any());
        verify(emailRenderer, never()).render(eq("reserva-cancelada-jugador"), anyMap());
        verify(emailRenderer, never()).render(eq("reserva-liberada-dueno"), anyMap());
    }

    @Test
    @DisplayName("enviarNotificacionesCancelacion_CanceladaPorDuenoConJugadorSinSeña_EnviaMailAlJugadorSinAvisoDeReembolso")
    void enviarNotificacionesCancelacion_CanceladaPorDuenoConJugadorSinSeña_EnviaMailAlJugadorSinAvisoDeReembolso() {
        Usuario jugador = Usuario.builder()
                .id(1L)
                .email("jugador@test.com")
                .nombre("Juan")
                .rol(Role.PLAYER)
                .build();

        Reserva reserva = Reserva.builder()
                .id(50L)
                .jugador(jugador)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CANCELADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(50L)).thenReturn(Optional.of(reserva));
        when(emailRenderer.render(eq("reserva-cancelada"), anyMap())).thenReturn("<html>jugador</html>");

        listener.enviarNotificacionesCancelacion(new ReservaCanceladaEvent(50L, 2L));

        verify(emailService).enviar(eq("jugador@test.com"), eq("Tu reserva fue cancelada"), eq("<html>jugador</html>"));
    }

    @Test
    @DisplayName("enviarNotificacionesCancelacion_CanceladaPorDuenoSinJugador_NoEnviaNingunMail")
    void enviarNotificacionesCancelacion_CanceladaPorDuenoSinJugador_NoEnviaNingunMail() {
        Reserva reserva = Reserva.builder()
                .id(51L)
                .jugador(null)
                .nombreClienteManual("Cliente Mostrador")
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CANCELADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(51L)).thenReturn(Optional.of(reserva));

        listener.enviarNotificacionesCancelacion(new ReservaCanceladaEvent(51L, 2L));

        verify(emailService, never()).enviar(any(), any(), any());
        verify(emailRenderer, never()).render(any(), anyMap());
    }

    @Test
    @DisplayName("enviarNotificacionesCancelacion_ReservaNoEncontrada_NoEnviaNingunMailNiLanzaExcepcion")
    void enviarNotificacionesCancelacion_ReservaNoEncontrada_NoEnviaNingunMailNiLanzaExcepcion() {
        when(reservaRepository.findByIdConEstablecimientoYDueno(999L)).thenReturn(Optional.empty());

        listener.enviarNotificacionesCancelacion(new ReservaCanceladaEvent(999L, 1L));

        verify(emailService, never()).enviar(any(), any(), any());
        verify(emailRenderer, never()).render(any(), anyMap());
    }

    @Test
    @DisplayName("enviarNotificacionesConfirmacion_JugadorEliminado_NoEnviaMailAlJugadorPeroSiAlDueno")
    void enviarNotificacionesConfirmacion_JugadorEliminado_NoEnviaMailAlJugadorPeroSiAlDueno() {
        Usuario jugadorEliminado = Usuario.builder()
                .id(1L)
                .email("deleted+1@saque.deleted")
                .nombre("Usuario eliminado")
                .rol(Role.PLAYER)
                .deletedAt(LocalDateTime.now())
                .build();

        Reserva reserva = Reserva.builder()
                .id(52L)
                .jugador(jugadorEliminado)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(52L)).thenReturn(Optional.of(reserva));
        when(emailRenderer.render(eq("reserva-nueva-dueno"), anyMap())).thenReturn("<html>dueno</html>");

        listener.enviarNotificacionesConfirmacion(new ReservaConfirmadaEvent(52L));

        verify(emailService, never()).enviar(eq("deleted+1@saque.deleted"), any(), any());
        verify(emailRenderer, never()).render(eq("reserva-confirmada"), anyMap());
        verify(emailService).enviar(eq("dueno@test.com"), eq("Nueva reserva confirmada en tu establecimiento"), eq("<html>dueno</html>"));
    }

    @Test
    @DisplayName("enviarNotificacionesCancelacion_CanceladaPorElJugadorYaEliminado_NoEnviaReciboAlJugadorPeroSiLiberacionAlDueno")
    void enviarNotificacionesCancelacion_CanceladaPorElJugadorYaEliminado_NoEnviaReciboAlJugadorPeroSiLiberacionAlDueno() {
        Usuario jugadorEliminado = Usuario.builder()
                .id(1L)
                .email("deleted+1@saque.deleted")
                .nombre("Usuario eliminado")
                .rol(Role.PLAYER)
                .deletedAt(LocalDateTime.now())
                .build();

        Reserva reserva = Reserva.builder()
                .id(53L)
                .jugador(jugadorEliminado)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CANCELADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(53L)).thenReturn(Optional.of(reserva));
        when(emailRenderer.render(eq("reserva-liberada-dueno"), anyMap())).thenReturn("<html>dueno</html>");

        listener.enviarNotificacionesCancelacion(new ReservaCanceladaEvent(53L, 1L));

        verify(emailService, never()).enviar(eq("deleted+1@saque.deleted"), any(), any());
        verify(emailRenderer, never()).render(eq("reserva-cancelada-jugador"), anyMap());
        verify(emailService).enviar(eq("dueno@test.com"), eq("Se liberó una cancha"), eq("<html>dueno</html>"));
    }

    @Test
    @DisplayName("enviarNotificacionesConfirmacion_DuenoEliminado_NoEnviaMailAlDuenoPeroSiAlJugador")
    void enviarNotificacionesConfirmacion_DuenoEliminado_NoEnviaMailAlDuenoPeroSiAlJugador() {
        Usuario duenoEliminado = Usuario.builder()
                .id(2L)
                .email("deleted+2@saque.deleted")
                .nombre("Usuario eliminado")
                .rol(Role.OWNER)
                .deletedAt(LocalDateTime.now())
                .build();
        Establecimiento establecimientoConDuenoEliminado = Establecimiento.builder()
                .id(10L)
                .nombre("Establecimiento Test")
                .dueno(duenoEliminado)
                .build();
        Cancha canchaConDuenoEliminado = Cancha.builder()
                .id(100L)
                .nombre("Cancha A")
                .establecimiento(establecimientoConDuenoEliminado)
                .build();

        Usuario jugador = Usuario.builder()
                .id(1L)
                .email("jugador@test.com")
                .nombre("Juan")
                .rol(Role.PLAYER)
                .build();

        Reserva reserva = Reserva.builder()
                .id(55L)
                .jugador(jugador)
                .cancha(canchaConDuenoEliminado)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(55L)).thenReturn(Optional.of(reserva));
        when(emailRenderer.render(eq("reserva-confirmada"), anyMap())).thenReturn("<html>jugador</html>");

        listener.enviarNotificacionesConfirmacion(new ReservaConfirmadaEvent(55L));

        verify(emailService).enviar(eq("jugador@test.com"), eq("Tu reserva fue confirmada"), eq("<html>jugador</html>"));
        verify(emailService, never()).enviar(eq("deleted+2@saque.deleted"), any(), any());
        verify(emailRenderer, never()).render(eq("reserva-nueva-dueno"), anyMap());
    }

    @Test
    @DisplayName("enviarNotificacionesCancelacion_CanceladaPorElJugadorConDuenoEliminado_NoEnviaMailAlDuenoPeroSiAlJugador")
    void enviarNotificacionesCancelacion_CanceladaPorElJugadorConDuenoEliminado_NoEnviaMailAlDuenoPeroSiAlJugador() {
        Usuario duenoEliminado = Usuario.builder()
                .id(2L)
                .email("deleted+2@saque.deleted")
                .nombre("Usuario eliminado")
                .rol(Role.OWNER)
                .deletedAt(LocalDateTime.now())
                .build();
        Establecimiento establecimientoConDuenoEliminado = Establecimiento.builder()
                .id(10L)
                .nombre("Establecimiento Test")
                .dueno(duenoEliminado)
                .build();
        Cancha canchaConDuenoEliminado = Cancha.builder()
                .id(100L)
                .nombre("Cancha A")
                .establecimiento(establecimientoConDuenoEliminado)
                .build();

        Usuario jugador = Usuario.builder()
                .id(1L)
                .email("jugador@test.com")
                .nombre("Juan")
                .rol(Role.PLAYER)
                .build();

        Reserva reserva = Reserva.builder()
                .id(56L)
                .jugador(jugador)
                .cancha(canchaConDuenoEliminado)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CANCELADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(56L)).thenReturn(Optional.of(reserva));
        when(emailRenderer.render(eq("reserva-cancelada-jugador"), anyMap())).thenReturn("<html>jugador</html>");

        listener.enviarNotificacionesCancelacion(new ReservaCanceladaEvent(56L, 1L));

        verify(emailService).enviar(eq("jugador@test.com"), eq("Cancelaste tu reserva"), eq("<html>jugador</html>"));
        verify(emailService, never()).enviar(eq("deleted+2@saque.deleted"), any(), any());
        verify(emailRenderer, never()).render(eq("reserva-liberada-dueno"), anyMap());
    }

    @Test
    @DisplayName("enviarNotificacionesCancelacion_CanceladaPorDuenoConJugadorEliminado_NoEnviaNingunMailAlJugador")
    void enviarNotificacionesCancelacion_CanceladaPorDuenoConJugadorEliminado_NoEnviaNingunMailAlJugador() {
        Usuario jugadorEliminado = Usuario.builder()
                .id(1L)
                .email("deleted+1@saque.deleted")
                .nombre("Usuario eliminado")
                .rol(Role.PLAYER)
                .deletedAt(LocalDateTime.now())
                .build();

        Reserva reserva = Reserva.builder()
                .id(54L)
                .jugador(jugadorEliminado)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CANCELADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(54L)).thenReturn(Optional.of(reserva));

        listener.enviarNotificacionesCancelacion(new ReservaCanceladaEvent(54L, 2L));

        verify(emailService, never()).enviar(any(), any(), any());
        verify(emailRenderer, never()).render(any(), anyMap());
    }

    /**
     * Las 3 ocurrencias de un turno fijo de los martes 20:00 (08, 15 y 22 de enero de 2030).
     * Comparten cancha, deporte, horario y precio por construcción (ver
     * ReservaService.crearReservaSemanal): lo único que varía es la fecha.
     */
    private List<Reserva> ocurrenciasDeTurnoFijo(Usuario jugador, String nombreClienteManual) {
        return List.of(
                ocurrencia(60L, jugador, nombreClienteManual, LocalDateTime.of(2030, 1, 8, 20, 0)),
                ocurrencia(61L, jugador, nombreClienteManual, LocalDateTime.of(2030, 1, 15, 20, 0)),
                ocurrencia(62L, jugador, nombreClienteManual, LocalDateTime.of(2030, 1, 22, 20, 0)));
    }

    private Reserva ocurrencia(Long id, Usuario jugador, String nombreClienteManual, LocalDateTime inicio) {
        return Reserva.builder()
                .id(id)
                .jugador(jugador)
                .nombreClienteManual(nombreClienteManual)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(inicio)
                .fechaHoraFin(inicio.plusHours(1))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .build();
    }

    @Test
    @DisplayName("enviarNotificacionesTurnoFijo_ConJugador_EnviaUnSoloMailAlJugadorYOtroAlDueno")
    void enviarNotificacionesTurnoFijo_ConJugador_EnviaUnSoloMailAlJugadorYOtroAlDueno() {
        Usuario jugador = Usuario.builder()
                .id(1L)
                .email("jugador@test.com")
                .nombre("Juan")
                .rol(Role.PLAYER)
                .build();
        List<Long> ids = List.of(60L, 61L, 62L);

        when(reservaRepository.findAllByIdInConEstablecimientoYDueno(ids))
                .thenReturn(ocurrenciasDeTurnoFijo(jugador, null));
        when(emailRenderer.render(eq("turno-fijo-confirmado"), anyMap())).thenReturn("<html>jugador</html>");
        when(emailRenderer.render(eq("turno-fijo-nuevo-dueno"), anyMap())).thenReturn("<html>dueno</html>");

        listener.enviarNotificacionesTurnoFijo(new TurnoFijoCreadoEvent(ids));

        // El punto de todo el cambio: 3 ocurrencias -> 2 emails, no 6.
        verify(emailService, times(1))
                .enviar(eq("jugador@test.com"), eq("Tu turno fijo quedó confirmado"), eq("<html>jugador</html>"));
        verify(emailService, times(1))
                .enviar(eq("dueno@test.com"), eq("Nuevo turno fijo en tu establecimiento"), eq("<html>dueno</html>"));
        verify(emailService, times(2)).enviar(any(), any(), any());
    }

    @Test
    @DisplayName("enviarNotificacionesTurnoFijo_ConJugador_ElModeloLlevaTodasLasFechasYElTotalDelPeriodo")
    @SuppressWarnings("unchecked")
    void enviarNotificacionesTurnoFijo_ConJugador_ElModeloLlevaTodasLasFechasYElTotalDelPeriodo() {
        Usuario jugador = Usuario.builder()
                .id(1L)
                .email("jugador@test.com")
                .nombre("Juan")
                .rol(Role.PLAYER)
                .build();
        List<Long> ids = List.of(60L, 61L, 62L);

        when(reservaRepository.findAllByIdInConEstablecimientoYDueno(ids))
                .thenReturn(ocurrenciasDeTurnoFijo(jugador, null));
        when(emailRenderer.render(eq("turno-fijo-confirmado"), anyMap())).thenReturn("<html>jugador</html>");
        when(emailRenderer.render(eq("turno-fijo-nuevo-dueno"), anyMap())).thenReturn("<html>dueno</html>");

        listener.enviarNotificacionesTurnoFijo(new TurnoFijoCreadoEvent(ids));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(emailRenderer).render(eq("turno-fijo-confirmado"), captor.capture());
        Map<String, Object> modelo = captor.getValue();

        // Un solo mail sirve para todo el turno fijo sólo si lista las N fechas.
        assertEquals(List.of("08/01/2030", "15/01/2030", "22/01/2030"), modelo.get("fechas"));
        assertEquals(3, modelo.get("cantidadTurnos"));
        assertEquals("20:00", modelo.get("horaInicio"));
        assertEquals("21:00", modelo.get("horaFin"));
        assertEquals(BigDecimal.valueOf(1500), modelo.get("precioPorTurno"));
        assertEquals(BigDecimal.valueOf(4500), modelo.get("precioTotalTurnoFijo"));
        assertEquals("Cancha A", modelo.get("canchaNombre"));
        assertEquals("Establecimiento Test", modelo.get("establecimientoNombre"));
    }

    @Test
    @DisplayName("enviarNotificacionesTurnoFijo_SinJugador_EnviaSoloMailAlDuenoConElNombreDelClienteManual")
    @SuppressWarnings("unchecked")
    void enviarNotificacionesTurnoFijo_SinJugador_EnviaSoloMailAlDuenoConElNombreDelClienteManual() {
        List<Long> ids = List.of(60L, 61L, 62L);

        when(reservaRepository.findAllByIdInConEstablecimientoYDueno(ids))
                .thenReturn(ocurrenciasDeTurnoFijo(null, "Cliente Fijo"));
        when(emailRenderer.render(eq("turno-fijo-nuevo-dueno"), anyMap())).thenReturn("<html>dueno</html>");

        listener.enviarNotificacionesTurnoFijo(new TurnoFijoCreadoEvent(ids));

        verify(emailRenderer, never()).render(eq("turno-fijo-confirmado"), anyMap());
        verify(emailService, times(1))
                .enviar(eq("dueno@test.com"), eq("Nuevo turno fijo en tu establecimiento"), eq("<html>dueno</html>"));
        verify(emailService, times(1)).enviar(any(), any(), any());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(emailRenderer).render(eq("turno-fijo-nuevo-dueno"), captor.capture());
        assertEquals("Cliente Fijo", captor.getValue().get("nombreCliente"));
    }

    @Test
    @DisplayName("enviarNotificacionesTurnoFijo_JugadorEliminado_NoEnviaMailAlJugadorPeroSiAlDueno")
    void enviarNotificacionesTurnoFijo_JugadorEliminado_NoEnviaMailAlJugadorPeroSiAlDueno() {
        Usuario jugadorEliminado = Usuario.builder()
                .id(1L)
                .email("deleted+1@saque.deleted")
                .nombre("Usuario eliminado")
                .rol(Role.PLAYER)
                .deletedAt(LocalDateTime.now())
                .build();
        List<Long> ids = List.of(60L, 61L, 62L);

        when(reservaRepository.findAllByIdInConEstablecimientoYDueno(ids))
                .thenReturn(ocurrenciasDeTurnoFijo(jugadorEliminado, null));
        when(emailRenderer.render(eq("turno-fijo-nuevo-dueno"), anyMap())).thenReturn("<html>dueno</html>");

        listener.enviarNotificacionesTurnoFijo(new TurnoFijoCreadoEvent(ids));

        verify(emailService, never()).enviar(eq("deleted+1@saque.deleted"), any(), any());
        verify(emailRenderer, never()).render(eq("turno-fijo-confirmado"), anyMap());
        verify(emailService).enviar(eq("dueno@test.com"), eq("Nuevo turno fijo en tu establecimiento"), eq("<html>dueno</html>"));
    }

    @Test
    @DisplayName("enviarNotificacionesTurnoFijo_ReservasNoEncontradas_NoEnviaNingunMailNiLanzaExcepcion")
    void enviarNotificacionesTurnoFijo_ReservasNoEncontradas_NoEnviaNingunMailNiLanzaExcepcion() {
        List<Long> ids = List.of(998L, 999L);
        when(reservaRepository.findAllByIdInConEstablecimientoYDueno(ids)).thenReturn(List.of());

        listener.enviarNotificacionesTurnoFijo(new TurnoFijoCreadoEvent(ids));

        verify(emailService, never()).enviar(any(), any(), any());
        verify(emailRenderer, never()).render(any(), anyMap());
    }
}
