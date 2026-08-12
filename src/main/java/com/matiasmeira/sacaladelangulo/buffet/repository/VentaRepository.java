package com.matiasmeira.sacaladelangulo.buffet.repository;

import com.matiasmeira.sacaladelangulo.buffet.model.EstadoVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.Venta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VentaRepository extends JpaRepository<Venta, Long> {

    /**
     * Trae también detalles y su producto de buffet en la misma consulta (fetch join +
     * DISTINCT para no duplicar la Venta por cada detalle), para que
     * VentaService.obtenerMetricas/calcularProductosMasVendidos no dispare una query por
     * cada línea de cada venta al iterarlas (N+1 con relaciones LAZY). Toda Venta tiene al
     * menos un detalle (VentaRequest.detalles es @NotEmpty), así que el JOIN interno no
     * pierde ninguna venta real.
     */
    @Query("SELECT DISTINCT v FROM Venta v JOIN FETCH v.detalles d JOIN FETCH d.productoBuffet " +
            "WHERE v.establecimiento.id = :establecimientoId AND v.estado = :estado AND v.fechaHora BETWEEN :desde AND :hasta")
    List<Venta> findByEstablecimientoIdAndEstadoAndFechaHoraBetween(
            @Param("establecimientoId") Long establecimientoId, @Param("estado") EstadoVenta estado,
            @Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    /**
     * Misma razón que la query anterior, para el path de cancelarVenta: trae la venta con
     * sus detalles y el producto de cada uno ya cargados, en vez de disparar una query
     * lazy por cada ítem del carrito al devolver stock.
     */
    @Query("SELECT v FROM Venta v JOIN FETCH v.detalles d JOIN FETCH d.productoBuffet WHERE v.id = :id")
    Optional<Venta> findByIdConDetalles(@Param("id") Long id);

    /**
     * Listado paginado de ventas para GET /api/v1/buffet/ventas. A diferencia de
     * findByEstablecimientoIdAndEstadoAndFechaHoraBetween, estado es opcional (sin
     * filtro trae CONFIRMADA y CANCELADA) y no hace JOIN FETCH de detalles: la
     * respuesta de ese endpoint es un resumen sin el desglose de ítems, así que no
     * hace falta traerlos y se evita la trampa de combinar fetch join de una
     * colección con Pageable (Hibernate paginaría en memoria, warning HHH90003004).
     */
    @Query("SELECT v FROM Venta v WHERE v.establecimiento.id = :establecimientoId " +
            "AND (:estado IS NULL OR v.estado = :estado) " +
            "AND v.fechaHora BETWEEN :desde AND :hasta")
    Page<Venta> buscarPaginado(@Param("establecimientoId") Long establecimientoId,
                                @Param("estado") EstadoVenta estado,
                                @Param("desde") LocalDateTime desde,
                                @Param("hasta") LocalDateTime hasta,
                                Pageable pageable);
}
