package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CanchaRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CanchaResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Servicio de negocio para canchas.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CanchaService {

    private final CanchaRepository canchaRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final UsuarioRepository usuarioRepository;

    public CanchaResponse crearCancha(Long establecimientoId, CanchaRequest request, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new IllegalArgumentException("Establecimiento no encontrado"));

        Usuario dueno = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));

        if (!establecimiento.getDueno().getId().equals(dueno.getId())) {
            throw new IllegalArgumentException("No autorizado para crear canchas en este establecimiento");
        }

        // Validar y procesar el campo canchasNecesarias
        Integer canchasNecesarias = null;
        if (request.canchasFisicasIds() != null && !request.canchasFisicasIds().isEmpty()) {
            Integer cantidadSolicitada = request.cantidadCanchasNecesarias();
            int totalCanchasSeleccionadas = request.canchasFisicasIds().size();

            if (cantidadSolicitada == null || cantidadSolicitada < 1) {
                // Asignar por defecto el tamaño de la lista de IDs
                canchasNecesarias = totalCanchasSeleccionadas;
            } else if (cantidadSolicitada > totalCanchasSeleccionadas) {
                throw new IllegalArgumentException("Las canchas necesarias no pueden superar el total de canchas seleccionadas");
            } else {
                canchasNecesarias = cantidadSolicitada;
            }
        }

        Cancha cancha = Cancha.builder()
                .nombre(request.nombre())
                .deporte(request.deporte())
                .capacidad(request.capacidad())
                .isActive(true)
                .establecimiento(establecimiento)
                .canchasNecesarias(canchasNecesarias)
                .build();

        if (request.canchasFisicasIds() != null && !request.canchasFisicasIds().isEmpty()) {
            List<Cancha> canchasFisicas = new ArrayList<>();
            canchaRepository.findAllById(request.canchasFisicasIds()).forEach(canchasFisicas::add);

            if (canchasFisicas.size() != request.canchasFisicasIds().size()) {
                throw new IllegalArgumentException("Algunas canchas físicas no existen");
            }

            cancha.setCanchasFisicas(canchasFisicas);
        }

        Cancha canchaGuardada = canchaRepository.save(cancha);

        return new CanchaResponse(
                canchaGuardada.getId(),
                canchaGuardada.getNombre(),
                canchaGuardada.getDeporte(),
                canchaGuardada.getCapacidad(),
                canchaGuardada.getIsActive(),
                canchaGuardada.getEstablecimiento().getId(),
                canchaGuardada.getCanchasFisicas().stream().map(Cancha::getId).toList(),
                canchaGuardada.getCanchasNecesarias()
        );
    }
}
