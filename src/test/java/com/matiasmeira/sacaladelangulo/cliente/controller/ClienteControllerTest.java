package com.matiasmeira.sacaladelangulo.cliente.controller;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.auth.service.JwtService;
import com.matiasmeira.sacaladelangulo.auth.service.UsuarioUserDetailsMapper;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoJugador;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoJugadorRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mismo patrón end-to-end (MockMvc + JWT real + H2 real) que EmpleadoControllerListarTest /
 * EstablecimientoControllerServiciosIntegrationTest: cada test crea su propio dueño/slug con
 * sufijo único, porque el contexto de Spring (y la base H2) se comparte entre métodos de la
 * misma clase (ddl-auto=create-drop solo corre al levantar el contexto, no por test).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-cliente-controller;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("Padrón de clientes: /api/v1/establecimientos/{id}/clientes")
class ClienteControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private EstablecimientoRepository establecimientoRepository;
    @Autowired
    private CanchaRepository canchaRepository;
    @Autowired
    private ReservaRepository reservaRepository;
    @Autowired
    private BloqueoJugadorRepository bloqueoJugadorRepository;
    @Autowired
    private JwtService jwtService;

    private Usuario crearDueno(String email) {
        return usuarioRepository.save(Usuario.builder()
                .email(email).password("hash").nombre("Dueno Test")
                .rol(Role.OWNER).isActive(true).emailVerified(true).telefonoVerificado(true).build());
    }

    private Establecimiento crearEstablecimiento(Usuario dueno, String slug) {
        return establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo Test").direccion("Calle Falsa 123").slug(slug)
                .latitud(-34.6).longitud(-58.4).requiereSena(false).isActive(true).dueno(dueno).build());
    }

    private Cancha crearCancha(Establecimiento establecimiento) {
        return canchaRepository.save(Cancha.builder()
                .nombre("Cancha 1").deportes(Set.of(Deporte.FUTBOL_5)).isActive(true)
                .precioBase(BigDecimal.valueOf(100)).montoSena(BigDecimal.valueOf(20))
                .establecimiento(establecimiento).build());
    }

    private Usuario crearJugador(String email, String nombre, String telefono) {
        return usuarioRepository.save(Usuario.builder()
                .email(email).password("hash").nombre(nombre).telefono(telefono)
                .rol(Role.PLAYER).isActive(true).emailVerified(true).telefonoVerificado(true).build());
    }

    private void crearReserva(Usuario jugador, Cancha cancha, EstadoReserva estado, LocalDateTime inicio, BigDecimal precio) {
        reservaRepository.save(Reserva.builder()
                .jugador(jugador).cancha(cancha).deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(inicio).fechaHoraFin(inicio.plusHours(1))
                .estado(estado).precioTotal(precio).build());
    }

    private String tokenPara(Usuario usuario) {
        return jwtService.generateToken(UsuarioUserDetailsMapper.map(usuario));
    }

    @Test
    @DisplayName("Cuenta reservasTotales/totalGastado solo FINALIZADA, ausencias solo AUSENTE, y excluye jugadores de otro establecimiento")
    void listarClientes_CriteriosDeAceptacion() throws Exception {
        Usuario dueno = crearDueno("dueno1@cliente-controller-test.com");
        Establecimiento establecimiento = crearEstablecimiento(dueno, "complejo-cliente-controller-test-1");
        Cancha cancha = crearCancha(establecimiento);

        Usuario juan = crearJugador("juan1@cliente-controller-test.com", "Juan Perez", "1122334455");
        crearReserva(juan, cancha, EstadoReserva.FINALIZADA, LocalDateTime.now().minusDays(3), new BigDecimal("100.00"));
        crearReserva(juan, cancha, EstadoReserva.FINALIZADA, LocalDateTime.now().minusDays(2), new BigDecimal("100.00"));
        crearReserva(juan, cancha, EstadoReserva.FINALIZADA, LocalDateTime.now().minusDays(1), new BigDecimal("100.00"));
        crearReserva(juan, cancha, EstadoReserva.AUSENTE, LocalDateTime.now().minusDays(5), new BigDecimal("50.00"));

        Usuario duenoAjeno = crearDueno("dueno2@cliente-controller-test.com");
        Establecimiento otroEstablecimiento = crearEstablecimiento(duenoAjeno, "otro-complejo-cliente-controller-test-1");
        Cancha otraCancha = crearCancha(otroEstablecimiento);
        Usuario pedro = crearJugador("pedro1@cliente-controller-test.com", "Pedro Gomez", null);
        crearReserva(pedro, otraCancha, EstadoReserva.FINALIZADA, LocalDateTime.now().minusDays(1), new BigDecimal("999.00"));

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/clientes")
                        .header("Authorization", "Bearer " + tokenPara(dueno)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].jugadorId").value(is(juan.getId()), Long.class))
                .andExpect(jsonPath("$.content[0].reservasTotales").value(3))
                .andExpect(jsonPath("$.content[0].ausencias").value(1))
                .andExpect(jsonPath("$.content[0].totalGastado").value(300.00))
                .andExpect(jsonPath("$.content[0].bloqueado").value(false));
    }

    @Test
    @DisplayName("buscar matchea nombre, telefono o email, case-insensitive")
    void listarClientes_Buscar_MatcheaCaseInsensitive() throws Exception {
        Usuario dueno = crearDueno("dueno3@cliente-controller-test.com");
        Establecimiento establecimiento = crearEstablecimiento(dueno, "complejo-cliente-controller-test-2");
        Cancha cancha = crearCancha(establecimiento);

        Usuario juan = crearJugador("juan2@cliente-controller-test.com", "Juan Perez", "1122334455");
        crearReserva(juan, cancha, EstadoReserva.FINALIZADA, LocalDateTime.now().minusDays(1), new BigDecimal("100.00"));
        Usuario maria = crearJugador("maria2@cliente-controller-test.com", "Maria Lopez", "5566778899");
        crearReserva(maria, cancha, EstadoReserva.FINALIZADA, LocalDateTime.now().minusDays(1), new BigDecimal("100.00"));

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/clientes")
                        .header("Authorization", "Bearer " + tokenPara(dueno))
                        .param("buscar", "perez"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].jugadorId").value(is(juan.getId()), Long.class));
    }

    @Test
    @DisplayName("soloBloqueados=true devuelve unicamente los bloqueados vigentes")
    void listarClientes_SoloBloqueados_FiltraPorBloqueoVigente() throws Exception {
        Usuario dueno = crearDueno("dueno4@cliente-controller-test.com");
        Establecimiento establecimiento = crearEstablecimiento(dueno, "complejo-cliente-controller-test-3");
        Cancha cancha = crearCancha(establecimiento);

        Usuario juan = crearJugador("juan3@cliente-controller-test.com", "Juan Perez", null);
        crearReserva(juan, cancha, EstadoReserva.FINALIZADA, LocalDateTime.now().minusDays(1), new BigDecimal("100.00"));
        Usuario maria = crearJugador("maria3@cliente-controller-test.com", "Maria Lopez", null);
        crearReserva(maria, cancha, EstadoReserva.FINALIZADA, LocalDateTime.now().minusDays(1), new BigDecimal("100.00"));
        bloqueoJugadorRepository.save(BloqueoJugador.builder()
                .establecimiento(establecimiento).jugador(maria).motivo("Reincidente").build());

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/clientes")
                        .header("Authorization", "Bearer " + tokenPara(dueno))
                        .param("soloBloqueados", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].jugadorId").value(is(maria.getId()), Long.class));
    }

    @Test
    @DisplayName("403 para un OWNER ajeno al establecimiento, en los tres endpoints")
    void endpoints_Fallo_OwnerAjeno() throws Exception {
        Usuario dueno = crearDueno("dueno5@cliente-controller-test.com");
        Establecimiento establecimiento = crearEstablecimiento(dueno, "complejo-cliente-controller-test-4");
        Cancha cancha = crearCancha(establecimiento);
        Usuario juan = crearJugador("juan4@cliente-controller-test.com", "Juan Perez", null);
        crearReserva(juan, cancha, EstadoReserva.FINALIZADA, LocalDateTime.now().minusDays(1), new BigDecimal("100.00"));

        Usuario duenoAjeno = crearDueno("ajeno5@cliente-controller-test.com");
        String tokenAjeno = tokenPara(duenoAjeno);

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/clientes")
                        .header("Authorization", "Bearer " + tokenAjeno))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/clientes/" + juan.getId())
                        .header("Authorization", "Bearer " + tokenAjeno))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/clientes/" + juan.getId() + "/reservas")
                        .header("Authorization", "Bearer " + tokenAjeno))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Detalle: 404 si el jugador nunca reservo en este establecimiento")
    void obtenerDetalle_Fallo_JugadorNuncaReservoAca() throws Exception {
        Usuario dueno = crearDueno("dueno6@cliente-controller-test.com");
        Establecimiento establecimiento = crearEstablecimiento(dueno, "complejo-cliente-controller-test-5");
        Usuario pedro = crearJugador("pedro6@cliente-controller-test.com", "Pedro Gomez", null);

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/clientes/" + pedro.getId())
                        .header("Authorization", "Bearer " + tokenPara(dueno)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Detalle: trae motivoBloqueo y fechaPrimeraReserva")
    void obtenerDetalle_TraeMotivoBloqueoYFechaPrimeraReserva() throws Exception {
        Usuario dueno = crearDueno("dueno7@cliente-controller-test.com");
        Establecimiento establecimiento = crearEstablecimiento(dueno, "complejo-cliente-controller-test-6");
        Cancha cancha = crearCancha(establecimiento);
        Usuario juan = crearJugador("juan7@cliente-controller-test.com", "Juan Perez", null);
        crearReserva(juan, cancha, EstadoReserva.FINALIZADA, LocalDateTime.now().minusDays(10), new BigDecimal("100.00"));
        crearReserva(juan, cancha, EstadoReserva.FINALIZADA, LocalDateTime.now().minusDays(1), new BigDecimal("100.00"));
        bloqueoJugadorRepository.save(BloqueoJugador.builder()
                .establecimiento(establecimiento).jugador(juan).motivo("No se presento").build());

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/clientes/" + juan.getId())
                        .header("Authorization", "Bearer " + tokenPara(dueno)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.motivoBloqueo").value("No se presento"))
                .andExpect(jsonPath("$.cliente.bloqueado").value(true))
                .andExpect(jsonPath("$.cliente.reservasTotales").value(2));
    }

    @Test
    @DisplayName("Reservas del cliente: lista todos los estados, ordenadas por fechaHoraInicio DESC por defecto")
    void listarReservasDeCliente_TodosLosEstados_OrdenDescendentePorDefecto() throws Exception {
        Usuario dueno = crearDueno("dueno8@cliente-controller-test.com");
        Establecimiento establecimiento = crearEstablecimiento(dueno, "complejo-cliente-controller-test-7");
        Cancha cancha = crearCancha(establecimiento);
        Usuario juan = crearJugador("juan8@cliente-controller-test.com", "Juan Perez", null);
        crearReserva(juan, cancha, EstadoReserva.FINALIZADA, LocalDateTime.now().minusDays(5), new BigDecimal("100.00"));
        crearReserva(juan, cancha, EstadoReserva.CANCELADA, LocalDateTime.now().minusDays(1), new BigDecimal("100.00"));

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/clientes/" + juan.getId() + "/reservas")
                        .header("Authorization", "Bearer " + tokenPara(dueno)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].estado").value("CANCELADA"))
                .andExpect(jsonPath("$.content[1].estado").value("FINALIZADA"));
    }
}
