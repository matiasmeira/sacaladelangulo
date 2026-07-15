package com.matiasmeira.sacaladelangulo.buffet.repository;

import com.matiasmeira.sacaladelangulo.buffet.model.ProductoBuffet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ProductoBuffetRepository extends JpaRepository<ProductoBuffet, Long> {

    List<ProductoBuffet> findByEstablecimientoId(Long establecimientoId);

    /**
     * Adquiere un lock pesimista (SELECT ... FOR UPDATE) sobre los productos indicados,
     * en el orden ascendente de ID de la propia query. Se usa antes de leer/modificar
     * stock (alta/ajuste/venta/cancelación), para serializar entre sí a las transacciones
     * concurrentes que compiten por el mismo producto y evitar que se pierda una
     * actualización de stock (lost update). Todos los llamadores deben pasar los IDs ya
     * ordenados ascendentemente para que el orden de adquisición sea siempre el mismo y
     * no se produzcan deadlocks entre transacciones que bloquean el mismo conjunto de
     * productos (ej. dos ventas con distintos ítems del mismo carrito, en distinto orden).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ProductoBuffet p WHERE p.id IN :ids ORDER BY p.id ASC")
    List<ProductoBuffet> lockPorIds(@Param("ids") Collection<Long> ids);
}
