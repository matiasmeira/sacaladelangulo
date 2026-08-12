# GET /api/v1/buffet/ventas — Listado paginado de ventas de buffet

## Motivación

El front necesita una tabla con las ventas individuales de buffet de un
establecimiento en un rango de fechas. `VentaBuffetController` hoy solo expone
`POST`, `PUT /{id}/cancelar` y `GET /metricas` (agregados). No existe forma de
listar las ventas una por una, y la query de repositorio más cercana
(`findByEstablecimientoIdAndEstadoAndFechaHoraBetween`) no pagina y exige `estado`
obligatorio (esconde las canceladas si no se pasa).

## Alcance

Un único endpoint nuevo: `GET /api/v1/buffet/ventas`, agregado a
`VentaBuffetController` (mismo patrón no anidado bajo establecimiento que
`/metricas`: `establecimientoId` como query param).

No se toca `/metricas`, `POST`, ni `PUT /{id}/cancelar`. Sin cambios de esquema.

## Diseño

**`buffet/dto/VentaResumenResponse.java`** (record nuevo, mismo estilo que
`TurnoCajaResumenResponse`): la tabla del front no necesita el desglose de
`detalles`, así que en vez de reusar `VentaResponse` con `detalles` siempre vacío,
va un DTO dedicado sin ese campo.

```java
record VentaResumenResponse(
    Long id,
    LocalDateTime fechaHora,
    BigDecimal total,
    String estado,
    String metodoPago,
    Long reservaId
)
```

**`buffet/dto/VentaMapper`**: nuevo método `mapToResumenResponse(Venta venta)`,
misma forma que `mapToResponse` pero sin mapear `detalles`. `venta.getReserva()` es
`@ManyToOne(LAZY)`; llamar `.getId()` sobre el proxy no dispara una query adicional
(mismo acceso que ya hace `mapToResponse` hoy), así que no hace falta fetch join
para ese campo.

**`buffet/repository/VentaRepository`**: nuevo método paginado, `estado` opcional
vía `:estado IS NULL OR ...` (mismo patrón que `GastoRepository.buscar`). Sin
`JOIN FETCH`: como la respuesta no lleva `detalles`, no hace falta traer esa
colección, y así se evita de raíz la trampa de fetch-join-de-colección +
`Pageable` (HHH90003004) en vez de tener que resolverla.

```java
@Query("SELECT v FROM Venta v WHERE v.establecimiento.id = :establecimientoId " +
        "AND (:estado IS NULL OR v.estado = :estado) " +
        "AND v.fechaHora BETWEEN :desde AND :hasta")
Page<Venta> buscarPaginado(@Param("establecimientoId") Long establecimientoId,
                            @Param("estado") EstadoVenta estado,
                            @Param("desde") LocalDateTime desde,
                            @Param("hasta") LocalDateTime hasta,
                            Pageable pageable);
```

**`buffet/service/VentaMetricasService`**: nuevo método `listarVentas`, no
`VentaService` — `VentaService` es exclusivamente ciclo de vida (registrar/
cancelar, ver su propio javadoc), mientras que `VentaMetricasService` ya es donde
vive el resto de las consultas de solo-lectura scopeadas a un establecimiento
(`/metricas`), con la misma autorización que este endpoint necesita. Requiere
agregar `VentaMapper` como dependencia nueva del service.

```java
@Transactional(readOnly = true)
public Page<VentaResumenResponse> listarVentas(Long establecimientoId, LocalDate desde,
        LocalDate hasta, EstadoVenta estado, String email, Pageable pageable) {
    Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
            .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
    autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

    if (desde.isAfter(hasta)) {
        throw new IllegalArgumentException("La fecha 'desde' no puede ser posterior a 'hasta'");
    }

    return ventaRepository.buscarPaginado(establecimientoId, estado,
                    desde.atStartOfDay(), hasta.atTime(LocalTime.MAX), pageable)
            .map(ventaMapper::mapToResumenResponse);
}
```

Mismo criterio de autorización que `obtenerMetricas`: `validarPropietarioOAdmin`
lanza `AccessDeniedException` si el usuario no es el dueño real del
establecimiento ni ADMIN — un OWNER de otro establecimiento queda bloqueado igual
que hoy en `/metricas`.

**`VentaBuffetController`**: nuevo `@GetMapping` (sin path, la base ya es
`/api/v1/buffet/ventas`) junto a `/metricas`, `@PreAuthorize("hasAnyRole('OWNER',
'ADMIN')")` (mismos roles que `/metricas` y `cancelar`).

```java
@GetMapping
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
public ResponseEntity<Page<VentaResumenResponse>> listarVentas(
        @RequestParam Long establecimientoId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
        @RequestParam(required = false) EstadoVenta estado,
        @AuthenticationPrincipal UserDetails userDetails,
        @ParameterObject @PageableDefault(size = 20, sort = "fechaHora", direction = Sort.Direction.DESC) Pageable pageable) {
    return ResponseEntity.ok(ventaMetricasService.listarVentas(
            establecimientoId, desde, hasta, estado, userDetails.getUsername(), pageable));
}
```

`estado` como `@RequestParam(required = false) EstadoVenta` — Spring bindea el
enum directo desde el query param sin converter custom (mismo patrón que
`CategoriaGasto` en `GastoController`); si viene `null`, `buscarPaginado` no
filtra por estado y trae CONFIRMADA + CANCELADA.

## Testing

**`VentaMetricasServiceTest`** (unitario, Mockito — se agregan casos al archivo
existente): pasa `estado` tal cual al repositorio (incluido `null`), pagina
correctamente (verifica que el `Pageable` recibido se propaga y que el resultado
del mock se mapea con `VentaMapper`), `desde > hasta` tira
`IllegalArgumentException`, un usuario que no es dueño ni ADMIN del
establecimiento tira `AccessDeniedException` (mismo caso que ya existe para
`obtenerMetricas`).

**Integración nueva, `VentaBuffetControllerListarTest`**: `@SpringBootTest` +
`@AutoConfigureMockMvc` + H2 en memoria + JWT real (mismo patrón que
`EmpleadoControllerListarTest`, no mocks de seguridad). Siembra ventas
CONFIRMADA y CANCELADA de un establecimiento en fechas distintas dentro y fuera
del rango pedido, más de una página de datos (>20 ventas) para poder verificar
`totalElements`/`totalPages` de verdad.

Casos:
- Sin `estado`: devuelve CONFIRMADA + CANCELADA, ordenadas por `fechaHora` desc.
- Con `estado=CANCELADA`: solo esas.
- `totalElements`/`totalPages`/`content.length` correctos con más de una página
  (`page=0&size=...` y `page=1&size=...`).
- Ventas fuera del rango `desde`/`hasta` no aparecen.
- Un OWNER dueño de OTRO establecimiento pidiendo `establecimientoId` ajeno recibe
  403.
- Sin token → 401 (cubierto por la cadena de seguridad general, un caso basta).
