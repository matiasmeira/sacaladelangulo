package com.matiasmeira.sacaladelangulo.support;

import com.matiasmeira.sacaladelangulo.buffet.model.ProductoBuffet;
import com.matiasmeira.sacaladelangulo.buffet.repository.ProductoBuffetRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

/**
 * Capa sobre {@link AbstractSecurityWebTest} para el buffet: siembra un producto en el complejo A y otro
 * en el B (stock 10 y precio 100 en ambos), para aseverar tanto que el propio sobrevive a un rechazo como
 * que no se toca ni se lista el del otro complejo.
 */
public abstract class AbstractBuffetSecurityTest extends AbstractSecurityWebTest {

    /** Mensaje de validarPropietarioOAdmin (AutorizacionEmpleadoService:135). */
    protected static final String MENSAJE_403_SERVICE = "No autorizado en este establecimiento";

    @Autowired
    protected ProductoBuffetRepository productoBuffetRepository;

    protected ProductoBuffet productoA;
    protected ProductoBuffet productoB;

    @BeforeEach
    void sembrarProductos() {
        productoA = producto(establecimientoA, "Agua A");
        productoB = producto(establecimientoB, "Agua B");
    }

    private ProductoBuffet producto(Establecimiento establecimiento, String nombre) {
        return productoBuffetRepository.save(ProductoBuffet.builder()
                .nombre(nombre).precio(new BigDecimal("100")).stock(10).establecimiento(establecimiento).build());
    }

    protected ProductoBuffet recargar(ProductoBuffet p) {
        return productoBuffetRepository.findById(p.getId()).orElseThrow();
    }
}
