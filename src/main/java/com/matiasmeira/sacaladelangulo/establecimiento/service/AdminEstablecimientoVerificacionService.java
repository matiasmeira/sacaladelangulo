package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.AdminEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.publico.service.ComplejoDetalleCache;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Resuelve la verificación manual de un establecimiento (EN_REVISION -> VERIFICADO o
 * RECHAZADO) del lado del admin. Separado de EstablecimientoService (que es la cara del
 * dueño: alta/edición de su propio establecimiento) porque este es un flujo de un actor
 * completamente distinto, con su propio controller y autorización -- mismo criterio que
 * PoliticaCancelacionService/FotoEstablecimientoService están separados de él.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AdminEstablecimientoVerificacionService {

    private final EstablecimientoRepository establecimientoRepository;
    private final UsuarioRepository usuarioRepository;
    private final RegistroAuditoriaService registroAuditoriaService;
    private final ComplejoDetalleCache complejoDetalleCache;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public Page<AdminEstablecimientoResponse> listar(EstadoVerificacion filtro, Pageable pageable) {
        Page<Establecimiento> pagina = filtro == null
                ? establecimientoRepository.findAllBy(pageable)
                : establecimientoRepository.findByEstadoVerificacion(filtro, pageable);
        return pagina.map(this::mapResponse);
    }

    public void verificar(Long establecimientoId, String emailAdmin) {
        Establecimiento establecimiento = buscarPorId(establecimientoId);
        Usuario admin = buscarPorEmail(emailAdmin);

        validarTransicionDesdeEnRevision(establecimiento);

        establecimiento.setEstadoVerificacion(EstadoVerificacion.VERIFICADO);
        establecimiento.setFechaVerificacion(LocalDateTime.now());
        establecimiento.setVerificadoPor(admin);
        establecimiento.setMotivoRechazo(null);
        establecimientoRepository.save(establecimiento);

        iniciarPruebaAlVerificar(establecimiento.getDueno());

        registroAuditoriaService.registrarSobreEstablecimiento(admin, establecimiento,
                AccionAuditoria.VERIFICAR_ESTABLECIMIENTO, establecimiento.getId(),
                "Establecimiento verificado: " + establecimiento.getNombre());

        // En la misma transacción que el UPDATE de arriba: ComplejoDetalleCache registra el
        // desalojo real para después del commit (ver su javadoc), así que esta llamada solo
        // encola el callback -- no hay ninguna ventana en la que el visitante siga viendo
        // 404 después de que la verificación ya es visible.
        complejoDetalleCache.invalidarPorEstablecimientoId(establecimiento.getId());

        eventPublisher.publishEvent(
                new EstablecimientoVerificadoEvent(establecimiento.getDueno().getId(), establecimiento.getNombre()));
    }

    public void rechazar(Long establecimientoId, String motivo, String emailAdmin) {
        Establecimiento establecimiento = buscarPorId(establecimientoId);
        Usuario admin = buscarPorEmail(emailAdmin);

        validarTransicionDesdeEnRevision(establecimiento);

        establecimiento.setEstadoVerificacion(EstadoVerificacion.RECHAZADO);
        establecimiento.setMotivoRechazo(motivo);
        establecimientoRepository.save(establecimiento);

        registroAuditoriaService.registrarSobreEstablecimiento(admin, establecimiento,
                AccionAuditoria.RECHAZAR_ESTABLECIMIENTO, establecimiento.getId(),
                "Establecimiento rechazado. Motivo: " + motivo);

        eventPublisher.publishEvent(
                new EstablecimientoRechazadoEvent(establecimiento.getDueno().getId(), establecimiento.getNombre(), motivo));
    }

    /**
     * Arranca la prueba gratuita del dueño en el momento en que se VERIFICA un
     * establecimiento, no cuando se registra (ver AuthService.registerOwner): un dueño puede
     * tardar en juntar los datos de verificación o esperar a que un admin lo revise, y ese
     * tiempo no debería descontarse de su mes de prueba.
     *
     * <p>Es idempotente a nivel de DUEÑO, no de establecimiento: si ya tiene
     * fechaFinPrueba (por ejemplo, está verificando un segundo complejo), no se toca -- un
     * segundo establecimiento no le regala un mes adicional ni le reinicia el que ya está
     * corriendo. Tampoco se toca si el plan ya no es TRIAL (ya lo degradaron a FREE o pasó a
     * PREMIUM): esto es exclusivamente el arranque del período de prueba, no una gracia
     * general para cualquier estado de plan.
     */
    private void iniciarPruebaAlVerificar(Usuario dueno) {
        if (dueno.getFechaFinPrueba() != null) {
            return;
        }
        if (dueno.getPlanSuscripcion() != PlanSuscripcion.TRIAL) {
            return;
        }
        dueno.setFechaFinPrueba(LocalDateTime.now().plusMonths(1));
        usuarioRepository.save(dueno);
    }

    private void validarTransicionDesdeEnRevision(Establecimiento establecimiento) {
        if (establecimiento.getEstadoVerificacion() != EstadoVerificacion.EN_REVISION) {
            throw new IllegalArgumentException(
                    "Solo se puede resolver la verificación de un establecimiento EN_REVISION (estado actual: "
                            + establecimiento.getEstadoVerificacion() + ")");
        }
    }

    private Establecimiento buscarPorId(Long id) {
        return establecimientoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
    }

    private Usuario buscarPorEmail(String email) {
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));
    }

    private AdminEstablecimientoResponse mapResponse(Establecimiento establecimiento) {
        Usuario dueno = establecimiento.getDueno();
        return new AdminEstablecimientoResponse(
                establecimiento.getId(),
                establecimiento.getNombre(),
                establecimiento.getDireccion(),
                establecimiento.getLatitud(),
                establecimiento.getLongitud(),
                establecimiento.getEstadoVerificacion(),
                establecimiento.getCuit(),
                establecimiento.getRazonSocial(),
                establecimiento.getTelefonoContacto(),
                establecimiento.getUrlRedSocial(),
                establecimiento.getFechaSolicitudVerificacion(),
                establecimiento.getFechaVerificacion(),
                establecimiento.getMotivoRechazo(),
                dueno.getId(),
                dueno.getNombre(),
                dueno.getEmail());
    }
}
