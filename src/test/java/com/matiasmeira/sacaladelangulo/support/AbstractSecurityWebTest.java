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
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 *
 * <p><b>Rate limit por IP (pendiente 55):</b> los buckets de RateLimitFilter se indexan por
 * {@code getRemoteAddr()} y no se resetean entre tests (RateLimiterService no expone un reset). Para
 * que un test no deje sin cupo a otro (p. ej. el bucket {@code mail:ip:<ip>}, 5 por minuto, en
 * /api/v1/mails y /api/v1/admin/mails sin token), esta base le asigna a CADA TEST una IP propia
 * ({@link #ipUnica()}) que se aplica sola a todos los requests de {@link #mockMvc}: los tests nuevos
 * no tienen que hacer nada. Reglas para tests nuevos:
 * <ul>
 *   <li>Pegar normalmente con {@code mockMvc}: ya sale de la IP del test, con cupo completo.</li>
 *   <li>Todos los requests de un mismo test comparten esa IP (se acumulan contra el mismo bucket,
 *       como en producción): un test que manda más de 5 mails sin token verá el 429 a propósito.</li>
 *   <li>Para fijar otra IP (p. ej. probar buckets de IPs distintas) usar {@code .with(desdeIp(...))},
 *       que tiene prioridad sobre la IP del test (salvo que sea 127.0.0.1, la de MockMvc); con
 *       {@link #ipUnica()} se obtiene una libre.</li>
 *   <li>Los buckets por usuario ({@code mail:user:<email>}) ya quedan aislados porque el escenario
 *       siembra emails con sufijo único.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AbstractSecurityWebTest.IpPorTestConfig.class)
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

    /** Nombre de la cookie de dispositivo de caja (DispositivoCajaGate.COOKIE_NAME, privado). */
    protected static final String COOKIE_DISPOSITIVO = "saque_caja_device";

    /** Contador de IPs de prueba: ver {@link #ipUnica()}. */
    private static final AtomicInteger SECUENCIA_IP = new AtomicInteger();

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

    /** El mismo BCrypt que usa el login: los empleados con PIN real se siembran con él. */
    @Autowired
    protected PasswordEncoder passwordEncoder;

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

    /** Remote address que MockMvc pone por defecto en los requests. */
    private static final String IP_POR_DEFECTO_MOCKMVC = "127.0.0.1";

    /** IP de origen del test en curso: la aplica {@link IpPorTestConfig} a cada request de mockMvc. */
    private static volatile String ipDelTest = IP_POR_DEFECTO_MOCKMVC;

    /**
     * Registra, como primer filtro del mockMvc autoconfigurado (antes de la chain de seguridad y de
     * RateLimitFilter), uno que reemplaza la IP por defecto de MockMvc por la IP del test en curso.
     * Si el test fijó otra IP con {@link #desdeIp}, se respeta. Se usa un filtro y no
     * {@code defaultRequest} porque Spring Boot ya registra su propio defaultRequest (contexto de
     * seguridad de test) y un segundo lo pisaría.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class IpPorTestConfig {
        @Bean
        FilterRegistrationBean<OncePerRequestFilter> ipPorTestFilter() {
            OncePerRequestFilter filtro = new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                FilterChain chain) throws ServletException, IOException {
                    if (request instanceof MockHttpServletRequest mock
                            && IP_POR_DEFECTO_MOCKMVC.equals(mock.getRemoteAddr())) {
                        mock.setRemoteAddr(ipDelTest);
                    }
                    chain.doFilter(request, response);
                }
            };
            FilterRegistrationBean<OncePerRequestFilter> registro = new FilterRegistrationBean<>(filtro);
            registro.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registro;
        }
    }

    @BeforeEach
    void limpiarYSembrarEscenario() {
        ipDelTest = ipUnica();
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

    /**
     * Empleado extra del establecimiento indicado con los permisos indicados (para los casos que el
     * escenario base no siembra: empleado de B, empleado con todos los permisos). Alias único por llamada.
     */
    protected Usuario empleado(Establecimiento establecimiento, Set<PermisoEmpleado> permisos) {
        return guardarUsuario("empleado-extra-" + SECUENCIA.incrementAndGet(), Role.EMPLOYEE, establecimiento, permisos);
    }

    /**
     * Jugador extra (rol PLAYER, sin establecimiento), para los escenarios que necesitan más de uno
     * además de {@link #jugador}. Alias único por llamada.
     */
    protected Usuario jugadorExtra() {
        return guardarUsuario("jugador-extra-" + SECUENCIA.incrementAndGet(), Role.PLAYER, null, Set.of());
    }

    /** Permisos persistidos del usuario, leídos por JDBC (la colección es lazy y no hay sesión abierta en el test). */
    protected Set<PermisoEmpleado> permisosPersistidos(Usuario usuario) {
        return new java.util.HashSet<>(jdbcTemplate.queryForList(
                "SELECT permiso FROM usuario_permisos WHERE usuario_id = ?", String.class, usuario.getId())
                .stream().map(PermisoEmpleado::valueOf).toList());
    }

    /**
     * Cookie de dispositivo de caja real: activa el local por HTTP (POST activar-local con el token del
     * dueño) y devuelve la cookie que el browser guardaría, tomada del Set-Cookie de la respuesta.
     * {@code dueno} tiene que ser el dueño de {@code establecimiento} (o un admin).
     */
    protected Cookie cookieDispositivo(Establecimiento establecimiento, Usuario dueno) throws Exception {
        MvcResult resultado = mockMvc.perform(post("/api/v1/establecimientos/" + establecimiento.getId()
                        + "/caja/dispositivos/activar-local")
                        .header("Authorization", bearer(dueno))
                        .contentType("application/json")
                        .content("{\"label\":\"Caja " + SECUENCIA.incrementAndGet() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return cookieDeSetCookie(resultado.getResponse().getHeader("Set-Cookie"));
    }

    /** Convierte un header Set-Cookie de dispositivo en la {@link Cookie} que el cliente reenviaría. */
    protected static Cookie cookieDeSetCookie(String setCookie) {
        assertNotNull(setCookie, "la respuesta tenía que traer Set-Cookie");
        return new Cookie(COOKIE_DISPOSITIVO, setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';')));
    }

    /**
     * Empleado de mostrador con PIN real: la contraseña se guarda hasheada con el PasswordEncoder del
     * proyecto (los del escenario base tienen "hash" y no sirven para el login por PIN). El email es
     * único por llamada; el nombre lo elige el test (usar {@link #nombreUnico()}).
     */
    protected Usuario empleadoConPin(Establecimiento establecimiento, String nombre, String pin) {
        return usuarioRepository.save(Usuario.builder()
                .email("empleado-pin-" + SECUENCIA.incrementAndGet() + "@seguridad-http-test.com")
                .password(passwordEncoder.encode(pin))
                .nombre(nombre)
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .permisos(new java.util.HashSet<>(Set.of(PermisoEmpleado.OPERAR_CAJA)))
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
    }

    /** Fija la IP de origen del request (RateLimitFilter y el service usan getRemoteAddr). */
    protected static RequestPostProcessor desdeIp(String ip) {
        return req -> {
            req.setRemoteAddr(ip);
            return req;
        };
    }

    /**
     * IP de prueba que ningún otro test usa: los buckets por IP de RateLimitFilter viven en el contexto
     * compartido y no se resetean, así que cada test que pega a /auth/empleados/login o a
     * /caja/emparejar necesita la suya.
     */
    protected static String ipUnica() {
        int n = SECUENCIA_IP.incrementAndGet();
        return "10.99." + (n / 250) + "." + (n % 250 + 1);
    }

    /**
     * Nombre de empleado único en toda la corrida: el bucket "login-empleado:{estId}:{nombre}" vive en
     * el contexto compartido y el estId se repite entre tests (RESTART IDENTITY).
     */
    protected static String nombreUnico() {
        return "cajero" + SECUENCIA.incrementAndGet();
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
