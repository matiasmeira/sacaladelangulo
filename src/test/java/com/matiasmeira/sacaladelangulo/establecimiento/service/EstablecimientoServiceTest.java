package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.EstablecimientoRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.EstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.HorarioAtencionDto;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.feedback.repository.FeedbackRepository;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EstablecimientoServiceTest {

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private ReservaRepository reservaRepository;

    @Mock
    private CanchaRepository canchaRepository;

    @Mock
    private FeedbackRepository feedbackRepository;

    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;

    @InjectMocks
    private EstablecimientoService establecimientoService;

    @Test
    @DisplayName("buscarEstablecimientos sin fecha y hora devuelve resultados cercanos")
    void buscarEstablecimientosSinFechaYHoraDevuelveResultadosCercanos() {
        Usuario dueno = Usuario.builder()
                .id(1L)
                .email("dueno@test.com")
                .build();

        Establecimiento establecimiento = Establecimiento.builder()
                .id(10L)
                .nombre("Cancha Premium")
                .direccion("Av. Siempre Viva 123")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(true)
                .isActive(true)
                .dueno(dueno)
                .build();

        when(establecimientoRepository.findCercanosYPorDeporte(-34.6037, -58.3816, 10.0, null))
                .thenReturn(List.of(establecimiento));

        List<EstablecimientoResponse> resultados = establecimientoService.buscarEstablecimientos(
                -34.6037,
                -58.3816,
                10.0,
                null,
                null,
                null
        );

        assertEquals(1, resultados.size());
        EstablecimientoResponse esperado = new EstablecimientoResponse(
                10L,
                "Cancha Premium",
                "Av. Siempre Viva 123",
                -34.6037,
                -58.3816,
                true,
                true,
                dueno.getId(),
                List.of(),
                null,
                0L,
                null
        );
        assertEquals(esperado, resultados.get(0));
    }

    @Test
    @DisplayName("crearEstablecimiento_Exito_ConHorariosValidos")
    void crearEstablecimiento_Exito_ConHorariosValidos() {
        Usuario dueno = Usuario.builder()
                .id(1L)
                .email("dueno@test.com")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .build();

        EstablecimientoRequest request = new EstablecimientoRequest(
                "Complejo Test",
                "Calle Falsa 123",
                -34.6,
                -58.4,
                false,
                List.of(
                        new HorarioAtencionDto(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(23, 0)),
                        new HorarioAtencionDto(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(23, 0))
                )
        );

        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(establecimientoRepository.save(any(Establecimiento.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EstablecimientoResponse response = assertDoesNotThrow(
                () -> establecimientoService.crearEstablecimiento(request, dueno.getEmail()));

        assertEquals(2, response.horariosAtencion().size());
    }

    @Test
    @DisplayName("crearEstablecimiento_Fallo_HorariosDuplicadosParaElMismoDia")
    void crearEstablecimiento_Fallo_HorariosDuplicadosParaElMismoDia() {
        Usuario dueno = Usuario.builder()
                .id(1L)
                .email("dueno@test.com")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .build();

        EstablecimientoRequest request = new EstablecimientoRequest(
                "Complejo Test",
                "Calle Falsa 123",
                -34.6,
                -58.4,
                false,
                List.of(
                        new HorarioAtencionDto(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(13, 0)),
                        new HorarioAtencionDto(DayOfWeek.TUESDAY, LocalTime.of(15, 0), LocalTime.of(23, 0))
                )
        );

        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> establecimientoService.crearEstablecimiento(request, dueno.getEmail())
        );
        assertTrue(exception.getMessage().contains("TUESDAY"));
        verify(establecimientoRepository, never()).save(any());
    }

    @Test
    @DisplayName("crearEstablecimiento_Fallo_HoraAperturaIgualHoraCierre")
    void crearEstablecimiento_Fallo_HoraAperturaIgualHoraCierre() {
        Usuario dueno = Usuario.builder()
                .id(1L)
                .email("dueno@test.com")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .build();

        EstablecimientoRequest request = new EstablecimientoRequest(
                "Complejo Test",
                "Calle Falsa 123",
                -34.6,
                -58.4,
                false,
                List.of(new HorarioAtencionDto(DayOfWeek.WEDNESDAY, LocalTime.of(9, 0), LocalTime.of(9, 0)))
        );

        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> establecimientoService.crearEstablecimiento(request, dueno.getEmail())
        );
        assertTrue(exception.getMessage().contains("iguales"));
        verify(establecimientoRepository, never()).save(any());
    }

    @Test
    @DisplayName("actualizarEstablecimiento_Exito_ReemplazaHorarios")
    void actualizarEstablecimiento_Exito_ReemplazaHorarios() {
        Usuario dueno = Usuario.builder()
                .id(1L)
                .email("dueno@test.com")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .build();

        Establecimiento existente = Establecimiento.builder()
                .id(10L)
                .nombre("Viejo Nombre")
                .direccion("Vieja Direccion")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .horariosAtencion(new ArrayList<>(List.of(
                        HorarioAtencion.builder()
                                .diaSemana(DayOfWeek.MONDAY)
                                .horaApertura(LocalTime.of(8, 0))
                                .horaCierre(LocalTime.of(20, 0))
                                .build()
                )))
                .build();

        EstablecimientoRequest request = new EstablecimientoRequest(
                "Nuevo Nombre",
                "Nueva Direccion",
                -34.6,
                -58.4,
                true,
                List.of(new HorarioAtencionDto(DayOfWeek.FRIDAY, LocalTime.of(10, 0), LocalTime.of(22, 0)))
        );

        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(existente));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(existente, dueno.getEmail())).thenReturn(dueno);
        when(establecimientoRepository.save(any(Establecimiento.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EstablecimientoResponse response = assertDoesNotThrow(
                () -> establecimientoService.actualizarEstablecimiento(10L, request, dueno.getEmail()));

        assertEquals(1, response.horariosAtencion().size());
        assertEquals(DayOfWeek.FRIDAY, response.horariosAtencion().get(0).diaSemana());
    }
}
