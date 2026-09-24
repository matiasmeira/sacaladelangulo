package com.matiasmeira.sacaladelangulo.reserva.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaManualRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaRequest;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Análoga a ReservaControllerEstablecimientoInactivoIntegrationTest pero para la otra mitad
 * del criterio de EstablecimientoOperativoGuard: estadoVerificacion. cancelarReserva,
 * finalizarReserva y marcarAusente operan sobre una reserva YA creada -no crean compromiso
 * nuevo- y a propósito no llevan el gate: siguen funcionando aunque el establecimiento nunca
 * haya sido verificado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-reserva-establecimiento-no-verificado;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("POST /api/v1/reservas(/manual) - establecimiento no verificado")
class ReservaControllerEstablecimientoNoVerificadoIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Autowired
    private CanchaRepository canchaRepository;

    @Autowired
    private ReservaRepository reservaRepository;

    private Usuario crearUsuario(String email, Role rol) {
        return usuarioRepository.save(Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Usuario Test")
                .rol(rol)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());
    }

    /** Horario de atención los 7 días de la semana, para no depender de qué día es "hoy". */
    private Establecimiento crearEstablecimientoConCancha(String slug, Usuario dueno, EstadoVerificacion estadoVerificacion) {
        Establecimiento establecimiento = establecimientoRepository.save(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo " + slug)
                .direccion("Calle Test 123")
                .slug(slug)
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(true)
                .estadoVerificacion(estadoVerificacion)
                .dueno(dueno)));

        Establecimiento establecimientoFinal = establecimiento;
        List<HorarioAtencion> horarios = Arrays.stream(DayOfWeek.values())
                .map(dia -> HorarioAtencion.builder()
                        .diaSemana(dia)
                        .horaApertura(LocalTime.of(6, 0))
                        .horaCierre(LocalTime.of(23, 0))
                        .establecimiento(establecimientoFinal)
                        .build())
                .collect(Collectors.toList());
        establecimiento.setHorariosAtencion(new ArrayList<>(horarios));
        establecimiento = establecimientoRepository.save(establecimiento);

        Cancha cancha = canchaRepository.save(Cancha.builder()
                .nombre("Cancha 1")
                .deportes(Set.of(Deporte.FUTBOL_5))
                .isActive(true)
                .precioBase(BigDecimal.valueOf(5000))
                .montoSena(BigDecimal.valueOf(1000))
                .duracionesPermitidas(List.of(60))
                .permiteInicioMediaHora(false)
                .establecimiento(establecimiento)
                .build());
        this.ultimaCanchaCreada = cancha;
        return establecimiento;
    }

    private Cancha ultimaCanchaCreada;

    private LocalDateTime pasadoManiana10hs() {
        return LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    @ParameterizedTest(name = "crearReserva sobre establecimiento {0} -> 404 opaco (mismo tratamiento que ''no existe'' o ''inactivo'')")
    @EnumSource(value = EstadoVerificacion.class, names = "VERIFICADO", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("crearReserva_EstablecimientoNoVerificado_Devuelve404")
    void crearReserva_EstablecimientoNoVerificado_Devuelve404(EstadoVerificacion estadoVerificacion) throws Exception {
        Usuario dueno = crearUsuario("dueno-crear-a-" + estadoVerificacion + "@test.com", Role.OWNER);
        crearEstablecimientoConCancha("complejo-crear-a-" + estadoVerificacion.name().toLowerCase(), dueno, estadoVerificacion);
        String emailJugador = "jugador-crear-a-" + estadoVerificacion + "@test.com";
        crearUsuario(emailJugador, Role.PLAYER);

        LocalDateTime inicio = pasadoManiana10hs();
        ReservaRequest request = new ReservaRequest(ultimaCanchaCreada.getId(), inicio, inicio.plusHours(1), Deporte.FUTBOL_5);

        mockMvc.perform(post("/api/v1/reservas")
                        .with(user(emailJugador).roles("PLAYER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Establecimiento no encontrado"));
    }

    @ParameterizedTest(name = "crearReservaManual sobre establecimiento {0} -> 400 explícito sobre la verificación")
    @EnumSource(value = EstadoVerificacion.class, names = "VERIFICADO", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("crearReservaManual_EstablecimientoNoVerificado_Devuelve400")
    void crearReservaManual_EstablecimientoNoVerificado_Devuelve400(EstadoVerificacion estadoVerificacion) throws Exception {
        String emailDueno = "dueno-crear-b-" + estadoVerificacion + "@test.com";
        Usuario dueno = crearUsuario(emailDueno, Role.OWNER);
        crearEstablecimientoConCancha("complejo-crear-b-" + estadoVerificacion.name().toLowerCase(), dueno, estadoVerificacion);

        LocalDateTime inicio = pasadoManiana10hs();
        ReservaManualRequest request = new ReservaManualRequest(
                ultimaCanchaCreada.getId(), inicio, inicio.plusHours(1), Deporte.FUTBOL_5,
                "Cliente Mostrador", "1122334455", false);

        mockMvc.perform(post("/api/v1/reservas/manual")
                        .with(user(emailDueno).roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Este establecimiento todavía no está verificado. No se pueden cargar reservas nuevas hasta que se apruebe la verificación."));
    }

    /**
     * Siembra una reserva CONFIRMADA a mano en el establecimiento (evitando crearReserva,
     * que está bloqueado por el guard para un establecimiento no verificado) para probar
     * que su administración posterior sigue funcionando.
     */
    private Reserva sembrarReservaConfirmada(Establecimiento establecimiento, Usuario jugador) {
        return sembrarReservaConfirmada(establecimiento, jugador, pasadoManiana10hs());
    }

    private Reserva sembrarReservaConfirmada(Establecimiento establecimiento, Usuario jugador, LocalDateTime inicio) {
        return reservaRepository.save(Reserva.builder()
                .jugador(jugador)
                .cancha(ultimaCanchaCreada)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(inicio)
                .fechaHoraFin(inicio.plusHours(1))
                .estado(com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(5000))
                .senaPagada(BigDecimal.valueOf(1000))
                .fechaCreacion(LocalDateTime.now())
                .build());
    }

    @Test
    @DisplayName("cancelarReserva de una reserva existente sigue funcionando con el establecimiento sin verificar")
    void cancelarReserva_EstablecimientoNoVerificado_SigueFuncionando() throws Exception {
        String emailDueno = "dueno-cancelar-nv@test.com";
        Usuario dueno = crearUsuario(emailDueno, Role.OWNER);
        Establecimiento establecimiento = crearEstablecimientoConCancha("complejo-cancelar-nv", dueno, EstadoVerificacion.PENDIENTE);
        Usuario jugador = crearUsuario("jugador-cancelar-nv@test.com", Role.PLAYER);
        Reserva reserva = sembrarReservaConfirmada(establecimiento, jugador);

        mockMvc.perform(put("/api/v1/reservas/" + reserva.getId() + "/cancelar")
                        .with(user(emailDueno).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CANCELADA"));
    }

    @Test
    @DisplayName("finalizarReserva de una reserva existente sigue funcionando con el establecimiento sin verificar")
    void finalizarReserva_EstablecimientoNoVerificado_SigueFuncionando() throws Exception {
        String emailDueno = "dueno-finalizar-nv@test.com";
        Usuario dueno = crearUsuario(emailDueno, Role.OWNER);
        Establecimiento establecimiento = crearEstablecimientoConCancha("complejo-finalizar-nv", dueno, EstadoVerificacion.EN_REVISION);
        Usuario jugador = crearUsuario("jugador-finalizar-nv@test.com", Role.PLAYER);
        Reserva reserva = sembrarReservaConfirmada(establecimiento, jugador);

        mockMvc.perform(patch("/api/v1/reservas/" + reserva.getId() + "/finalizar")
                        .with(user(emailDueno).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metodoPago\":\"EFECTIVO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("FINALIZADA"));
    }

    @Test
    @DisplayName("marcarAusente de una reserva existente sigue funcionando con el establecimiento sin verificar")
    void marcarAusente_EstablecimientoNoVerificado_SigueFuncionando() throws Exception {
        String emailDueno = "dueno-ausente-nv@test.com";
        Usuario dueno = crearUsuario(emailDueno, Role.OWNER);
        Establecimiento establecimiento = crearEstablecimientoConCancha("complejo-ausente-nv", dueno, EstadoVerificacion.RECHAZADO);
        Usuario jugador = crearUsuario("jugador-ausente-nv@test.com", Role.PLAYER);
        // marcarAusente exige que el turno ya haya empezado.
        Reserva reserva = sembrarReservaConfirmada(establecimiento, jugador, LocalDateTime.now().minusHours(2));

        mockMvc.perform(patch("/api/v1/reservas/" + reserva.getId() + "/ausente")
                        .with(user(emailDueno).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("AUSENTE"));
    }
}
