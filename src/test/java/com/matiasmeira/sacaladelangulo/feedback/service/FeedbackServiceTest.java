package com.matiasmeira.sacaladelangulo.feedback.service;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.feedback.dto.FeedbackMapper;
import com.matiasmeira.sacaladelangulo.feedback.dto.FeedbackRequest;
import com.matiasmeira.sacaladelangulo.feedback.dto.FeedbackResponse;
import com.matiasmeira.sacaladelangulo.feedback.model.Feedback;
import com.matiasmeira.sacaladelangulo.feedback.repository.FeedbackRepository;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.publico.service.ComplejoDetalleCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackService - Tests de calificación y comentario destacado")
class FeedbackServiceTest {

    @Mock
    private FeedbackRepository feedbackRepository;

    @Mock
    private ReservaRepository reservaRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private FeedbackMapper feedbackMapper;

    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;

    @Mock
    private ComplejoDetalleCache complejoDetalleCache;

    @InjectMocks
    private FeedbackService feedbackService;

    private Usuario dueno;
    private Usuario jugador;
    private Usuario otroJugador;
    private Establecimiento establecimiento;
    private Cancha cancha;
    private Reserva reserva;

    @BeforeEach
    void setUp() {
        dueno = Usuario.builder().id(1L).email("dueno@test.com").rol(Role.OWNER).build();
        jugador = Usuario.builder().id(2L).email("jugador@test.com").rol(Role.PLAYER).nombre("Juan").build();
        otroJugador = Usuario.builder().id(3L).email("otro@test.com").rol(Role.PLAYER).nombre("Pedro").build();

        establecimiento = Establecimiento.builder().id(100L).dueno(dueno).build();
        cancha = Cancha.builder().id(50L).establecimiento(establecimiento).build();
        reserva = Reserva.builder()
                .id(500L)
                .cancha(cancha)
                .jugador(jugador)
                .estado(EstadoReserva.FINALIZADA)
                .build();
    }

    @Test
    @DisplayName("crearFeedback crea la calificación cuando la reserva está finalizada y el usuario es el jugador")
    void crearFeedbackExitoCuandoReservaFinalizadaYUsuarioEsJugador() {
        FeedbackRequest request = new FeedbackRequest(5, "Excelente cancha");

        when(reservaRepository.findByIdConEstablecimientoYDueno(500L)).thenReturn(Optional.of(reserva));
        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(feedbackRepository.existsByReservaId(500L)).thenReturn(false);
        when(feedbackRepository.saveAndFlush(any(Feedback.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(feedbackMapper.mapToResponse(any(Feedback.class))).thenReturn(
                new FeedbackResponse(10L, 500L, 100L, 2L, "Juan", 5, "Excelente cancha", false, LocalDateTime.now()));

        FeedbackResponse response = assertDoesNotThrow(
                () -> feedbackService.crearFeedback(500L, request, jugador.getEmail()));

        assertEquals(5, response.puntuacion());
        assertEquals("Excelente cancha", response.comentario());
    }

    @Test
    @DisplayName("crearFeedback lanza AccessDeniedException si el usuario autenticado no es el jugador de la reserva")
    void crearFeedbackFallaSiUsuarioNoEsElJugador() {
        FeedbackRequest request = new FeedbackRequest(4, "Muy bien");

        when(reservaRepository.findByIdConEstablecimientoYDueno(500L)).thenReturn(Optional.of(reserva));
        when(usuarioRepository.findByEmail(otroJugador.getEmail())).thenReturn(Optional.of(otroJugador));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> feedbackService.crearFeedback(500L, request, otroJugador.getEmail()));
    }

    @Test
    @DisplayName("crearFeedback lanza IllegalArgumentException si la reserva no está finalizada")
    void crearFeedbackFallaSiReservaNoEstaFinalizada() {
        reserva.setEstado(EstadoReserva.CONFIRMADA);
        FeedbackRequest request = new FeedbackRequest(3, "Todavía no se jugó");

        when(reservaRepository.findByIdConEstablecimientoYDueno(500L)).thenReturn(Optional.of(reserva));
        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> feedbackService.crearFeedback(500L, request, jugador.getEmail()));
        assertTrue(exception.getMessage().contains("finalizada"));
    }

    @Test
    @DisplayName("crearFeedback lanza IllegalArgumentException si ya existe un feedback para esa reserva")
    void crearFeedbackFallaSiYaExisteFeedback() {
        FeedbackRequest request = new FeedbackRequest(2, "Repetido");

        when(reservaRepository.findByIdConEstablecimientoYDueno(500L)).thenReturn(Optional.of(reserva));
        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(feedbackRepository.existsByReservaId(500L)).thenReturn(true);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> feedbackService.crearFeedback(500L, request, jugador.getEmail()));
        assertTrue(exception.getMessage().contains("Ya calificaste"));
    }

    @Test
    @DisplayName("crearFeedback traduce la carrera de inserción perdida al mismo mensaje de negocio")
    void crearFeedbackFallaPorCarreraDeInsercion() {
        // existsByReservaId + save no es atómico: dos requests casi simultáneas (doble
        // click/retry) pueden pasar ambas la validación antes de que cualquiera persista.
        FeedbackRequest request = new FeedbackRequest(5, "Doble submit");

        when(reservaRepository.findByIdConEstablecimientoYDueno(500L)).thenReturn(Optional.of(reserva));
        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(feedbackRepository.existsByReservaId(500L)).thenReturn(false);
        when(feedbackRepository.saveAndFlush(any(Feedback.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> feedbackService.crearFeedback(500L, request, jugador.getEmail()));
        assertTrue(exception.getMessage().contains("Ya calificaste"));
    }

    @Test
    @DisplayName("editarFeedback actualiza puntuación y comentario cuando el usuario es el jugador dueño")
    void editarFeedbackExitoCuandoUsuarioEsElJugador() {
        Feedback feedback = Feedback.builder().id(10L).reserva(reserva).puntuacion(3).comentario("Bien").destacado(false).build();
        FeedbackRequest request = new FeedbackRequest(5, "Excelente, corrijo mi opinión");

        when(feedbackRepository.findByIdConReservaYJugador(10L)).thenReturn(Optional.of(feedback));
        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(feedbackRepository.save(any(Feedback.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(feedbackMapper.mapToResponse(any(Feedback.class))).thenReturn(
                new FeedbackResponse(10L, 500L, 100L, 2L, "Juan", 5, "Excelente, corrijo mi opinión", false, LocalDateTime.now()));

        FeedbackResponse response = assertDoesNotThrow(
                () -> feedbackService.editarFeedback(10L, request, jugador.getEmail()));

        assertEquals(5, response.puntuacion());
        assertEquals("Excelente, corrijo mi opinión", response.comentario());
        assertEquals(5, feedback.getPuntuacion());
        assertEquals("Excelente, corrijo mi opinión", feedback.getComentario());
    }

    @Test
    @DisplayName("editarFeedback lanza AccessDeniedException si el usuario autenticado no es el jugador de la reserva")
    void editarFeedbackFallaSiUsuarioNoEsElJugador() {
        Feedback feedback = Feedback.builder().id(10L).reserva(reserva).puntuacion(3).comentario("Bien").destacado(false).build();
        FeedbackRequest request = new FeedbackRequest(1, "Intento ajeno");

        when(feedbackRepository.findByIdConReservaYJugador(10L)).thenReturn(Optional.of(feedback));
        when(usuarioRepository.findByEmail(otroJugador.getEmail())).thenReturn(Optional.of(otroJugador));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> feedbackService.editarFeedback(10L, request, otroJugador.getEmail()));
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    @DisplayName("eliminarFeedback borra el feedback cuando el usuario es el jugador dueño")
    void eliminarFeedbackExitoCuandoUsuarioEsElJugador() {
        Feedback feedback = Feedback.builder().id(10L).reserva(reserva).puntuacion(3).comentario("Bien").destacado(false).build();

        when(feedbackRepository.findByIdConReservaYJugador(10L)).thenReturn(Optional.of(feedback));
        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));

        assertDoesNotThrow(() -> feedbackService.eliminarFeedback(10L, jugador.getEmail()));

        verify(feedbackRepository).delete(feedback);
    }

    @Test
    @DisplayName("eliminarFeedback lanza AccessDeniedException si el usuario autenticado no es el jugador de la reserva")
    void eliminarFeedbackFallaSiUsuarioNoEsElJugador() {
        Feedback feedback = Feedback.builder().id(10L).reserva(reserva).puntuacion(3).comentario("Bien").destacado(false).build();

        when(feedbackRepository.findByIdConReservaYJugador(10L)).thenReturn(Optional.of(feedback));
        when(usuarioRepository.findByEmail(otroJugador.getEmail())).thenReturn(Optional.of(otroJugador));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> feedbackService.eliminarFeedback(10L, otroJugador.getEmail()));
        verify(feedbackRepository, never()).delete(any());
    }

    @Test
    @DisplayName("fijarComentario desfija el destacado anterior del establecimiento antes de fijar el nuevo")
    void fijarComentarioDesfijaElAnterior() {
        Feedback destacadoAnterior = Feedback.builder().id(9L).reserva(reserva).puntuacion(3).destacado(true).build();
        Feedback nuevoFeedback = Feedback.builder().id(10L).reserva(reserva).puntuacion(5).destacado(false).build();

        when(feedbackRepository.findById(10L)).thenReturn(Optional.of(nuevoFeedback));
        when(autorizacionEmpleadoService.validarAccion(establecimiento, dueno.getEmail(), PermisoEmpleado.FIJAR_COMENTARIO_DESTACADO))
                .thenReturn(dueno);
        when(feedbackRepository.findDestacadoByEstablecimientoId(100L)).thenReturn(Optional.of(destacadoAnterior));
        when(feedbackRepository.save(any(Feedback.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(feedbackMapper.mapToResponse(any(Feedback.class))).thenReturn(
                new FeedbackResponse(10L, 500L, 100L, 2L, "Juan", 5, null, true, LocalDateTime.now()));

        feedbackService.fijarComentario(10L, dueno.getEmail());

        assertFalse(destacadoAnterior.getDestacado());
        assertTrue(nuevoFeedback.getDestacado());
    }

    @Test
    @DisplayName("fijarComentario lanza AccessDeniedException si el usuario no está autorizado")
    void fijarComentarioFallaSiUsuarioNoAutorizado() {
        Feedback feedback = Feedback.builder().id(10L).reserva(reserva).puntuacion(5).destacado(false).build();

        when(feedbackRepository.findById(10L)).thenReturn(Optional.of(feedback));
        when(autorizacionEmpleadoService.validarAccion(establecimiento, otroJugador.getEmail(), PermisoEmpleado.FIJAR_COMENTARIO_DESTACADO))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("No autorizado para realizar esta acción en este establecimiento"));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> feedbackService.fijarComentario(10L, otroJugador.getEmail()));
    }
}
