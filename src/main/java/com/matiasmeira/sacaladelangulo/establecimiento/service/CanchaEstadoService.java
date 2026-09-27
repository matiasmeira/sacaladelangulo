package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.CambiarEstadoCanchaRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CambiarEstadoCanchaResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Orquestador de PATCH /estado para canchas: reemplaza al DELETE que en realidad hacía un
 * toggle reversible (ver javadoc de CanchaController.desactivarCancha). No reimplementa ningún
 * guard de negocio -- desactivarCancha y reactivarCancha, en CanchaService, siguen siendo los
 * únicos lugares donde corren validarDesactivacion y validarConfiguracionDePool
 * respectivamente; este servicio sólo elige cuál de los dos llamar y arma la respuesta.
 *
 * <p>Con esto quedan DOS caminos para tocar isActive de una cancha: éste (PATCH /estado) y el
 * campo isActive (nullable) de CanchaRequest en el PUT general de actualizarCancha. En
 * establecimientos no pasa -- su PUT no tiene un campo de estado -- porque nació después de
 * este endpoint viejo. Si algún día se unifica, éste (el que expone el contrato correcto, sin
 * necesitar el resto de los campos de la cancha) es el que debería quedar; no se resuelve acá
 * porque el PUT general lo sigue usando el frontend.
 */
@Service
@RequiredArgsConstructor
public class CanchaEstadoService {

    private final CanchaService canchaService;

    public CambiarEstadoCanchaResponse cambiarEstado(Long establecimientoId, Long canchaId,
                                                       CambiarEstadoCanchaRequest request, String email) {
        Cancha cancha = Boolean.TRUE.equals(request.activo())
                ? canchaService.reactivarCancha(establecimientoId, canchaId, email)
                : canchaService.desactivarCancha(establecimientoId, canchaId, email);
        return new CambiarEstadoCanchaResponse(cancha.getId(), cancha.getIsActive());
    }
}
