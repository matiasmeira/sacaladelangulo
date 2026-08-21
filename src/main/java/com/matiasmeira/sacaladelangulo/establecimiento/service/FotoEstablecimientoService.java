package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.core.imagekit.FotoSubida;
import com.matiasmeira.sacaladelangulo.core.imagekit.ImageKitService;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.FotoEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.FotoEstablecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Gestión de las fotos del complejo (subir, borrar, reordenar) para el dueño o un admin.
 * Separado de EstablecimientoService a propósito: no comparte nada con la lógica de altas
 * y límites de plan de aquel.
 *
 * Criterio general ante fallos de ImageKit: nunca dejar al usuario trabado. Si el borrado
 * remoto falla, la foto se saca igual de la lista y el fileId queda logueado para limpieza
 * manual — es preferible un archivo huérfano en el CDN a una foto que el dueño no puede
 * sacar de su perfil público.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class FotoEstablecimientoService {

    /** Tope del detalle de auditoría: RegistroAuditoria.detalle es VARCHAR(500). */
    private static final int LARGO_MAXIMO_DETALLE = 500;

    private final EstablecimientoRepository establecimientoRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final ImageKitService imageKitService;
    private final ValidadorFoto validadorFoto;
    private final RegistroAuditoriaService registroAuditoriaService;

    @Transactional(readOnly = true)
    public List<FotoEstablecimientoResponse> listar(Long establecimientoId, String email) {
        Establecimiento establecimiento = buscarEstablecimiento(establecimientoId);
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);
        return mapear(establecimiento.getFotos());
    }

    public FotoEstablecimientoResponse subir(Long establecimientoId, byte[] contenido, String nombreArchivo, String email) {
        Establecimiento establecimiento = buscarEstablecimiento(establecimientoId);
        Usuario actor = autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        validadorFoto.validar(contenido, establecimiento.getFotos().size());

        FotoSubida subida = imageKitService.subir(contenido, nombreArchivo, carpetaDe(establecimientoId));
        registrarCompensacionSiFallaElCommit(subida.fileId());

        establecimiento.getFotos().add(FotoEstablecimiento.builder()
                .url(subida.url())
                .fileId(subida.fileId())
                .build());
        establecimientoRepository.save(establecimiento);

        auditar(actor, establecimiento, AccionAuditoria.SUBIR_FOTO_ESTABLECIMIENTO,
                "fileId=" + subida.fileId());

        return new FotoEstablecimientoResponse(subida.url(), subida.fileId());
    }

    public void borrar(Long establecimientoId, String fileId, String email) {
        Establecimiento establecimiento = buscarEstablecimiento(establecimientoId);
        Usuario actor = autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        FotoEstablecimiento foto = establecimiento.getFotos().stream()
                .filter(f -> fileId.equals(f.getFileId()))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Foto no encontrada en este establecimiento"));

        try {
            imageKitService.borrar(fileId);
        } catch (RuntimeException ex) {
            // Decisión explícita: se sigue adelante. Un fallo de ImageKit no puede dejar
            // al dueño con una foto que no puede sacar de su perfil público. El fileId
            // queda acá para poder limpiar el archivo huérfano después.
            log.error("No se pudo borrar el archivo {} en ImageKit; se saca igual de la lista del "
                    + "establecimiento {}. Queda huérfano y hay que limpiarlo a mano.",
                    fileId, establecimientoId, ex);
        }

        establecimiento.getFotos().remove(foto);
        establecimientoRepository.save(establecimiento);

        auditar(actor, establecimiento, AccionAuditoria.ELIMINAR_FOTO_ESTABLECIMIENTO, "fileId=" + fileId);
    }

    public List<FotoEstablecimientoResponse> reordenar(Long establecimientoId, List<String> fileIds, String email) {
        Establecimiento establecimiento = buscarEstablecimiento(establecimientoId);
        Usuario actor = autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        List<FotoEstablecimiento> actuales = establecimiento.getFotos();

        // Las fotos legacy (cargadas a mano antes de ImageKit) no tienen fileId, así que
        // no son direccionables: no hay forma de decir dónde va cada una en el orden
        // nuevo. Se rechaza entero en vez de reordenar a medias.
        if (actuales.stream().anyMatch(f -> f.getFileId() == null)) {
            throw new IllegalArgumentException(
                    "Este establecimiento tiene fotos cargadas manualmente, sin identificador. "
                            + "No se pueden reordenar por API.");
        }

        Set<String> pedidos = new LinkedHashSet<>(fileIds);
        Set<String> existentes = actuales.stream()
                .map(FotoEstablecimiento::getFileId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        // Un LinkedHashSet más chico que la lista significa que venían repetidos.
        if (pedidos.size() != fileIds.size() || !pedidos.equals(existentes)) {
            throw new IllegalArgumentException(
                    "La lista de fileIds tiene que contener exactamente las fotos actuales del "
                            + "establecimiento, una sola vez cada una.");
        }

        List<FotoEstablecimiento> reordenadas = new ArrayList<>(actuales.size());
        for (String fileId : fileIds) {
            reordenadas.add(actuales.stream()
                    .filter(f -> fileId.equals(f.getFileId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("fileId desconocido: " + fileId)));
        }

        // clear()+addAll() y no setFotos(...): Hibernate rastrea la instancia de la
        // colección de una @ElementCollection. Reemplazarla por otra lista provoca
        // "A collection with cascade=all-delete-orphan was no longer referenced".
        actuales.clear();
        actuales.addAll(reordenadas);
        establecimientoRepository.save(establecimiento);

        auditar(actor, establecimiento, AccionAuditoria.REORDENAR_FOTOS_ESTABLECIMIENTO,
                "principal=" + fileIds.get(0) + ", total=" + fileIds.size());

        return mapear(actuales);
    }

    /**
     * Si ImageKit ya aceptó el archivo y después el commit falla, el archivo queda pago y
     * sin referencia. Un try/catch acá adentro no lo cubre: el commit ocurre DESPUÉS de
     * que este método retorna, así que hay que engancharse al final de la transacción.
     */
    private void registrarCompensacionSiFallaElCommit(String fileId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;
                }
                try {
                    imageKitService.borrar(fileId);
                    log.warn("La transacción no commiteó tras subir {}: se borró el archivo en ImageKit.", fileId);
                } catch (RuntimeException ex) {
                    log.error("La transacción no commiteó tras subir {} y tampoco se pudo borrar el "
                            + "archivo en ImageKit. Queda huérfano y hay que limpiarlo a mano.", fileId, ex);
                }
            }
        });
    }

    private void auditar(Usuario actor, Establecimiento establecimiento, AccionAuditoria accion, String detalle) {
        registroAuditoriaService.registrarSobreEstablecimiento(
                actor, establecimiento, accion, establecimiento.getId(), truncar(detalle));
    }

    private String truncar(String detalle) {
        if (detalle == null || detalle.length() <= LARGO_MAXIMO_DETALLE) {
            return detalle;
        }
        return detalle.substring(0, LARGO_MAXIMO_DETALLE);
    }

    private String carpetaDe(Long establecimientoId) {
        return "/establecimientos/" + establecimientoId + "/";
    }

    private Establecimiento buscarEstablecimiento(Long establecimientoId) {
        return establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
    }

    private List<FotoEstablecimientoResponse> mapear(List<FotoEstablecimiento> fotos) {
        return fotos.stream()
                .filter(Objects::nonNull)
                .map(f -> new FotoEstablecimientoResponse(f.getUrl(), f.getFileId()))
                .toList();
    }
}
