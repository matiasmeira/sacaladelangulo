package com.matiasmeira.sacaladelangulo.core.email.reintento;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmailPendienteRepository extends JpaRepository<EmailPendiente, Long> {

    Optional<EmailPendiente> findByDestinatarioAndAsunto(String destinatario, String asunto);

    void deleteByDestinatarioAndAsunto(String destinatario, String asunto);

    /**
     * Lote a reintentar, más viejo primero. Paginado a propósito: si el proveedor estuvo
     * caído un rato la cola puede tener miles de filas, y una corrida que intente
     * mandarlas todas de una bloquearía el hilo del scheduler y castigaría la cuota del
     * proveedor. Se drena de a tandas, una por corrida.
     */
    @Query("SELECT e FROM EmailPendiente e WHERE e.estado = 'PENDIENTE' ORDER BY e.fechaCreacion ASC")
    List<EmailPendiente> findLoteAReintentar(Pageable pageable);

    long countByEstado(EstadoEmailPendiente estado);
}
