package com.matiasmeira.sacaladelangulo.buffet.service;

import com.matiasmeira.sacaladelangulo.buffet.dto.AjustarStockRequest;
import com.matiasmeira.sacaladelangulo.buffet.dto.ProductoBuffetMapper;
import com.matiasmeira.sacaladelangulo.buffet.dto.ProductoBuffetRequest;
import com.matiasmeira.sacaladelangulo.buffet.dto.ProductoBuffetResponse;
import com.matiasmeira.sacaladelangulo.buffet.model.ProductoBuffet;
import com.matiasmeira.sacaladelangulo.buffet.repository.ProductoBuffetRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.service.EstablecimientoAutorizado;
import com.matiasmeira.sacaladelangulo.establecimiento.service.EstablecimientoOperativoGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Servicio de negocio para el inventario de buffet de un establecimiento.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ProductoBuffetService {

    private final ProductoBuffetRepository productoBuffetRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final EstablecimientoOperativoGuard establecimientoOperativoGuard;
    private final ProductoBuffetMapper productoBuffetMapper;

    public ProductoBuffetResponse crearProducto(Long establecimientoId, ProductoBuffetRequest request, String email) {
        Establecimiento establecimiento = autorizarPropietarioOAdmin(establecimientoId, email);
        establecimientoOperativoGuard.validarPuedeGenerarCompromisosNuevos(establecimiento);

        ProductoBuffet producto = ProductoBuffet.builder()
                .nombre(request.nombre())
                .descripcion(request.descripcion())
                .precio(request.precio())
                .stock(request.stock())
                // Opcional: si no viene, el @Builder.Default de la entidad deja 5.
                .umbralAlerta(request.umbralAlerta() == null ? 5 : request.umbralAlerta())
                .establecimiento(establecimiento)
                .build();

        ProductoBuffet productoGuardado = productoBuffetRepository.save(producto);
        log.info("Producto de buffet creado. ID: {}, Establecimiento: {}", productoGuardado.getId(), establecimientoId);

        return productoBuffetMapper.mapToResponse(productoGuardado);
    }

    /**
     * Actualiza nombre, descripción y precio. El stock no se modifica acá: se maneja
     * exclusivamente a través de {@link #ajustarStock}.
     */
    public ProductoBuffetResponse actualizarProducto(Long establecimientoId, Long productoId, ProductoBuffetRequest request, String email) {
        autorizarPropietarioOAdmin(establecimientoId, email);
        ProductoBuffet producto = buscarProductoDelEstablecimiento(establecimientoId, productoId);

        producto.setNombre(request.nombre());
        producto.setDescripcion(request.descripcion());
        producto.setPrecio(request.precio());
        if (request.umbralAlerta() != null) {
            producto.setUmbralAlerta(request.umbralAlerta());
        }

        ProductoBuffet productoActualizado = productoBuffetRepository.save(producto);
        log.info("Producto de buffet actualizado. ID: {}", productoId);

        return productoBuffetMapper.mapToResponse(productoActualizado);
    }

    /**
     * Suma o resta stock según el signo de la cantidad. El resultado PUEDE quedar
     * por debajo de cero: el stock es informativo, no una condición para vender.
     */
    public ProductoBuffetResponse ajustarStock(Long establecimientoId, Long productoId, AjustarStockRequest request, String email) {
        autorizarPropietarioOAdmin(establecimientoId, email);
        ProductoBuffet producto = buscarProductoDelEstablecimiento(establecimientoId, productoId);

        // Lock pesimista antes de leer/escribir el stock: serializa contra cualquier otro
        // ajuste/venta/cancelación concurrente sobre el mismo producto.
        productoBuffetRepository.lockPorIds(List.of(productoId));

        int nuevoStock = producto.getStock() + request.cantidad();
        if (nuevoStock < 0) {
            // Se permite, no se rechaza: el stock es informativo y puede estar
            // desfasado de la realidad del mostrador. Bloquear el ajuste dejaría
            // sin forma de corregir un producto que ya quedó en negativo por una
            // venta (ver V16). Queda el warn para poder detectarlo.
            log.warn("Ajuste deja el stock en negativo. Producto: {}, Stock actual: {}, Ajuste: {}",
                    productoId, producto.getStock(), request.cantidad());
        }

        producto.setStock(nuevoStock);
        ProductoBuffet productoActualizado = productoBuffetRepository.save(producto);
        log.info("Stock ajustado. Producto: {}, Nuevo stock: {}", productoId, nuevoStock);

        return productoBuffetMapper.mapToResponse(productoActualizado);
    }

    @Transactional(readOnly = true)
    public List<ProductoBuffetResponse> listarPorEstablecimiento(Long establecimientoId, String email) {
        // El que puede vender puede ver qué vender. Las mutaciones de arriba siguen
        // con validarPropietarioOAdmin.
        EstablecimientoAutorizado.autorizar(establecimientoRepository.findById(establecimientoId),
                establecimiento -> autorizacionEmpleadoService.validarAccion(
                        establecimiento, email, PermisoEmpleado.REGISTRAR_VENTA_BUFFET));

        return productoBuffetRepository.findByEstablecimientoId(establecimientoId).stream()
                .map(productoBuffetMapper::mapToResponse)
                .toList();
    }

    public void eliminarProducto(Long establecimientoId, Long productoId, String email) {
        autorizarPropietarioOAdmin(establecimientoId, email);
        ProductoBuffet producto = buscarProductoDelEstablecimiento(establecimientoId, productoId);

        productoBuffetRepository.delete(producto);
        log.info("Producto de buffet eliminado. ID: {}", productoId);
    }

    /**
     * Autoriza contra el establecimiento del path antes de buscar o validar nada: uno inexistente responde
     * igual que uno ajeno (ver EstablecimientoAutorizado).
     */
    private Establecimiento autorizarPropietarioOAdmin(Long establecimientoId, String email) {
        return EstablecimientoAutorizado.autorizar(establecimientoRepository.findById(establecimientoId),
                establecimiento -> autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email));
    }

    /** Acotado al establecimiento ya autorizado: inexistente o de otro complejo, el mismo 404. */
    private ProductoBuffet buscarProductoDelEstablecimiento(Long establecimientoId, Long productoId) {
        return productoBuffetRepository.findById(productoId)
                .filter(producto -> producto.getEstablecimiento().getId().equals(establecimientoId))
                .orElseThrow(() -> new EntityNotFoundException("Producto no encontrado"));
    }

}
