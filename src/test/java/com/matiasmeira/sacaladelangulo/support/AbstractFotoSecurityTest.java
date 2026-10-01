package com.matiasmeira.sacaladelangulo.support;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.FotoEstablecimiento;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Capa sobre {@link AbstractSecurityWebTest} para los endpoints de fotos del establecimiento: siembra
 * dos fotos en el complejo A (file_a1, file_a2) y una en el B (file_b1), y lee los fileId persistidos
 * (la colección es lazy: se lee dentro de una transacción de prueba). No mockea ImageKit: los tests
 * de esta capa sólo ejercen los caminos que cortan ANTES de llamarlo (rol, dueño ajeno, foto que no
 * es del complejo) o que no lo usan (listar, reordenar).
 */
public abstract class AbstractFotoSecurityTest extends AbstractSecurityWebTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void sembrarFotos() {
        establecimientoA.setFotos(new ArrayList<>(List.of(foto("a1"), foto("a2"))));
        establecimientoA = establecimientoRepository.save(establecimientoA);
        establecimientoB.setFotos(new ArrayList<>(List.of(foto("b1"))));
        establecimientoB = establecimientoRepository.save(establecimientoB);
    }

    private static FotoEstablecimiento foto(String sufijo) {
        return FotoEstablecimiento.builder()
                .url("https://ik.imagekit.io/test/" + sufijo + ".jpg")
                .fileId("file_" + sufijo)
                .build();
    }

    /** fileIds persistidos del establecimiento, en orden. */
    protected List<String> fileIdsDe(Establecimiento establecimiento) {
        return new TransactionTemplate(transactionManager).execute(status ->
                establecimientoRepository.findById(establecimiento.getId()).orElseThrow()
                        .getFotos().stream().map(FotoEstablecimiento::getFileId).toList());
    }

    protected void assertFotosIntactas() {
        org.junit.jupiter.api.Assertions.assertEquals(List.of("file_a1", "file_a2"), fileIdsDe(establecimientoA));
        org.junit.jupiter.api.Assertions.assertEquals(List.of("file_b1"), fileIdsDe(establecimientoB));
    }
}
