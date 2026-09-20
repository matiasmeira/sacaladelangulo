package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.auth.service.JwtService;
import com.matiasmeira.sacaladelangulo.auth.service.UsuarioUserDetailsMapper;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.repository.RegistroAuditoriaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.publico.service.ComplejoPublicoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * No es @Transactional a propósito: la verificación tiene que commitear de verdad para que
 * ComplejoDetalleCache la desaloje AFTER_COMMIT y para que EstablecimientoVerificacionEmailListener
 * (también AFTER_COMMIT) dispare, mismo criterio que ExpiracionPruebaServiceIntegrationTest /
 * ComplejoDetalleCacheTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-admin-establecimientos;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("/api/v1/admin/establecimientos")
class AdminEstablecimientoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Autowired
    private RegistroAuditoriaRepository registroAuditoriaRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ComplejoPublicoService complejoPublicoService;

    @MockitoBean
    private EmailService emailService;

    // ---------- 401 sin autenticar ----------

    @Test
    @DisplayName("sinAutenticar_Listar_Devuelve401")
    void sinAutenticar_Listar_Devuelve401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/establecimientos"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("sinAutenticar_Verificar_Devuelve401")
    void sinAutenticar_Verificar_Devuelve401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/establecimientos/1/verificar"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("sinAutenticar_Rechazar_Devuelve401")
    void sinAutenticar_Rechazar_Devuelve401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/establecimientos/1/rechazar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"Datos incompletos\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 403 OWNER / EMPLOYEE ----------

    @Test
    @DisplayName("owner_Listar_Devuelve403")
    void owner_Listar_Devuelve403() throws Exception {
        Usuario owner = crearUsuario("owner-list@admin-est-test.com", Role.OWNER, PlanSuscripcion.TRIAL, null);
        mockMvc.perform(get("/api/v1/admin/establecimientos")
                        .header("Authorization", "Bearer " + tokenPara(owner)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("empleado_Listar_Devuelve403")
    void empleado_Listar_Devuelve403() throws Exception {
        Usuario empleado = crearUsuario("empleado-list@admin-est-test.com", Role.EMPLOYEE, null, null);
        mockMvc.perform(get("/api/v1/admin/establecimientos")
                        .header("Authorization", "Bearer " + tokenPara(empleado)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("owner_Verificar_Devuelve403YNoCambiaElEstado")
    void owner_Verificar_Devuelve403YNoCambiaElEstado() throws Exception {
        Usuario owner = crearUsuario("owner-verificar@admin-est-test.com", Role.OWNER, PlanSuscripcion.TRIAL, null);
        Establecimiento establecimiento = crearEstablecimiento("owner-verificar-slug", owner, EstadoVerificacion.EN_REVISION);

        // El OWNER intenta autoverificar su propio establecimiento: la propiedad no importa
        // acá, @PreAuthorize corta antes de que el service la evalúe.
        mockMvc.perform(post("/api/v1/admin/establecimientos/" + establecimiento.getId() + "/verificar")
                        .header("Authorization", "Bearer " + tokenPara(owner)))
                .andExpect(status().isForbidden());

        Establecimiento recargado = establecimientoRepository.findById(establecimiento.getId()).orElseThrow();
        assertThat(recargado.getEstadoVerificacion()).isEqualTo(EstadoVerificacion.EN_REVISION);
    }

    @Test
    @DisplayName("empleado_Verificar_Devuelve403")
    void empleado_Verificar_Devuelve403() throws Exception {
        Usuario dueno = crearUsuario("dueno-emp-verificar@admin-est-test.com", Role.OWNER, PlanSuscripcion.TRIAL, null);
        Usuario empleado = crearUsuario("empleado-verificar@admin-est-test.com", Role.EMPLOYEE, null, null);
        Establecimiento establecimiento = crearEstablecimiento("empleado-verificar-slug", dueno, EstadoVerificacion.EN_REVISION);

        mockMvc.perform(post("/api/v1/admin/establecimientos/" + establecimiento.getId() + "/verificar")
                        .header("Authorization", "Bearer " + tokenPara(empleado)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("owner_Rechazar_Devuelve403")
    void owner_Rechazar_Devuelve403() throws Exception {
        Usuario owner = crearUsuario("owner-rechazar@admin-est-test.com", Role.OWNER, PlanSuscripcion.TRIAL, null);
        Establecimiento establecimiento = crearEstablecimiento("owner-rechazar-slug", owner, EstadoVerificacion.EN_REVISION);

        mockMvc.perform(post("/api/v1/admin/establecimientos/" + establecimiento.getId() + "/rechazar")
                        .header("Authorization", "Bearer " + tokenPara(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"Datos incompletos\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("empleado_Rechazar_Devuelve403")
    void empleado_Rechazar_Devuelve403() throws Exception {
        Usuario dueno = crearUsuario("dueno-emp-rechazar@admin-est-test.com", Role.OWNER, PlanSuscripcion.TRIAL, null);
        Usuario empleado = crearUsuario("empleado-rechazar@admin-est-test.com", Role.EMPLOYEE, null, null);
        Establecimiento establecimiento = crearEstablecimiento("empleado-rechazar-slug", dueno, EstadoVerificacion.EN_REVISION);

        mockMvc.perform(post("/api/v1/admin/establecimientos/" + establecimiento.getId() + "/rechazar")
                        .header("Authorization", "Bearer " + tokenPara(empleado))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"Datos incompletos\"}"))
                .andExpect(status().isForbidden());
    }

    // ---------- verificar: éxito, trial, auditoría, caché ----------

    @Test
    @DisplayName("admin_VerificaEstablecimientoEnRevision_QuedaVerificadoAuditaYArrancaElTrialDeUnMes")
    void admin_VerificaEstablecimientoEnRevision_QuedaVerificadoAuditaYArrancaElTrialDeUnMes() throws Exception {
        Usuario admin = crearUsuario("admin-verificar@admin-est-test.com", Role.ADMIN, null, null);
        Usuario dueno = crearUsuario("dueno-verificar@admin-est-test.com", Role.OWNER, PlanSuscripcion.TRIAL, null);
        Establecimiento establecimiento = crearEstablecimiento("verificar-slug", dueno, EstadoVerificacion.EN_REVISION);

        mockMvc.perform(post("/api/v1/admin/establecimientos/" + establecimiento.getId() + "/verificar")
                        .header("Authorization", "Bearer " + tokenPara(admin)))
                .andExpect(status().isNoContent());

        Establecimiento recargado = establecimientoRepository.findById(establecimiento.getId()).orElseThrow();
        assertThat(recargado.getEstadoVerificacion()).isEqualTo(EstadoVerificacion.VERIFICADO);
        assertThat(recargado.getFechaVerificacion()).isNotNull();
        assertThat(recargado.getVerificadoPor().getId()).isEqualTo(admin.getId());
        assertThat(recargado.getMotivoRechazo()).isNull();

        Usuario duenoRecargado = usuarioRepository.findById(dueno.getId()).orElseThrow();
        assertThat(duenoRecargado.getFechaFinPrueba()).isAfter(LocalDateTime.now().plusDays(29));

        assertThat(registroAuditoriaRepository.findAll())
                .anySatisfy(r -> assertThat(r.getAccion()).isEqualTo(AccionAuditoria.VERIFICAR_ESTABLECIMIENTO));

        verify(emailService, timeout(5000)).enviar(eq(dueno.getEmail()), any(), any());
    }

    @Test
    @DisplayName("admin_VerificaSegundoEstablecimientoDelMismoDueno_NoReiniciaElTrial")
    void admin_VerificaSegundoEstablecimientoDelMismoDueno_NoReiniciaElTrial() throws Exception {
        Usuario admin = crearUsuario("admin-segundo@admin-est-test.com", Role.ADMIN, null, null);
        LocalDateTime fechaExistente = LocalDateTime.now().plusDays(10);
        Usuario dueno = crearUsuario("dueno-segundo@admin-est-test.com", Role.OWNER, PlanSuscripcion.TRIAL, fechaExistente);
        Establecimiento establecimiento = crearEstablecimiento("segundo-establecimiento-slug", dueno, EstadoVerificacion.EN_REVISION);

        mockMvc.perform(post("/api/v1/admin/establecimientos/" + establecimiento.getId() + "/verificar")
                        .header("Authorization", "Bearer " + tokenPara(admin)))
                .andExpect(status().isNoContent());

        Usuario duenoRecargado = usuarioRepository.findById(dueno.getId()).orElseThrow();
        assertThat(duenoRecargado.getFechaFinPrueba()).isEqualToIgnoringNanos(fechaExistente);
    }

    @Test
    @DisplayName("admin_VerificaEstablecimientoDeDuenoYaEnFree_NoLeSeteaFechaFinPrueba")
    void admin_VerificaEstablecimientoDeDuenoYaEnFree_NoLeSeteaFechaFinPrueba() throws Exception {
        Usuario admin = crearUsuario("admin-free@admin-est-test.com", Role.ADMIN, null, null);
        Usuario dueno = crearUsuario("dueno-free@admin-est-test.com", Role.OWNER, PlanSuscripcion.FREE, null);
        Establecimiento establecimiento = crearEstablecimiento("dueno-free-slug", dueno, EstadoVerificacion.EN_REVISION);

        mockMvc.perform(post("/api/v1/admin/establecimientos/" + establecimiento.getId() + "/verificar")
                        .header("Authorization", "Bearer " + tokenPara(admin)))
                .andExpect(status().isNoContent());

        Usuario duenoRecargado = usuarioRepository.findById(dueno.getId()).orElseThrow();
        assertThat(duenoRecargado.getFechaFinPrueba()).isNull();
        assertThat(duenoRecargado.getPlanSuscripcion()).isEqualTo(PlanSuscripcion.FREE);
    }

    @Test
    @DisplayName("admin_Verifica_InvalidaLaCachePublicaYElDetalleQuedaDisponibleSinEsperarElTtl")
    void admin_Verifica_InvalidaLaCachePublicaYElDetalleQuedaDisponibleSinEsperarElTtl() throws Exception {
        Usuario admin = crearUsuario("admin-cache@admin-est-test.com", Role.ADMIN, null, null);
        Usuario dueno = crearUsuario("dueno-cache@admin-est-test.com", Role.OWNER, PlanSuscripcion.TRIAL, null);
        String slug = "cache-verificar-slug";
        Establecimiento establecimiento = crearEstablecimiento(slug, dueno, EstadoVerificacion.EN_REVISION);

        // Antes de verificar, la ficha pública no existe (findBySlugOperativo exige VERIFICADO).
        assertThatThrownBy(() -> complejoPublicoService.obtenerDetalle(slug))
                .isInstanceOf(com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException.class);

        mockMvc.perform(post("/api/v1/admin/establecimientos/" + establecimiento.getId() + "/verificar")
                        .header("Authorization", "Bearer " + tokenPara(admin)))
                .andExpect(status().isNoContent());

        // Sin la invalidación explícita en la misma transacción del verificar, esto podría
        // seguir sirviendo una entrada vieja de la caché in-process hasta el TTL configurado
        // (5 minutos, ver application.properties) en vez del estado recién commiteado.
        assertThat(complejoPublicoService.obtenerDetalle(slug).nombre()).isEqualTo(establecimiento.getNombre());
    }

    // ---------- rechazar ----------

    @Test
    @DisplayName("admin_RechazaEstablecimientoEnRevision_GuardaElMotivoYNoTocaElTrial")
    void admin_RechazaEstablecimientoEnRevision_GuardaElMotivoYNoTocaElTrial() throws Exception {
        Usuario admin = crearUsuario("admin-rechazar@admin-est-test.com", Role.ADMIN, null, null);
        Usuario dueno = crearUsuario("dueno-rechazar@admin-est-test.com", Role.OWNER, PlanSuscripcion.TRIAL, null);
        Establecimiento establecimiento = crearEstablecimiento("rechazar-slug", dueno, EstadoVerificacion.EN_REVISION);

        mockMvc.perform(post("/api/v1/admin/establecimientos/" + establecimiento.getId() + "/rechazar")
                        .header("Authorization", "Bearer " + tokenPara(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"Faltan fotos del frente del local\"}"))
                .andExpect(status().isNoContent());

        Establecimiento recargado = establecimientoRepository.findById(establecimiento.getId()).orElseThrow();
        assertThat(recargado.getEstadoVerificacion()).isEqualTo(EstadoVerificacion.RECHAZADO);
        assertThat(recargado.getMotivoRechazo()).isEqualTo("Faltan fotos del frente del local");
        assertThat(recargado.getFechaVerificacion()).isNull();

        Usuario duenoRecargado = usuarioRepository.findById(dueno.getId()).orElseThrow();
        assertThat(duenoRecargado.getFechaFinPrueba()).isNull();

        assertThat(registroAuditoriaRepository.findAll())
                .anySatisfy(r -> assertThat(r.getAccion()).isEqualTo(AccionAuditoria.RECHAZAR_ESTABLECIMIENTO));

        verify(emailService, timeout(5000)).enviar(eq(dueno.getEmail()), any(), any());
    }

    @Test
    @DisplayName("admin_RechazaSinMotivo_Devuelve400")
    void admin_RechazaSinMotivo_Devuelve400() throws Exception {
        Usuario admin = crearUsuario("admin-sin-motivo@admin-est-test.com", Role.ADMIN, null, null);
        Usuario dueno = crearUsuario("dueno-sin-motivo@admin-est-test.com", Role.OWNER, PlanSuscripcion.TRIAL, null);
        Establecimiento establecimiento = crearEstablecimiento("sin-motivo-slug", dueno, EstadoVerificacion.EN_REVISION);

        mockMvc.perform(post("/api/v1/admin/establecimientos/" + establecimiento.getId() + "/rechazar")
                        .header("Authorization", "Bearer " + tokenPara(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- transiciones inválidas ----------

    @Test
    @DisplayName("admin_VerificaUnEstablecimientoYaVerificado_Devuelve400")
    void admin_VerificaUnEstablecimientoYaVerificado_Devuelve400() throws Exception {
        Usuario admin = crearUsuario("admin-transicion1@admin-est-test.com", Role.ADMIN, null, null);
        Usuario dueno = crearUsuario("dueno-transicion1@admin-est-test.com", Role.OWNER, PlanSuscripcion.TRIAL, null);
        Establecimiento establecimiento = crearEstablecimiento("ya-verificado-slug", dueno, EstadoVerificacion.VERIFICADO);

        mockMvc.perform(post("/api/v1/admin/establecimientos/" + establecimiento.getId() + "/verificar")
                        .header("Authorization", "Bearer " + tokenPara(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("admin_RechazaUnEstablecimientoPendiente_Devuelve400")
    void admin_RechazaUnEstablecimientoPendiente_Devuelve400() throws Exception {
        Usuario admin = crearUsuario("admin-transicion2@admin-est-test.com", Role.ADMIN, null, null);
        Usuario dueno = crearUsuario("dueno-transicion2@admin-est-test.com", Role.OWNER, PlanSuscripcion.TRIAL, null);
        Establecimiento establecimiento = crearEstablecimiento("pendiente-slug", dueno, EstadoVerificacion.PENDIENTE);

        mockMvc.perform(post("/api/v1/admin/establecimientos/" + establecimiento.getId() + "/rechazar")
                        .header("Authorization", "Bearer " + tokenPara(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"Datos incompletos\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- listar ----------

    @Test
    @DisplayName("admin_Lista_FiltraPorEstadoVerificacion")
    void admin_Lista_FiltraPorEstadoVerificacion() throws Exception {
        // La clase no es @Transactional (ver javadoc de la clase), así que la base H2 se
        // comparte entre tests del mismo método de ejecución: se verifica que la fila propia
        // aparezca (y la de otro estado no), en vez de un conteo exacto de "$.content", que
        // sería frágil ante establecimientos creados por otros tests de esta clase.
        Usuario admin = crearUsuario("admin-listar@admin-est-test.com", Role.ADMIN, null, null);
        Usuario dueno = crearUsuario("dueno-listar@admin-est-test.com", Role.OWNER, PlanSuscripcion.TRIAL, null);
        crearEstablecimiento("listar-en-revision", dueno, EstadoVerificacion.EN_REVISION);
        crearEstablecimiento("listar-verificado", dueno, EstadoVerificacion.VERIFICADO);

        mockMvc.perform(get("/api/v1/admin/establecimientos")
                        .param("estadoVerificacion", "EN_REVISION")
                        .param("size", "200")
                        .header("Authorization", "Bearer " + tokenPara(admin)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content[*].nombre")
                        .value(org.hamcrest.Matchers.hasItem("Complejo listar-en-revision")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content[*].nombre")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("Complejo listar-verificado"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content[?(@.nombre=='Complejo listar-en-revision')].duenoEmail")
                        .value(org.hamcrest.Matchers.contains(dueno.getEmail())));
    }

    private String tokenPara(Usuario usuario) {
        return jwtService.generateToken(UsuarioUserDetailsMapper.map(usuario));
    }

    private Usuario crearUsuario(String email, Role rol, PlanSuscripcion plan, LocalDateTime fechaFinPrueba) {
        return usuarioRepository.save(Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Usuario Test")
                .rol(rol)
                .planSuscripcion(plan)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .fechaFinPrueba(fechaFinPrueba)
                .build());
    }

    private Establecimiento crearEstablecimiento(String slug, Usuario dueno, EstadoVerificacion estado) {
        return establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo " + slug)
                .direccion("Calle Falsa 123")
                .slug(slug)
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .estadoVerificacion(estado)
                .dueno(dueno)
                .build());
    }
}
