package com.matiasmeira.sacaladelangulo.gastos.repository;

import com.matiasmeira.sacaladelangulo.gastos.model.CategoriaGasto;
import com.matiasmeira.sacaladelangulo.gastos.model.Gasto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface GastoRepository extends JpaRepository<Gasto, Long> {

    Optional<Gasto> findByIdAndEstablecimientoId(Long id, Long establecimientoId);

    // Las tres queries de abajo excluyen is_active=false (ver M-04 en la auditoría): un
    // gasto eliminado no debe aparecer en el listado ni contarse en los reportes, aunque
    // la fila siga existiendo para auditoría.

    // Los tres cast() evitan SQLState 42P18 ("no se pudo determinar el tipo del parámetro"):
    // pgjdbc no puede inferir el tipo de un parámetro cuya única aparición es un "? IS NULL"
    // sin contexto. Las comparaciones (>=, <=, =) no lo necesitan porque ya tienen tipo por
    // la columna del otro lado — no los saques pensando que sobran, sin ellos esta query
    // rompe en el 100% de las llamadas reales (desde/hasta/categoria siempre llegan null en
    // algún filtro; ver diagnóstico del 2026-09-24).
    @Query("SELECT g FROM Gasto g WHERE g.establecimiento.id = :establecimientoId " +
            "AND g.isActive = true " +
            "AND (cast(:desde as date) IS NULL OR g.fecha >= :desde) " +
            "AND (cast(:hasta as date) IS NULL OR g.fecha <= :hasta) " +
            "AND (cast(:categoria as string) IS NULL OR g.categoria = :categoria) " +
            "ORDER BY g.fecha DESC")
    Page<Gasto> buscar(@Param("establecimientoId") Long establecimientoId,
                        @Param("desde") LocalDate desde,
                        @Param("hasta") LocalDate hasta,
                        @Param("categoria") CategoriaGasto categoria,
                        Pageable pageable);

    @Query("SELECT g.categoria, SUM(g.monto), COUNT(g) FROM Gasto g " +
            "WHERE g.establecimiento.id = :establecimientoId AND g.isActive = true AND g.fecha BETWEEN :desde AND :hasta " +
            "GROUP BY g.categoria")
    List<Object[]> sumGastoPorCategoria(@Param("establecimientoId") Long establecimientoId,
                                         @Param("desde") LocalDate desde,
                                         @Param("hasta") LocalDate hasta);

    @Query("SELECT g.fecha, g.monto FROM Gasto g " +
            "WHERE g.establecimiento.id = :establecimientoId AND g.isActive = true AND g.fecha BETWEEN :desde AND :hasta")
    List<Object[]> findFechaYMontoParaSerieTemporal(@Param("establecimientoId") Long establecimientoId,
                                                      @Param("desde") LocalDate desde,
                                                      @Param("hasta") LocalDate hasta);
}
