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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 *
 * <p><b>Por qué las fronteras transaccionales son programáticas acá y no {@code @Transactional}
 * de clase.</b> {@code subir()} y {@code borrar()} hacen una llamada HTTP sincrónica a
 * ImageKit en el medio de su trabajo. Con {@code @Transactional} a nivel de clase (como tenía
 * este servicio antes), esa llamada ocurre con una conexión de Hikari retenida durante todo
 * el round-trip: el pool de producción tiene {@code maximum-pool-size=5} a propósito (tier
 * chico de Postgres administrado), así que cinco subidas concurrentes — o simplemente
 * ImageKit lento — vacían el pool y cualquier otro request que toque la base (reservas,
 * login, ventas de buffet) encola hasta el timeout y falla. Por eso {@code subir()} y
 * {@code borrar()} parten el trabajo en tres fases explícitas con {@link TransactionTemplate}:
 * una transacción corta de lectura, la llamada a ImageKit SIN transacción abierta, y una
 * transacción corta de escritura. {@code TransactionTemplate} se eligió sobre extraer un bean
 * colaborador (para sortear que Spring no aplica {@code @Transactional} en self-invocation)
 * porque deja las fronteras explícitas en el mismo método, al lado de este comentario, sin
 * agregar una clase cuya única razón de ser sea esquivar el proxy de Spring.
 *
 * <p>Si a alguien le tienta "simplificar" esto volviendo a {@code @Transactional} de clase:
 * no, es exactamente el bug que este diseño corrige. {@code listar()} y {@code reordenar()}
 * sí pueden quedarse con {@code @Transactional} de método porque no llaman a ImageKit.
 */
@Service
@Slf4j
public class FotoEstablecimientoService {

    /** Tope del detalle de auditoría: RegistroAuditoria.detalle es VARCHAR(500). */
    private static final int LARGO_MAXIMO_DETALLE = 500;

    private final EstablecimientoRepository establecimientoRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final ImageKitService imageKitService;
    private final ValidadorFoto validadorFoto;
    private final RegistroAuditoriaService registroAuditoriaService;

    /** {@code null} sólo con el constructor histórico de abajo; ver su javadoc. */
    private final TransactionTemplate transactionTemplateLectura;
    private final TransactionTemplate transactionTemplateEscritura;

    /**
     * Constructor histórico, sin {@link PlatformTransactionManager}. Lo sigue usando
     * {@code FotoEstablecimientoServiceTest}, que arma el servicio a mano con {@code new}
     * (Mockito puro, fuera del contenedor de Spring) — así probó este servicio desde el
     * principio, sin depender de un contexto de Spring para tests que no ejercitan
     * transacciones reales. Sin un {@link PlatformTransactionManager} no hay forma de armar
     * un {@link TransactionTemplate} que funcione de verdad, así que con este constructor
     * las tres fases de {@code subir()}/{@code borrar()} corren en línea, sin ninguna
     * transacción propia: no hace falta, porque Mockito no tiene ninguna conexión real que
     * retener. Spring nunca usa este constructor — con dos constructores en la clase, hace
     * falta marcar uno con {@code @Autowired} para que no sea ambiguo.
     */
    public FotoEstablecimientoService(EstablecimientoRepository establecimientoRepository,
                                       AutorizacionEmpleadoService autorizacionEmpleadoService,
                                       ImageKitService imageKitService,
                                       ValidadorFoto validadorFoto,
                                       RegistroAuditoriaService registroAuditoriaService) {
        this(establecimientoRepository, autorizacionEmpleadoService, imageKitService, validadorFoto,
                registroAuditoriaService, null);
    }

    @Autowired
    public FotoEstablecimientoService(EstablecimientoRepository establecimientoRepository,
                                       AutorizacionEmpleadoService autorizacionEmpleadoService,
                                       ImageKitService imageKitService,
                                       ValidadorFoto validadorFoto,
                                       RegistroAuditoriaService registroAuditoriaService,
                                       PlatformTransactionManager transactionManager) {
        this.establecimientoRepository = establecimientoRepository;
        this.autorizacionEmpleadoService = autorizacionEmpleadoService;
        this.imageKitService = imageKitService;
        this.validadorFoto = validadorFoto;
        this.registroAuditoriaService = registroAuditoriaService;
        if (transactionManager == null) {
            this.transactionTemplateLectura = null;
            this.transactionTemplateEscritura = null;
        } else {
            TransactionTemplate lectura = new TransactionTemplate(transactionManager);
            lectura.setReadOnly(true);
            this.transactionTemplateLectura = lectura;
            this.transactionTemplateEscritura = new TransactionTemplate(transactionManager);
        }
    }

    @Transactional(readOnly = true)
    public List<FotoEstablecimientoResponse> listar(Long establecimientoId, String email) {
        Establecimiento establecimiento = buscarEstablecimiento(establecimientoId);
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);
        return mapear(establecimiento.getFotos());
    }

    public FotoEstablecimientoResponse subir(Long establecimientoId, byte[] contenido, String nombreArchivo, String email) {
        // Fase 1 — transacción corta de lectura: resuelve, autoriza y valida. Todo lo que
        // sale de acá (el actor) puede quedar detached; no se vuelve a tocar el
        // establecimiento de esta fase.
        Autorizacion autorizacion = ejecutarLectura(status -> {
            Establecimiento establecimiento = buscarEstablecimiento(establecimientoId);
            Usuario actor = autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);
            validadorFoto.validar(contenido, establecimiento.getFotos().size());
            return new Autorizacion(actor);
        });

        // Fase 2 — SIN transacción abierta: acá es donde antes se retenía la conexión.
        FotoSubida subida = imageKitService.subir(contenido, nombreArchivo, carpetaDe(establecimientoId));

        // Fase 3 — transacción corta de escritura.
        return ejecutarEscritura(status -> {
            // 1) Registrar el hook de compensación PRIMERO: si algo de acá para abajo
            // falla, el rollback de esta transacción dispara el borrado en ImageKit del
            // archivo recién subido. Si se registrara después, un fallo entre el inicio de
            // la fase y el registro dejaría un archivo pago sin ninguna limpieza.
            registrarCompensacionSiFallaElCommit(subida.fileId());

            // 2) Recargar: el establecimiento de la fase 1 quedó detached.
            Establecimiento establecimiento = buscarEstablecimiento(establecimientoId);

            // 3) Revalidar el tope de fotos contra la lista recargada. Lo único que puede
            // haber cambiado en la ventana sin transacción (fase 2) es CUÁNTAS fotos hay
            // — el contenido del archivo no cambia entre fases — así que reusar
            // validar() con el mismo contenido pero el conteo recargado es exactamente
            // "revalidar el tope", sin duplicar el mensaje de error en otro lado. Si otra
            // subida concurrente llegó al tope primero, esto lanza IllegalArgumentException
            // y el rollback dispara la compensación del punto 1: no hace falta borrar el
            // archivo a mano acá.
            validadorFoto.validar(contenido, establecimiento.getFotos().size());

            // 4) Agregar, guardar, auditar.
            establecimiento.getFotos().add(FotoEstablecimiento.builder()
                    .url(subida.url())
                    .fileId(subida.fileId())
                    .build());
            establecimientoRepository.save(establecimiento);

            auditar(autorizacion.actor(), establecimiento, AccionAuditoria.SUBIR_FOTO_ESTABLECIMIENTO,
                    "fileId=" + subida.fileId());

            return new FotoEstablecimientoResponse(subida.url(), subida.fileId());
        });
    }

    public void borrar(Long establecimientoId, String fileId, String email) {
        // Fase 1 — transacción corta de lectura: resuelve, autoriza y confirma que la foto
        // existe (404 si no). Sin esto acá, un fileId inexistente terminaría llamando a
        // ImageKit igual.
        Autorizacion autorizacion = ejecutarLectura(status -> {
            Establecimiento establecimiento = buscarEstablecimiento(establecimientoId);
            Usuario actor = autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);
            buscarFoto(establecimiento, fileId);
            return new Autorizacion(actor);
        });

        // Fase 2 — SIN transacción abierta.
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

        // Fase 3 — transacción corta de escritura: recarga, saca la foto de la lista, guarda
        // y audita.
        ejecutarEscritura(status -> {
            Establecimiento establecimiento = buscarEstablecimiento(establecimientoId);
            FotoEstablecimiento foto = buscarFoto(establecimiento, fileId);

            establecimiento.getFotos().remove(foto);
            establecimientoRepository.save(establecimiento);

            auditar(autorizacion.actor(), establecimiento, AccionAuditoria.ELIMINAR_FOTO_ESTABLECIMIENTO, "fileId=" + fileId);
            return null;
        });
    }

    @Transactional
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

    private FotoEstablecimiento buscarFoto(Establecimiento establecimiento, String fileId) {
        return establecimiento.getFotos().stream()
                .filter(f -> fileId.equals(f.getFileId()))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Foto no encontrada en este establecimiento"));
    }

    private List<FotoEstablecimientoResponse> mapear(List<FotoEstablecimiento> fotos) {
        return fotos.stream()
                .filter(Objects::nonNull)
                .map(f -> new FotoEstablecimientoResponse(f.getUrl(), f.getFileId()))
                .toList();
    }

    private <T> T ejecutarLectura(TransactionCallback<T> fase) {
        return ejecutar(transactionTemplateLectura, fase);
    }

    private <T> T ejecutarEscritura(TransactionCallback<T> fase) {
        return ejecutar(transactionTemplateEscritura, fase);
    }

    private <T> T ejecutar(TransactionTemplate plantilla, TransactionCallback<T> fase) {
        if (plantilla == null) {
            // Sólo puede pasar con el constructor histórico (ver su javadoc): no hay
            // PlatformTransactionManager real, así que la fase corre directo, sin abrir
            // ninguna transacción.
            return fase.doInTransaction(null);
        }
        return plantilla.execute(fase);
    }

    /** Lo único que subir()/borrar() necesitan pasar de la fase 1 a la fase 3. */
    private record Autorizacion(Usuario actor) {
    }
}
