package com.matiasmeira.sacaladelangulo.support;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.auth.service.JwtService;
import com.matiasmeira.sacaladelangulo.auth.service.UsuarioUserDetailsMapper;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.mails.service.OfertaMarketingBatchSender;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base de los tests de autorización HTTP (pendiente 17): levanta la aplicación completa con
 * MockMvc, la chain de Spring Security real y el JwtService real. No hay @WithMockUser ni
 * mocks del filtro, de JwtService ni de los services de autorización: lo que se prueba es
 * exactamente lo que corre en producción. Lo único que se mockea son los efectos externos
 * (envío de mails).
 *
 * <p><b>Un solo contexto para todo el lote:</b> el nombre de la base H2 es propio de este
 * lote y todas las subclases comparten el mismo {@code @TestPropertySource} (más los mismos
 * {@code @MockitoBean}), así que Spring reutiliza un único contexto en vez de levantar uno
 * por clase.
 *
 * <p><b>Aislamiento:</b> antes de cada test se vacían todas las tablas y se vuelve a sembrar
 * el escenario. No se usa {@code @Transactional} con rollback porque RegistroAuditoriaService
 * abre transacciones REQUIRES_NEW (auditoría de acciones de empleados): una transacción
 * aparte no ve los usuarios sin commitear del test y falla por clave foránea. Con limpieza
 * explícita, cada test arranca de cero sin depender del orden de ejecución ni de lo que
 * dejaron otros tests o clases.
 *
 * <p>El escenario (dos dueños con un establecimiento cada uno, admin, jugador y dos
 * empleados del establecimiento A) usa emails con sufijo único por siembra, para que los
 * buckets del rate limiter (que viven en el contexto compartido) no se arrastren entre tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-seguridad-http;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
public abstract class AbstractSecurityWebTest {

    /**
     * Cuerpo del 403 cuando lo corta @PreAuthorize (mensaje genérico de Spring Security, traducido por
     * GlobalExceptionHandler). Los tests de "rol equivocado" lo aseveran para distinguir ese rechazo
     * del que hace el service después con su propio mensaje: si se pierde la anotación, el service
     * puede seguir devolviendo 403 y el status solo no lo delataría.
     */
    protected static final String ERROR_PREAUTHORIZE = "Access Denied";

    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    /** Duración del token de empleado de mostrador en los tests (mismo orden que en producción). */
    private static final long EXPIRACION_EMPLEADO_MILLIS = 60_000L;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JwtService jwtService;

    @Autowired
    protected UsuarioRepository usuarioRepository;

    @Autowired
    protected EstablecimientoRepository establecimientoRepository;

    @Autowired
    protected CanchaRepository canchaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Efecto externo: ningún test manda mails de verdad. */
    @MockitoBean
    protected EmailService emailService;

    /** Efecto externo: el broadcast de ofertas es @Async y envía mails; se verifica que se invoque o no. */
    @MockitoBean
    protected OfertaMarketingBatchSender ofertaMarketingBatchSender;

    protected Usuario duenoA;
    protected Usuario duenoB;
    protected Usuario admin;
    protected Usuario jugador;
    /** Empleado del establecimiento A con OPERAR_CAJA. */
    protected Usuario empleadoConPermiso;
    /** Empleado del establecimiento A sin ningún permiso. */
    protected Usuario empleadoSinPermiso;
    protected Establecimiento establecimientoA;
    protected Establecimiento establecimientoB;
    protected Cancha canchaA;

    @BeforeEach
    void limpiarYSembrarEscenario() {
        vaciarTodasLasTablas();

        int n = SECUENCIA.incrementAndGet();
        duenoA = guardarUsuario("dueno-a-" + n, Role.OWNER, null, Set.of());
        duenoB = guardarUsuario("dueno-b-" + n, Role.OWNER, null, Set.of());
        admin = guardarUsuario("admin-" + n, Role.ADMIN, null, Set.of());
        jugador = guardarUsuario("jugador-" + n, Role.PLAYER, null, Set.of());

        establecimientoA = establecimientoRepository.save(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo A").slug("complejo-a-" + n).dueno(duenoA)));
        establecimientoB = establecimientoRepository.save(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo B").slug("complejo-b-" + n).dueno(duenoB)));
        canchaA = canchaRepository.save(Canchas.canchaActiva(establecimientoA));

        empleadoConPermiso = guardarUsuario("empleado-con-" + n, Role.EMPLOYEE, establecimientoA,
                Set.of(PermisoEmpleado.OPERAR_CAJA));
        empleadoSinPermiso = guardarUsuario("empleado-sin-" + n, Role.EMPLOYEE, establecimientoA, Set.of());
    }

    /** Token emitido por el JwtService real, igual que en el login. */
    protected String tokenPara(Usuario usuario) {
        return jwtService.generateToken(UsuarioUserDetailsMapper.map(usuario));
    }

    /** Token de sesión de mostrador: mismo mecanismo que AuthService.loginEmpleado (claim empleadoId, vida corta). */
    protected String tokenEmpleado(Usuario empleado) {
        return jwtService.generateToken(UsuarioUserDetailsMapper.map(empleado),
                Map.of("empleadoId", empleado.getId()), EXPIRACION_EMPLEADO_MILLIS);
    }

    /** Valor del header Authorization para el usuario (elige token de empleado o normal según el rol). */
    protected String bearer(Usuario usuario) {
        String token = usuario.getRol() == Role.EMPLOYEE ? tokenEmpleado(usuario) : tokenPara(usuario);
        return "Bearer " + token;
    }

    private Usuario guardarUsuario(String alias, Role rol, Establecimiento establecimiento, Set<PermisoEmpleado> permisos) {
        return usuarioRepository.save(Usuario.builder()
                .email(alias + "@seguridad-http-test.com")
                .password("hash")
                .nombre("Usuario " + alias)
                .rol(rol)
                .establecimiento(establecimiento)
                .permisos(new java.util.HashSet<>(permisos))
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
    }

    private void vaciarTodasLasTablas() {
        List<String> tablas = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'", String.class);
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            for (String tabla : tablas) {
                jdbcTemplate.execute("TRUNCATE TABLE \"" + tabla + "\" RESTART IDENTITY");
            }
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }
}
