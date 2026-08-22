package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.FotoEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.FotoEstablecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reordenamiento de fotos contra un Postgres real con las migraciones de Flyway aplicadas.
 * Es el único lugar donde este caso se puede probar: la suite normal corre sobre H2 con
 * spring.flyway.enabled=false y ddl-auto=create-drop, así que el esquema sale de las
 * anotaciones y la unicidad de (establecimiento_id, file_id) que agrega V18 directamente no
 * existe ahí.
 *
 * <p>Lo que cubre: V18 declaraba esa unicidad con CREATE UNIQUE INDEX, que Postgres evalúa
 * sentencia por sentencia. Establecimiento.fotos es una @ElementCollection con @OrderColumn y
 * Hibernate resuelve una permutación con un UPDATE por índice (SET file_id=?, foto_url=?
 * WHERE establecimiento_id=? AND orden=?), no borrando e insertando la lista entera. Al dar
 * vuelta [a, b], el primer UPDATE deja file_id='b' en orden=0 y en orden=1 a la vez, y el
 * índice lo rechazaba en el acto: el caso más básico de PUT /establecimientos/{id}/fotos/orden
 * fallaba siempre en producción. Con el constraint DEFERRABLE INITIALLY DEFERRED el chequeo
 * corre en el COMMIT y ese estado intermedio es legal.
 *
 * <p>Se pasa por el servicio a propósito, no por el repositorio: el clear()+addAll() de
 * reordenar es lo que produce ese flush, y escribir la lista de otra forma no reproduce el
 * bug.
 *
 * <p>Requiere Docker. Ver AbstractPostgresIntegrationTest.
 */
@Tag("testcontainers")
@DisplayName("FotoEstablecimientoService.reordenar - Postgres real con Flyway (Testcontainers)")
class FotoEstablecimientoReordenIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String FILE_ID_A = "file_a";
    private static final String FILE_ID_B = "file_b";
    private static final String URL_A = "https://ik.imagekit.io/test/a.jpg";
    private static final String URL_B = "https://ik.imagekit.io/test/b.jpg";

    @Autowired
    private FotoEstablecimientoService fotoEstablecimientoService;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private EstablecimientoRepository establecimientoRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Usuario dueno;
    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno-fotos-" + System.nanoTime() + "@test.com")
                .password("hash")
                .nombre("Dueño Fotos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .unsubscribeToken("tok-" + System.nanoTime())
                .build());

        establecimiento = establecimientoRepository.save(Establecimiento.builder()
                .nombre("Club Fotos")
                .direccion("Calle Falsa 789")
                .slug("club-fotos")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .fotos(new ArrayList<>(List.of(
                        FotoEstablecimiento.builder().url(URL_A).fileId(FILE_ID_A).build(),
                        FotoEstablecimiento.builder().url(URL_B).fileId(FILE_ID_B).build())))
                .build());
    }

    @Test
    @DisplayName("reordenarDosFotosIntercambiandolas_commiteaYPersisteElOrdenNuevo")
    void reordenarDosFotosIntercambiandolas_commiteaYPersisteElOrdenNuevo() {
        List<FotoEstablecimientoResponse> respuesta = fotoEstablecimientoService.reordenar(
                establecimiento.getId(), List.of(FILE_ID_B, FILE_ID_A), dueno.getEmail());

        assertThat(respuesta).extracting(FotoEstablecimientoResponse::fileId)
                .containsExactly(FILE_ID_B, FILE_ID_A);
        assertThat(respuesta).extracting(FotoEstablecimientoResponse::url)
                .containsExactly(URL_B, URL_A);

        // El servicio ya commiteó (esta jerarquía de tests no es @Transactional), así que se
        // lee la tabla directo: si el chequeo de unicidad hubiera abortado la transacción, el
        // orden persistido seguiría siendo el original.
        assertThat(fileIdsPersistidosEnOrden()).containsExactly(FILE_ID_B, FILE_ID_A);
        assertThat(urlsPersistidasEnOrden()).containsExactly(URL_B, URL_A);
    }

    @Test
    @DisplayName("dosFotosConElMismoFileIdEnUnEstablecimiento_LaBaseLasSigueRechazando")
    void dosFotosConElMismoFileIdEnUnEstablecimiento_LaBaseLasSigueRechazando() {
        // Diferido no es apagado: un fileId repetido de verdad (que dejaría el borrado y el
        // reordenamiento ambiguos) tiene que seguir abortando la transacción. Se escribe
        // directo contra la tabla porque reordenar/subir nunca generan este estado.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO establecimiento_fotos (establecimiento_id, orden, foto_url, file_id) "
                        + "VALUES (?, ?, ?, ?)",
                establecimiento.getId(), 2, "https://ik.imagekit.io/test/repetida.jpg", FILE_ID_A))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(fileIdsPersistidosEnOrden()).containsExactly(FILE_ID_A, FILE_ID_B);
    }

    private List<String> fileIdsPersistidosEnOrden() {
        return jdbcTemplate.queryForList(
                "SELECT file_id FROM establecimiento_fotos WHERE establecimiento_id = ? ORDER BY orden",
                String.class, establecimiento.getId());
    }

    private List<String> urlsPersistidasEnOrden() {
        return jdbcTemplate.queryForList(
                "SELECT foto_url FROM establecimiento_fotos WHERE establecimiento_id = ? ORDER BY orden",
                String.class, establecimiento.getId());
    }
}
