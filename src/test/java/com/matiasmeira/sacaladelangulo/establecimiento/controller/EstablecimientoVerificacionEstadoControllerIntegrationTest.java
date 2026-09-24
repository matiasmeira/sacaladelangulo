package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.core.exception.LimiteEstablecimientosException;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.repository.RegistroAuditoriaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.service.EstablecimientoOperativoGuard;
import com.matiasmeira.sacaladelangulo.publico.service.ComplejoPublicoService;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.reserva.service.ReservaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * No es @Transactional a propósito: PATCH /estado tiene que commitear de verdad para que
 * ComplejoDetalleCache la desaloje AFTER_COMMIT, mismo criterio que AdminEstablecimientoControllerTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-est-verificacion-estado;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("/api/v1/establecimientos/{id}/solicitar-verificacion, /previsualizacion, /estado")
class EstablecimientoVerificacionEstadoControllerIntegrationTest {

    private static final AtomicInteger CONTADOR = new AtomicInteger();

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
    private RegistroAuditoriaRepository registroAuditoriaRepository;
    @Autowired
    private ComplejoPublicoService complejoPublicoService;
    @Autowired
    private EstablecimientoOperativoGuard establecimientoOperativoGuard;
    @Autowired
    private ReservaService reservaService;

    private Usuario crearUsuario(Role rol) {
        String sufijo = "u" + CONTADOR.incrementAndGet();
        return usuarioRepository.save(Usuario.builder()
                .email(sufijo + "@verif-estado-test.com")
                .password("hash")
                .nombre("Usuario " + sufijo)
                .rol(rol)
                .planSuscripcion(rol == Role.OWNER ? PlanSuscripcion.TRIAL : null)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());
    }

    private Usuario crearEmpleado(Establecimiento establecimiento) {
        String sufijo = "emp" + CONTADOR.incrementAndGet();
        return usuarioRepository.save(Usuario.builder()
                .email(sufijo + "@verif-estado-test.com")
                .password("hash")
                .nombre("Empleado " + sufijo)
                .rol(Role.EMPLOYEE)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .establecimiento(establecimiento)
                .build());
    }

    private Establecimiento crearEstablecimiento(Usuario dueno, boolean activo, EstadoVerificacion estado) {
        String sufijo = "e" + CONTADOR.incrementAndGet();
        return establecimientoRepository.save(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo " + sufijo)
                .direccion("Calle Falsa 123")
                .slug("complejo-" + sufijo)
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(activo)
                .estadoVerificacion(estado)
                .dueno(dueno)));
    }

    private Cancha crearCancha(Establecimiento establecimiento) {
        return canchaRepository.save(Cancha.builder()
                .nombre("Cancha 1")
                .establecimiento(establecimiento)
                .isActive(true)
                .deportes(Set.of(Deporte.FUTBOL_5))
                .precioBase(new BigDecimal("10000"))
                .montoSena(new BigDecimal("2000"))
                .build());
    }

    private String bodySolicitud() {
        return "{\"cuit\":\"20-12345678-6\",\"razonSocial\":\"Mi Club SRL\","
                + "\"telefonoContacto\":\"1122334455\",\"urlRedSocial\":\"https://www.instagram.com/miclub\"}";
    }

    // ---------- solicitar-verificacion: autorización ----------

    @Test
    @DisplayName("dueno_SolicitaVerificacion_QuedaEnRevision_200")
    void dueno_SolicitaVerificacion_QuedaEnRevision_200() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.PENDIENTE);

        mockMvc.perform(post("/api/v1/establecimientos/" + establecimiento.getId() + "/solicitar-verificacion")
                        .with(user(dueno.getEmail()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodySolicitud()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estadoVerificacion").value("EN_REVISION"));

        Establecimiento recargado = establecimientoRepository.findById(establecimiento.getId()).orElseThrow();
        assertThat(recargado.getEstadoVerificacion()).isEqualTo(EstadoVerificacion.EN_REVISION);
        assertThat(recargado.getCuit()).isEqualTo("20123456786");
        assertThat(registroAuditoriaRepository.findAll())
                .anySatisfy(r -> assertThat(r.getAccion()).isEqualTo(AccionAuditoria.SOLICITAR_VERIFICACION_ESTABLECIMIENTO));
    }

    @Test
    @DisplayName("admin_SolicitaVerificacion_403")
    void admin_SolicitaVerificacion_403() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.PENDIENTE);

        mockMvc.perform(post("/api/v1/establecimientos/" + establecimiento.getId() + "/solicitar-verificacion")
                        .with(user("admin@verif-estado-test.com").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodySolicitud()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("empleado_SolicitaVerificacion_403")
    void empleado_SolicitaVerificacion_403() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.PENDIENTE);
        Usuario empleado = crearEmpleado(establecimiento);

        mockMvc.perform(post("/api/v1/establecimientos/" + establecimiento.getId() + "/solicitar-verificacion")
                        .with(user(empleado.getEmail()).roles("EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodySolicitud()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("otroOwner_SolicitaVerificacion_403")
    void otroOwner_SolicitaVerificacion_403() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Usuario otroDueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.PENDIENTE);

        mockMvc.perform(post("/api/v1/establecimientos/" + establecimiento.getId() + "/solicitar-verificacion")
                        .with(user(otroDueno.getEmail()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodySolicitud()))
                .andExpect(status().isForbidden());
    }

    // ---------- solicitar-verificacion: validación ----------

    @Test
    @DisplayName("camposFaltantes_400")
    void camposFaltantes_400() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.PENDIENTE);

        mockMvc.perform(post("/api/v1/establecimientos/" + establecimiento.getId() + "/solicitar-verificacion")
                        .with(user(dueno.getEmail()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cuit\":\"20-12345678-6\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("cuitInvalido_400")
    void cuitInvalido_400() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.PENDIENTE);

        mockMvc.perform(post("/api/v1/establecimientos/" + establecimiento.getId() + "/solicitar-verificacion")
                        .with(user(dueno.getEmail()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cuit\":\"11111111111\",\"razonSocial\":\"X\",\"telefonoContacto\":\"123\","
                                + "\"urlRedSocial\":\"https://www.instagram.com/x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("urlQueNoEsIgNiFb_400")
    void urlQueNoEsIgNiFb_400() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.PENDIENTE);

        mockMvc.perform(post("/api/v1/establecimientos/" + establecimiento.getId() + "/solicitar-verificacion")
                        .with(user(dueno.getEmail()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cuit\":\"20-12345678-6\",\"razonSocial\":\"X\",\"telefonoContacto\":\"123\","
                                + "\"urlRedSocial\":\"https://www.tiktok.com/x\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- solicitar-verificacion: transiciones ----------

    @Test
    @DisplayName("rechazado_Resolicita_QuedaEnRevisionYLimpiaElMotivo")
    void rechazado_Resolicita_QuedaEnRevisionYLimpiaElMotivo() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.RECHAZADO);
        establecimiento.setMotivoRechazo("Faltan fotos");
        establecimientoRepository.save(establecimiento);

        mockMvc.perform(post("/api/v1/establecimientos/" + establecimiento.getId() + "/solicitar-verificacion")
                        .with(user(dueno.getEmail()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodySolicitud()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estadoVerificacion").value("EN_REVISION"));

        Establecimiento recargado = establecimientoRepository.findById(establecimiento.getId()).orElseThrow();
        assertThat(recargado.getMotivoRechazo()).isNull();
    }

    @Test
    @DisplayName("enRevision_Solicitar_400")
    void enRevision_Solicitar_400() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.EN_REVISION);

        mockMvc.perform(post("/api/v1/establecimientos/" + establecimiento.getId() + "/solicitar-verificacion")
                        .with(user(dueno.getEmail()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodySolicitud()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("verificado_Solicitar_400")
    void verificado_Solicitar_400() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.VERIFICADO);

        mockMvc.perform(post("/api/v1/establecimientos/" + establecimiento.getId() + "/solicitar-verificacion")
                        .with(user(dueno.getEmail()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodySolicitud()))
                .andExpect(status().isBadRequest());
    }

    // ---------- previsualizacion ----------

    @Test
    @DisplayName("dueno_Previsualiza_EstablecimientoPendienteYDeshabilitado_200")
    void dueno_Previsualiza_EstablecimientoPendienteYDeshabilitado_200() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, false, EstadoVerificacion.PENDIENTE);

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/previsualizacion")
                        .with(user(dueno.getEmail()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previsualizacion").value(true))
                .andExpect(jsonPath("$.estadoVerificacion").value("PENDIENTE"))
                .andExpect(jsonPath("$.detalle.nombre").value(establecimiento.getNombre()));
    }

    @Test
    @DisplayName("admin_Previsualiza_200")
    void admin_Previsualiza_200() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Usuario admin = crearUsuario(Role.ADMIN);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.EN_REVISION);

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/previsualizacion")
                        .with(user(admin.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estadoVerificacion").value("EN_REVISION"));
    }

    @Test
    @DisplayName("empleado_Previsualiza_403")
    void empleado_Previsualiza_403() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.PENDIENTE);
        Usuario empleado = crearEmpleado(establecimiento);

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/previsualizacion")
                        .with(user(empleado.getEmail()).roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("otroOwner_Previsualiza_403")
    void otroOwner_Previsualiza_403() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Usuario otroDueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.PENDIENTE);

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/previsualizacion")
                        .with(user(otroDueno.getEmail()).roles("OWNER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("sinAutenticar_Previsualiza_401")
    void sinAutenticar_Previsualiza_401() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.PENDIENTE);

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/previsualizacion"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("previsualizacion_CoincideConElPublicoDeUnEstablecimientoVerificado")
    void previsualizacion_CoincideConElPublicoDeUnEstablecimientoVerificado() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.VERIFICADO);
        crearCancha(establecimiento);

        mockMvc.perform(get("/api/v1/publico/complejos/" + establecimiento.getSlug()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value(establecimiento.getNombre()))
                .andExpect(jsonPath("$.precioDesde").value(10000))
                .andExpect(jsonPath("$.canchas[0].nombre").value("Cancha 1"));

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/previsualizacion")
                        .with(user(dueno.getEmail()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detalle.nombre").value(establecimiento.getNombre()))
                .andExpect(jsonPath("$.detalle.precioDesde").value(10000))
                .andExpect(jsonPath("$.detalle.canchas[0].nombre").value("Cancha 1"));
    }

    @Test
    @DisplayName("previsualizarPendiente_YLuegoIntentarReservar_SigueRechazado")
    void previsualizarPendiente_YLuegoIntentarReservar_SigueRechazado() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.PENDIENTE);

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/previsualizacion")
                        .with(user(dueno.getEmail()).roles("OWNER")))
                .andExpect(status().isOk());

        // Mismo chequeo que crearReserva/crearReservaManual corren como primer filtro de
        // negocio antes de tocar disponibilidad -- ver ReservaService líneas 122 y 203: la
        // previsualización de arriba no cambió nada en la base, así que sigue rechazado.
        Establecimiento recargado = establecimientoRepository.findById(establecimiento.getId()).orElseThrow();
        assertThatThrownBy(() -> establecimientoOperativoGuard.validarEstablecimientoOperativoParaJugador(recargado))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ---------- estado (habilitar/deshabilitar) ----------

    @Test
    @DisplayName("dueno_Deshabilita_200_DesapareceDelBuscadorY404EnDetallePublicoSinEsperarTtl")
    void dueno_Deshabilita_200_DesapareceDelBuscadorY404EnDetallePublicoSinEsperarTtl() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.VERIFICADO);
        String slug = establecimiento.getSlug();

        assertThat(complejoPublicoService.obtenerDetalle(slug).nombre()).isEqualTo(establecimiento.getNombre());

        mockMvc.perform(patch("/api/v1/establecimientos/" + establecimiento.getId() + "/estado")
                        .with(user(dueno.getEmail()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activo\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false))
                .andExpect(jsonPath("$.estadoVerificacion").value("VERIFICADO"));

        assertThatThrownBy(() -> complejoPublicoService.obtenerDetalle(slug))
                .isInstanceOf(EntityNotFoundException.class);

        // Rehabilitar: refleja el cambio en el otro sentido sin esperar el TTL, y no vuelve
        // a la cola de revisión (sigue VERIFICADO, ejes independientes).
        mockMvc.perform(patch("/api/v1/establecimientos/" + establecimiento.getId() + "/estado")
                        .with(user(dueno.getEmail()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activo\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.estadoVerificacion").value("VERIFICADO"));

        assertThat(complejoPublicoService.obtenerDetalle(slug).nombre()).isEqualTo(establecimiento.getNombre());

        Establecimiento recargado = establecimientoRepository.findById(establecimiento.getId()).orElseThrow();
        assertThat(recargado.getEstadoVerificacion()).isEqualTo(EstadoVerificacion.VERIFICADO);
    }

    @Test
    @DisplayName("dueno_Deshabilita_InformaCantidadDeReservasFuturasConfirmadasSinCancelarlas")
    void dueno_Deshabilita_InformaCantidadDeReservasFuturasConfirmadasSinCancelarlas() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.VERIFICADO);
        Cancha cancha = crearCancha(establecimiento);
        Usuario jugador = crearUsuario(Role.PLAYER);

        Reserva reserva = reservaRepository.save(Reserva.builder()
                .jugador(jugador)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(LocalDateTime.now().plusDays(2))
                .fechaHoraFin(LocalDateTime.now().plusDays(2).plusHours(1))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(new BigDecimal("10000"))
                .senaPagada(new BigDecimal("2000"))
                .build());

        mockMvc.perform(patch("/api/v1/establecimientos/" + establecimiento.getId() + "/estado")
                        .with(user(dueno.getEmail()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activo\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservasFuturasConfirmadas").value(1));

        Reserva reservaRecargada = reservaRepository.findById(reserva.getId()).orElseThrow();
        assertThat(reservaRecargada.getEstado()).isEqualTo(EstadoReserva.CONFIRMADA);

        // Reserva existente se sigue administrando (cancelar) aunque el establecimiento esté
        // deshabilitado: EstablecimientoOperativoGuard solo bloquea CREAR, y cancelarReserva
        // ni siquiera lo invoca.
        reservaService.cancelarReserva(reserva.getId(), dueno.getEmail());
        Reserva reservaCancelada = reservaRepository.findById(reserva.getId()).orElseThrow();
        assertThat(reservaCancelada.getEstado()).isEqualTo(EstadoReserva.CANCELADA);
    }

    @Test
    @DisplayName("deshabilitado_CrearReservaNuevaQuedaBloqueado")
    void deshabilitado_CrearReservaNuevaQuedaBloqueado() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.VERIFICADO);

        mockMvc.perform(patch("/api/v1/establecimientos/" + establecimiento.getId() + "/estado")
                        .with(user(dueno.getEmail()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activo\": false}"))
                .andExpect(status().isOk());

        Establecimiento recargado = establecimientoRepository.findById(establecimiento.getId()).orElseThrow();
        assertThatThrownBy(() -> establecimientoOperativoGuard.validarEstablecimientoOperativoParaPanel(recargado))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deshabilitado");
        assertThatThrownBy(() -> establecimientoOperativoGuard.validarEstablecimientoOperativoParaJugador(recargado))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("otroOwner_CambiarEstado_403")
    void otroOwner_CambiarEstado_403() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Usuario otroDueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.VERIFICADO);

        mockMvc.perform(patch("/api/v1/establecimientos/" + establecimiento.getId() + "/estado")
                        .with(user(otroDueno.getEmail()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activo\": false}"))
                .andExpect(status().isForbidden());

        Establecimiento recargado = establecimientoRepository.findById(establecimiento.getId()).orElseThrow();
        assertThat(recargado.getIsActive()).isTrue();
    }

    @Test
    @DisplayName("admin_CambiarEstado_403")
    void admin_CambiarEstado_403() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno, true, EstadoVerificacion.VERIFICADO);

        mockMvc.perform(patch("/api/v1/establecimientos/" + establecimiento.getId() + "/estado")
                        .with(user("admin3@verif-estado-test.com").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activo\": false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("deshabilitarUnEstablecimiento_NoLiberaCupoDelLimiteDeTres")
    void deshabilitarUnEstablecimiento_NoLiberaCupoDelLimiteDeTres() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        crearEstablecimiento(dueno, true, EstadoVerificacion.PENDIENTE);
        crearEstablecimiento(dueno, true, EstadoVerificacion.PENDIENTE);
        Establecimiento tercero = crearEstablecimiento(dueno, true, EstadoVerificacion.PENDIENTE);

        mockMvc.perform(patch("/api/v1/establecimientos/" + tercero.getId() + "/estado")
                        .with(user(dueno.getEmail()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activo\": false}"))
                .andExpect(status().isOk());

        assertThat(establecimientoRepository.countByDuenoIdAndDeletedAtIsNull(dueno.getId())).isEqualTo(3);

        mockMvc.perform(post("/api/v1/establecimientos")
                        .with(user(dueno.getEmail()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Cuarto\",\"direccion\":\"Calle 1\",\"latitud\":-34.6,\"longitud\":-58.4,"
                                + "\"requiereSena\":false,\"requiereTelefonoVerificado\":false}"))
                .andExpect(status().isBadRequest());
    }
}
