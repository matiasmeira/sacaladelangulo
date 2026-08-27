package com.matiasmeira.sacaladelangulo.publico.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.core.imagekit.FotoSubida;
import com.matiasmeira.sacaladelangulo.core.imagekit.ImageKitService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CanchaRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.EstablecimientoRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.FotoEstablecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.service.CanchaService;
import com.matiasmeira.sacaladelangulo.establecimiento.service.EstablecimientoService;
import com.matiasmeira.sacaladelangulo.establecimiento.service.FotoEstablecimientoService;
import com.matiasmeira.sacaladelangulo.feedback.dto.FeedbackRequest;
import com.matiasmeira.sacaladelangulo.feedback.service.FeedbackService;
import com.matiasmeira.sacaladelangulo.publico.dto.CanchaPublicaDto;
import com.matiasmeira.sacaladelangulo.publico.dto.ComplejoDetalleResponse;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La ficha pública se cachea por slug con TTL, así que un segundo visitante dentro de la
 * ventana no vuelve a pegarle a la base. El espía va sobre EstablecimientoRepository y no
 * sobre el servicio: cuando la caché acierta, ComplejoPublicoService#obtenerDetalle no se
 * ejecuta y por lo tanto findBySlugAndIsActiveTrue tampoco -- contar esa invocación es la
 * forma directa de distinguir un hit de un miss.
 *
 * <p>El resto de los tests cubre la otra mitad del contrato: el payload de la ficha no se
 * arma sólo con columnas del establecimiento, sino también con sus canchas y sus fotos. Cada
 * camino de escritura que toca alguna de esas piezas tiene que invalidar la entrada, y se
 * verifica por el dato que devuelve la lectura siguiente (nombre, precio, cantidad de fotos,
 * orden) en vez de espiar al invalidador: lo que importa es que el visitante no vea una
 * ficha vieja, no que se haya llamado a tal colaborador.
 *
 * <p>Esta clase NO es {@code @Transactional} a propósito. La invalidación se registra con
 * TransactionSynchronization para correr recién después del commit (si desalojara dentro de
 * la transacción, un lector concurrente podría repoblar la caché con la fila vieja antes de
 * que el commit aterrice, y esa entrada envenenada sobreviviría hasta el TTL). Con la clase
 * anotada {@code @Transactional} el commit nunca ocurre y el afterCommit no dispararía
 * nunca: el test pasaría en verde sin probar nada. Mismo criterio que
 * ComplejoPublicoControllerDetalleNoTransactionalTest.
 *
 * <p>Cada test usa su propio slug en vez de limpiar la caché entre tests, así el aislamiento
 * no depende de un hook de limpieza que se pueda olvidar.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-cache-detalle;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("Caché de la ficha pública de complejos")
class ComplejoDetalleCacheTest {

    /** Firma PNG (89 50 4E 47 0D 0A 1A 0A) + relleno hasta los 12 bytes que exige ValidadorFoto. */
    private static final byte[] PNG_MINIMO = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x00};

    @Autowired
    private ComplejoPublicoService complejoPublicoService;

    @Autowired
    private EstablecimientoService establecimientoService;

    @Autowired
    private CanchaService canchaService;

    @Autowired
    private FotoEstablecimientoService fotoEstablecimientoService;

    @Autowired
    private FeedbackService feedbackService;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private CanchaRepository canchaRepository;

    @Autowired
    private ReservaRepository reservaRepository;

    @MockitoSpyBean
    private EstablecimientoRepository establecimientoRepository;

    /**
     * Se mockea el cliente de ImageKit y no el ValidadorFoto: el validador corre de verdad
     * sobre los magic bytes de PNG_MINIMO, que es comportamiento propio del proyecto. Lo
     * único que se corta es la llamada de red al CDN.
     */
    @MockitoBean
    private ImageKitService imageKitService;

    @Test
    @DisplayName("segundaLecturaDelMismoSlug_DentroDelTtl_NoVuelveAConsultarLaBase")
    void segundaLecturaDelMismoSlug_DentroDelTtl_NoVuelveAConsultarLaBase() {
        String slug = "cache-hit-test";
        seedComplejo(slug, "Complejo Cache Hit", "dueno-hit@cache-test.com");

        complejoPublicoService.obtenerDetalle(slug);
        ComplejoDetalleResponse segunda = complejoPublicoService.obtenerDetalle(slug);

        assertThat(segunda.nombre()).isEqualTo("Complejo Cache Hit");
        verify(establecimientoRepository, times(1)).findBySlugAndIsActiveTrue(slug);
    }

    @Test
    @DisplayName("editarElEstablecimiento_InvalidaLaFicha_YLaProximaLecturaTraeElNombreNuevo")
    void editarElEstablecimiento_InvalidaLaFicha_YLaProximaLecturaTraeElNombreNuevo() {
        String slug = "cache-evict-test";
        String email = "dueno-evict@cache-test.com";
        Establecimiento establecimiento = seedComplejo(slug, "Nombre Viejo", email);

        assertThat(complejoPublicoService.obtenerDetalle(slug).nombre()).isEqualTo("Nombre Viejo");

        establecimientoService.actualizarEstablecimiento(
                establecimiento.getId(),
                new EstablecimientoRequest("Nombre Nuevo", "Calle Falsa 123", -34.6, -58.4,
                        false, false, null, null),
                email);

        assertThat(complejoPublicoService.obtenerDetalle(slug).nombre()).isEqualTo("Nombre Nuevo");
        verify(establecimientoRepository, times(2)).findBySlugAndIsActiveTrue(slug);
    }

    @Test
    @DisplayName("slugInexistente_NoSeCachea_ConsultaLaBaseEnCadaIntento")
    void slugInexistente_NoSeCachea_ConsultaLaBaseEnCadaIntento() {
        String slug = "slug-que-no-existe";

        assertThatThrownBy(() -> complejoPublicoService.obtenerDetalle(slug))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> complejoPublicoService.obtenerDetalle(slug))
                .isInstanceOf(EntityNotFoundException.class);

        verify(establecimientoRepository, times(2)).findBySlugAndIsActiveTrue(slug);
    }

    @Test
    @DisplayName("crearUnaCancha_InvalidaLaFicha_YApareceEnLaProximaLectura")
    void crearUnaCancha_InvalidaLaFicha_YApareceEnLaProximaLectura() {
        String slug = "cache-cancha-crear";
        String email = "dueno-cancha-crear@cache-test.com";
        Establecimiento establecimiento = seedComplejo(slug, "Complejo Canchas", email);

        assertThat(complejoPublicoService.obtenerDetalle(slug).canchas()).isEmpty();

        canchaService.crearCancha(establecimiento.getId(), canchaRequest("Cancha 1", "10000"), email);

        assertThat(complejoPublicoService.obtenerDetalle(slug).canchas())
                .extracting(CanchaPublicaDto::nombre)
                .containsExactly("Cancha 1");
    }

    @Test
    @DisplayName("actualizarUnaCancha_InvalidaLaFicha_YElPrecioNuevoApareceEnLaProximaLectura")
    void actualizarUnaCancha_InvalidaLaFicha_YElPrecioNuevoApareceEnLaProximaLectura() {
        String slug = "cache-cancha-editar";
        String email = "dueno-cancha-editar@cache-test.com";
        Establecimiento establecimiento = seedComplejo(slug, "Complejo Canchas", email);
        Long canchaId = canchaService.crearCancha(establecimiento.getId(),
                canchaRequest("Cancha 1", "10000"), email).id();

        assertThat(complejoPublicoService.obtenerDetalle(slug).precioDesde()).isEqualByComparingTo("10000");

        canchaService.actualizarCancha(establecimiento.getId(), canchaId,
                canchaRequest("Cancha 1", "5000"), email);

        assertThat(complejoPublicoService.obtenerDetalle(slug).precioDesde()).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("desactivarUnaCancha_InvalidaLaFicha_YDesapareceDeLaProximaLectura")
    void desactivarUnaCancha_InvalidaLaFicha_YDesapareceDeLaProximaLectura() {
        String slug = "cache-cancha-baja";
        String email = "dueno-cancha-baja@cache-test.com";
        Establecimiento establecimiento = seedComplejo(slug, "Complejo Canchas", email);
        Long canchaId = canchaService.crearCancha(establecimiento.getId(),
                canchaRequest("Cancha 1", "10000"), email).id();

        assertThat(complejoPublicoService.obtenerDetalle(slug).canchas()).hasSize(1);

        canchaService.desactivarCancha(establecimiento.getId(), canchaId, email);

        assertThat(complejoPublicoService.obtenerDetalle(slug).canchas()).isEmpty();
    }

    @Test
    @DisplayName("subirUnaFoto_InvalidaLaFicha_YApareceEnLaProximaLectura")
    void subirUnaFoto_InvalidaLaFicha_YApareceEnLaProximaLectura() {
        String slug = "cache-foto-subir";
        String email = "dueno-foto-subir@cache-test.com";
        Establecimiento establecimiento = seedComplejo(slug, "Complejo Fotos", email);
        when(imageKitService.subir(any(), anyString(), anyString()))
                .thenReturn(new FotoSubida("https://cdn.example.com/nueva.jpg", "file_nueva"));

        assertThat(complejoPublicoService.obtenerDetalle(slug).fotos()).isEmpty();

        fotoEstablecimientoService.subir(establecimiento.getId(), PNG_MINIMO, "nueva.png", email);

        assertThat(complejoPublicoService.obtenerDetalle(slug).fotos())
                .containsExactly("https://cdn.example.com/nueva.jpg");
    }

    @Test
    @DisplayName("borrarUnaFoto_InvalidaLaFicha_YDesapareceDeLaProximaLectura")
    void borrarUnaFoto_InvalidaLaFicha_YDesapareceDeLaProximaLectura() {
        String slug = "cache-foto-borrar";
        String email = "dueno-foto-borrar@cache-test.com";
        Establecimiento establecimiento = seedComplejoConFotos(slug, email);

        assertThat(complejoPublicoService.obtenerDetalle(slug).fotos()).hasSize(2);

        fotoEstablecimientoService.borrar(establecimiento.getId(), "file_a", email);

        assertThat(complejoPublicoService.obtenerDetalle(slug).fotos())
                .containsExactly("https://cdn.example.com/b.jpg");
    }

    @Test
    @DisplayName("reordenarLasFotos_InvalidaLaFicha_YElOrdenNuevoApareceEnLaProximaLectura")
    void reordenarLasFotos_InvalidaLaFicha_YElOrdenNuevoApareceEnLaProximaLectura() {
        String slug = "cache-foto-reordenar";
        String email = "dueno-foto-reordenar@cache-test.com";
        Establecimiento establecimiento = seedComplejoConFotos(slug, email);

        assertThat(complejoPublicoService.obtenerDetalle(slug).fotos())
                .containsExactly("https://cdn.example.com/a.jpg", "https://cdn.example.com/b.jpg");

        fotoEstablecimientoService.reordenar(establecimiento.getId(), List.of("file_b", "file_a"), email);

        assertThat(complejoPublicoService.obtenerDetalle(slug).fotos())
                .containsExactly("https://cdn.example.com/b.jpg", "https://cdn.example.com/a.jpg");
    }

    /**
     * Con caché in-process, la MISMA instancia del DTO se le devuelve a todos los visitantes
     * mientras la entrada viva. Si alguna de sus colecciones fuera mutable, un consumidor que
     * la modificara estaría corrompiendo la ficha para todos los demás hasta que expire el
     * TTL. Se verifica sobre deportes porque es la única que se construía con
     * Collectors.toSet() (un HashSet mutable); el resto ya salía inmutable.
     */
    @Test
    @DisplayName("lasColeccionesDeLaFichaCacheada_SonInmutables_ParaQueUnConsumidorNoLaCorrompa()")
    void lasColeccionesDeLaFichaCacheada_SonInmutables_ParaQueUnConsumidorNoLaCorrompa() {
        String slug = "cache-inmutable";
        String email = "dueno-inmutable@cache-test.com";
        Establecimiento establecimiento = seedComplejo(slug, "Complejo Inmutable", email);
        canchaService.crearCancha(establecimiento.getId(), canchaRequest("Cancha 1", "10000"), email);

        ComplejoDetalleResponse ficha = complejoPublicoService.obtenerDetalle(slug);

        assertThat(ficha.deportes()).containsExactly(Deporte.FUTBOL_5);
        assertThatThrownBy(() -> ficha.deportes().add(Deporte.PADEL))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("dejarUnaCalificacion_InvalidaLaFicha_YElPromedioNuevoApareceEnLaProximaLectura")
    void dejarUnaCalificacion_InvalidaLaFicha_YElPromedioNuevoApareceEnLaProximaLectura() {
        String slug = "cache-feedback-crear";
        String emailDueno = "dueno-feedback-crear@cache-test.com";
        String emailJugador = "jugador-feedback-crear@cache-test.com";
        Establecimiento establecimiento = seedComplejo(slug, "Complejo Feedback", emailDueno);
        Reserva reserva = seedReservaFinalizada(establecimiento, emailDueno, emailJugador);

        ComplejoDetalleResponse antes = complejoPublicoService.obtenerDetalle(slug);
        assertThat(antes.cantidadCalificaciones()).isZero();
        assertThat(antes.promedioCalificacion()).isNull();

        feedbackService.crearFeedback(reserva.getId(), new FeedbackRequest(5, "Muy buena cancha"), emailJugador);

        ComplejoDetalleResponse despues = complejoPublicoService.obtenerDetalle(slug);
        assertThat(despues.cantidadCalificaciones()).isEqualTo(1L);
        assertThat(despues.promedioCalificacion()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("fijarUnComentarioDestacado_InvalidaLaFicha_YApareceEnLaProximaLectura")
    void fijarUnComentarioDestacado_InvalidaLaFicha_YApareceEnLaProximaLectura() {
        String slug = "cache-feedback-destacar";
        String emailDueno = "dueno-feedback-destacar@cache-test.com";
        String emailJugador = "jugador-feedback-destacar@cache-test.com";
        Establecimiento establecimiento = seedComplejo(slug, "Complejo Feedback", emailDueno);
        Reserva reserva = seedReservaFinalizada(establecimiento, emailDueno, emailJugador);
        Long feedbackId = feedbackService
                .crearFeedback(reserva.getId(), new FeedbackRequest(4, "Buena atencion"), emailJugador).id();

        assertThat(complejoPublicoService.obtenerDetalle(slug).comentarioDestacado()).isNull();

        feedbackService.fijarComentario(feedbackId, emailDueno);

        assertThat(complejoPublicoService.obtenerDetalle(slug).comentarioDestacado())
                .isNotNull()
                .satisfies(destacado -> assertThat(destacado.comentario()).isEqualTo("Buena atencion"));
    }

    private Reserva seedReservaFinalizada(Establecimiento establecimiento, String emailDueno, String emailJugador) {
        Long canchaId = canchaService
                .crearCancha(establecimiento.getId(), canchaRequest("Cancha Feedback", "10000"), emailDueno).id();
        Cancha cancha = canchaRepository.findById(canchaId).orElseThrow();

        Usuario jugador = usuarioRepository.save(Usuario.builder()
                .email(emailJugador)
                .password("hash")
                .nombre("Carlos Fernandez")
                .rol(Role.PLAYER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());

        LocalDateTime inicio = LocalDateTime.now().minusDays(1);
        return reservaRepository.save(Reserva.builder()
                .jugador(jugador)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(inicio)
                .fechaHoraFin(inicio.plusHours(1))
                .estado(EstadoReserva.FINALIZADA)
                .precioTotal(new BigDecimal("10000"))
                .senaPagada(BigDecimal.ZERO)
                .fechaCreacion(inicio.minusDays(1))
                .build());
    }

    /**
     * Las colecciones van mutables a propósito, porque CanchaService#actualizarCancha se las
     * asigna tal cual a la entidad y en el merge Hibernate hace clear()+addAll() sobre esa
     * misma instancia: cualquier colección inmutable ahí adentro hace que el update explote
     * con UnsupportedOperationException.
     *
     * <p>Por eso también se pasan duraciones explícitas en vez de dejar el campo en null: con
     * null, actualizarCancha cae en su constante DURACIONES_POR_DEFECTO (un List.of(...)
     * estático) y rompe. Ese bug es previo a la caché y se arregla aparte; acá sólo se lo
     * esquiva para que estos tests midan invalidación y no otra cosa.
     */
    private CanchaRequest canchaRequest(String nombre, String precioBase) {
        return new CanchaRequest(nombre, new HashSet<>(Set.of(Deporte.FUTBOL_5)), new BigDecimal(precioBase),
                null, new ArrayList<>(List.of(60, 90)), null, true, null, null, null);
    }

    private Establecimiento seedComplejoConFotos(String slug, String email) {
        Establecimiento establecimiento = seedComplejo(slug, "Complejo Fotos", email);
        establecimiento.setFotos(new ArrayList<>(List.of(
                FotoEstablecimiento.builder().url("https://cdn.example.com/a.jpg").fileId("file_a").build(),
                FotoEstablecimiento.builder().url("https://cdn.example.com/b.jpg").fileId("file_b").build())));
        return establecimientoRepository.save(establecimiento);
    }

    private Establecimiento seedComplejo(String slug, String nombre, String email) {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Dueno Cache Test")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());

        return establecimientoRepository.save(Establecimiento.builder()
                .nombre(nombre)
                .direccion("Calle Falsa 123")
                .slug(slug)
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());
    }
}
