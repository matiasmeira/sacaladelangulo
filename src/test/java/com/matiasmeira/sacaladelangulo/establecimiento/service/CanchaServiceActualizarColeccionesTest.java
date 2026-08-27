package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CanchaRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CanchaResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * actualizarCancha le asigna a la entidad varias colecciones que salen del request o de
 * constantes del servicio. Cancha.duracionesPermitidas y Cancha.deportes son
 * {@code @ElementCollection}: durante el merge, Hibernate hace clear()+addAll() sobre la
 * instancia exacta que se le haya asignado. Cualquier colección inmutable que llegue ahí
 * termina en UnsupportedOperationException, y cualquier colección compartida (una constante
 * estática, o la lista que sigue en manos del caller) queda expuesta a que Hibernate la pise.
 *
 * <p>Los dos síntomas se cubren acá porque son el mismo defecto: asignar una colección ajena
 * en vez de una copia propia. Las demás colecciones que toca el método ya venían protegidas
 * (resolverCanchasFisicas, mapearTarifas y normalizarPrecios devuelven instancias nuevas).
 *
 * <p>Necesita el contexto real de JPA y NO puede ser {@code @Transactional}: el fallo ocurre
 * en el merge de Hibernate, que un CanchaRepository mockeado (como en CanchaServiceTest)
 * nunca ejecuta.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-cancha-colecciones;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("CanchaService.actualizarCancha - colecciones que Hibernate muta en el merge")
class CanchaServiceActualizarColeccionesTest {

    private static final List<Integer> DURACIONES_POR_DEFECTO_ESPERADAS = List.of(60, 90, 120);

    @Autowired
    private CanchaService canchaService;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Test
    @DisplayName("sinDuracionesEnElRequest_GuardaLasPorDefecto_SinRomperEnElMerge")
    void sinDuracionesEnElRequest_GuardaLasPorDefecto_SinRomperEnElMerge() {
        String email = "dueno-duraciones@cancha-test.com";
        Establecimiento establecimiento = seedComplejo("complejo-duraciones", email);
        Long canchaId = canchaService
                .crearCancha(establecimiento.getId(), request("Cancha 1", List.of(60)), email).id();

        CanchaResponse actualizada = canchaService
                .actualizarCancha(establecimiento.getId(), canchaId, request("Cancha 1", null), email);

        assertThat(actualizada.duracionesPermitidas()).containsExactlyElementsOf(DURACIONES_POR_DEFECTO_ESPERADAS);
        assertThat(duracionesPersistidas(establecimiento, email).get("Cancha 1"))
                .containsExactlyElementsOf(DURACIONES_POR_DEFECTO_ESPERADAS);
    }

    @Test
    @DisplayName("dosCanchasSinDuraciones_NoCompartenLaMismaListaNiSePisanEntreSi")
    void dosCanchasSinDuraciones_NoCompartenLaMismaListaNiSePisanEntreSi() {
        String email = "dueno-duraciones-2@cancha-test.com";
        Establecimiento establecimiento = seedComplejo("complejo-duraciones-2", email);
        Long primera = canchaService
                .crearCancha(establecimiento.getId(), request("Cancha 1", List.of(60)), email).id();
        Long segunda = canchaService
                .crearCancha(establecimiento.getId(), request("Cancha 2", List.of(90)), email).id();

        canchaService.actualizarCancha(establecimiento.getId(), primera, request("Cancha 1", null), email);
        canchaService.actualizarCancha(establecimiento.getId(), segunda, request("Cancha 2", null), email);

        Map<String, List<Integer>> persistidas = duracionesPersistidas(establecimiento, email);
        assertThat(persistidas.get("Cancha 1")).containsExactlyElementsOf(DURACIONES_POR_DEFECTO_ESPERADAS);
        assertThat(persistidas.get("Cancha 2")).containsExactlyElementsOf(DURACIONES_POR_DEFECTO_ESPERADAS);
    }

    /**
     * Set.of(...) es lo que produce cualquier caller que no sea Jackson (otro servicio, un
     * job, un test): el servicio no puede depender de que la deserialización le entregue
     * siempre una colección mutable.
     */
    @Test
    @DisplayName("conDeportesInmutablesEnElRequest_ActualizaSinRomperEnElMerge")
    void conDeportesInmutablesEnElRequest_ActualizaSinRomperEnElMerge() {
        String email = "dueno-deportes@cancha-test.com";
        Establecimiento establecimiento = seedComplejo("complejo-deportes", email);
        Long canchaId = canchaService
                .crearCancha(establecimiento.getId(), request("Cancha 1", List.of(60)), email).id();

        CanchaResponse actualizada = canchaService.actualizarCancha(establecimiento.getId(), canchaId,
                new CanchaRequest("Cancha 1", Set.of(Deporte.PADEL), new BigDecimal("10000"),
                        null, new ArrayList<>(List.of(60)), null, true, null, null, null),
                email);

        assertThat(actualizada.deportes()).containsExactly(Deporte.PADEL);
    }

    /**
     * Se relee por el servicio y no por CanchaRepository: duracionesPermitidas es una
     * colección LAZY y este test no es transaccional, así que tocar la entidad directamente
     * desde acá tira LazyInitializationException. obtenerCanchasPorEstablecimiento la mapea
     * dentro de su propia transacción.
     */
    private Map<String, List<Integer>> duracionesPersistidas(Establecimiento establecimiento, String email) {
        return canchaService.obtenerCanchasPorEstablecimiento(establecimiento.getId(), email).stream()
                .collect(Collectors.toMap(CanchaResponse::nombre, CanchaResponse::duracionesPermitidas));
    }

    private CanchaRequest request(String nombre, List<Integer> duraciones) {
        return new CanchaRequest(nombre, new HashSet<>(Set.of(Deporte.FUTBOL_5)), new BigDecimal("10000"),
                null, duraciones == null ? null : new ArrayList<>(duraciones),
                null, true, null, null, null);
    }

    private Establecimiento seedComplejo(String slug, String email) {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Dueno Colecciones Test")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());

        return establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo Colecciones")
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
