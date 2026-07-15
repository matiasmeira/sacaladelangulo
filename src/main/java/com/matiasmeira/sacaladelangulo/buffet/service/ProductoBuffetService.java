package com.matiasmeira.sacaladelangulo.buffet.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.buffet.dto.AjustarStockRequest;
import com.matiasmeira.sacaladelangulo.buffet.dto.ProductoBuffetMapper;
import com.matiasmeira.sacaladelangulo.buffet.dto.ProductoBuffetRequest;
import com.matiasmeira.sacaladelangulo.buffet.dto.ProductoBuffetResponse;
import com.matiasmeira.sacaladelangulo.buffet.model.ProductoBuffet;
import com.matiasmeira.sacaladelangulo.buffet.repository.ProductoBuffetRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
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
    private final UsuarioRepository usuarioRepository;
    private final ProductoBuffetMapper productoBuffetMapper;

    public ProductoBuffetResponse crearProducto(Long establecimientoId, ProductoBuffetRequest request, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        validarPropietarioOAdmin(establecimiento, email);

        ProductoBuffet producto = ProductoBuffet.builder()
                .nombre(request.nombre())
                .descripcion(request.descripcion())
                .precio(request.precio())
                .stock(request.stock())
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
        ProductoBuffet producto = buscarProductoDelEstablecimiento(establecimientoId, productoId);
        validarPropietarioOAdmin(producto.getEstablecimiento(), email);

        producto.setNombre(request.nombre());
        producto.setDescripcion(request.descripcion());
        producto.setPrecio(request.precio());

        ProductoBuffet productoActualizado = productoBuffetRepository.save(producto);
        log.info("Producto de buffet actualizado. ID: {}", productoId);

        return productoBuffetMapper.mapToResponse(productoActualizado);
    }

    /**
     * Suma o resta stock según el signo de la cantidad. El stock resultante nunca
     * puede quedar por debajo de cero.
     */
    public ProductoBuffetResponse ajustarStock(Long establecimientoId, Long productoId, AjustarStockRequest request, String email) {
        ProductoBuffet producto = buscarProductoDelEstablecimiento(establecimientoId, productoId);
        validarPropietarioOAdmin(producto.getEstablecimiento(), email);

        // Lock pesimista antes de leer/escribir el stock: serializa contra cualquier otro
        // ajuste/venta/cancelación concurrente sobre el mismo producto (ver A5 en la auditoría).
        productoBuffetRepository.lockPorIds(List.of(productoId));

        int nuevoStock = producto.getStock() + request.cantidad();
        if (nuevoStock < 0) {
            log.warn("Ajuste de stock rechazado. Producto: {}, Stock actual: {}, Ajuste: {}",
                    productoId, producto.getStock(), request.cantidad());
            throw new IllegalArgumentException(
                    "El stock no puede quedar por debajo de 0 (stock actual: " + producto.getStock() +
                            ", ajuste solicitado: " + request.cantidad() + ")");
        }

        producto.setStock(nuevoStock);
        ProductoBuffet productoActualizado = productoBuffetRepository.save(producto);
        log.info("Stock ajustado. Producto: {}, Nuevo stock: {}", productoId, nuevoStock);

        return productoBuffetMapper.mapToResponse(productoActualizado);
    }

    @Transactional(readOnly = true)
    public List<ProductoBuffetResponse> listarPorEstablecimiento(Long establecimientoId, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        validarPropietarioOAdmin(establecimiento, email);

        return productoBuffetRepository.findByEstablecimientoId(establecimientoId).stream()
                .map(productoBuffetMapper::mapToResponse)
                .toList();
    }

    public void eliminarProducto(Long establecimientoId, Long productoId, String email) {
        ProductoBuffet producto = buscarProductoDelEstablecimiento(establecimientoId, productoId);
        validarPropietarioOAdmin(producto.getEstablecimiento(), email);

        productoBuffetRepository.delete(producto);
        log.info("Producto de buffet eliminado. ID: {}", productoId);
    }

    private Establecimiento buscarEstablecimientoPorId(Long establecimientoId) {
        return establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
    }

    private ProductoBuffet buscarProductoDelEstablecimiento(Long establecimientoId, Long productoId) {
        ProductoBuffet producto = productoBuffetRepository.findById(productoId)
                .orElseThrow(() -> new EntityNotFoundException("Producto no encontrado"));
        if (!producto.getEstablecimiento().getId().equals(establecimientoId)) {
            throw new IllegalArgumentException("El producto no pertenece a este establecimiento");
        }
        return producto;
    }

    private void validarPropietarioOAdmin(Establecimiento establecimiento, String email) {
        Usuario usuarioAutenticado = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));
        if (usuarioAutenticado.getRol() != Role.ADMIN &&
                !establecimiento.getDueno().getId().equals(usuarioAutenticado.getId())) {
            throw new AccessDeniedException("No autorizado en este establecimiento");
        }
    }
}
