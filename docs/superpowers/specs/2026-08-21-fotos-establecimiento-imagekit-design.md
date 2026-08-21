# Fotos de establecimiento con ImageKit (subir, borrar, reordenar)

## Motivación

`Establecimiento.fotos` existe desde V13 pero se carga a mano contra la base: no hay forma
de que un dueño suba ni borre una foto de su complejo. La primera foto de la lista es la
`fotoPrincipal` de la card del marketplace, así que hoy el aspecto público de un complejo
depende de que alguien edite SQL. Se integra ImageKit desde cero y se expone la gestión de
fotos como sub-recurso del establecimiento, para OWNER/ADMIN.

## Alcance

- Dependencia y configuración del SDK de ImageKit, aislado en un `ImageKitService`.
- `fotos` pasa de `List<String>` a `List<FotoEstablecimiento>` (`url` + `fileId`) + migración.
- Cuatro endpoints scopeados al establecimiento: listar, subir, borrar, reordenar.
- Validación de tipo real (magic bytes), tamaño máximo y cantidad máxima de fotos.
- Auditoría de las tres mutaciones vía `RegistroAuditoriaService`.
- Compensación del archivo huérfano si el commit falla después de subir a ImageKit.
- Idempotencia por `Idempotency-Key` en el POST de subida.
- Fuera de alcance: uploads directos desde el browser (firmados con la public key),
  transformaciones de imagen, fotos de `Cancha`, y recuperar fotos ya borradas.

## Hallazgos de la exploración (por qué el diseño no es un mapeo literal del pedido original)

- **El SDK de ImageKit para Java fue reescrito, y la API que circula en tutoriales es la
  vieja.** La línea v2 (`com.github.imagekit-developer.imagekit-java:imagekit-sdk`, vía
  JitPack, con `ImageKit.getInstance()`, `Configuration(publicKey, privateKey, urlEndpoint)`
  y `FileCreateRequest`) quedó obsoleta. La actual es `io.imagekit:image-kit-java:3.5.0`,
  publicada en Maven Central el 2026-08-15, con una API completamente distinta. Todas las
  firmas de abajo se verificaron con `javap` contra el jar real, no de memoria.
- **El cliente v3 NO acepta `publicKey` ni `urlEndpoint`.** Su builder solo expone
  `privateKey`, `webhookSecret` y `baseUrl`. La `publicKey` sirve únicamente para firmar
  uploads desde el navegador y el `urlEndpoint` para construir URLs a mano — innecesario,
  porque la respuesta del upload ya trae la URL absoluta. Por eso se configura **una sola**
  property y no las tres del pedido original: las otras dos serían properties muertas.
- **`EstablecimientoResponse` no incluye `fotos`.** Sin un endpoint de lectura, el panel del
  dueño no tendría de dónde sacar los `fileId` para borrar o reordenar. De ahí el `GET` que
  no estaba en el pedido original.
- **`IdempotencyFilter` y `RateLimitFilter` matchean rutas por igualdad exacta de string**
  (`RUTAS_PROTEGIDAS.contains(request.getRequestURI())`), documentado en
  `RutasProtegidasCoincidenConControllersTest` (M26). Una ruta con `{id}` adentro nunca
  matchea. Requiere matcheo por patrón, aditivo (ver Fase 5).
- **El `IdempotencyFilter` drena el body para hashearlo, y eso muy probablemente rompe el
  multipart.** Corre dentro de la cadena de security (`addFilterAfter(idempotencyFilter,
  JwtAuthenticationFilter.class)`), o sea antes de que el DispatcherServlet resuelva el
  multipart. Hace `request.getInputStream().readAllBytes()` y envuelve en
  `CachedBodyHttpServletRequest`, que overridea `getInputStream()`/`getReader()` pero **no
  `getParts()`** — este delega al request original de Tomcat, cuyo stream ya quedó
  consumido. NO está verificado empíricamente todavía (ver Riesgos).
- **`RegistroAuditoria.accion` es `VARCHAR(255)` sin CHECK constraint**
  (`V1__baseline.sql:330`), con `@Enumerated(EnumType.STRING)`. Sumar valores a
  `AccionAuditoria` no necesita migración.
- **Migraciones**: la más alta hoy es `V17__eliminar_capacidad_cancha.sql`. La siguiente
  libre es **V18**.
- **`spring.servlet.multipart.max-file-size` no está configurado**, y el default de Spring
  Boot es **1MB**. Sin tocarlo, un archivo de 5MB muere con `MaxUploadSizeExceededException`
  antes de llegar al controller.
- **Los tests corren con `spring.flyway.enabled=false` y H2 `ddl-auto=create-drop`.** El
  esquema de test lo genera Hibernate, no Flyway: `./mvnw test` **no ejerce la V18**.
- **Ninguna migración seedea filas en `establecimiento_fotos`.** Las fotos legacy (si las
  hay) son cargas manuales contra la base.

## Diseño

### Fase 0 — Dependencia, configuración y aislamiento del SDK

`pom.xml`:

```xml
<dependency>
  <groupId>io.imagekit</groupId>
  <artifactId>image-kit-java</artifactId>
  <version>3.5.0</version>
</dependency>
```

Paquete nuevo `core/imagekit/` (criterio del repo: infra transversal va en `core/`, al lado
de `core/email`, no en la feature).

- **`ImageKitConfig`** — `@Configuration` que produce el bean `ImageKitClient`:
  `ImageKitOkHttpClient.builder().privateKey(...).build()`.
- **`ImageKitService`** — la ÚNICA clase que toca el SDK. Dos métodos:
  - `FotoSubida subir(byte[] contenido, String nombreArchivo, String carpeta)`
  - `void borrar(String fileId)`
- **`FotoSubida`** — `record FotoSubida(String url, String fileId)`.
- **`ImageKitException`** — RuntimeException propia; envuelve cualquier fallo del SDK.

API real del SDK (verificada con `javap`):

| Operación | Firma real |
|---|---|
| Cliente | `ImageKitOkHttpClient.builder().privateKey(String).build()` → `ImageKitClient` |
| Subir | `client.files().upload(FileUploadParams)` → `FileUploadResponse` |
| Params | `FileUploadParams.builder().file(byte[]).fileName(String).folder(String).useUniqueFileName(boolean).build()` |
| Respuesta | `Optional<String> fileId()`, `Optional<String> url()` |
| Borrar | `client.files().delete(String fileId)` → `void` |

`url()` y `fileId()` devuelven `Optional`: si alguno viene vacío, `subir` lanza
`ImageKitException` en vez de persistir una foto a medias.

Configuración:

- `application.properties`: `imagekit.private-key=${IMAGEKIT_PRIVATE_KEY:}`
  El default vacío es **obligatorio**: los tests setean `spring.config.import=` y un
  placeholder sin default rompería el arranque de contexto de toda la suite.
- `application-prod.properties`: `imagekit.private-key=${IMAGEKIT_PRIVATE_KEY}`
  Sin default → fail-fast en prod, mismo patrón que `DB_HOST`/`DB_NAME`.
- `.env.example`: `IMAGEKIT_PRIVATE_KEY=`. Nunca en `.env` ni en el repo.

`ImageKitException` → **502 Bad Gateway** en `GlobalExceptionHandler`: que ImageKit se caiga
no es un bug nuestro, y hoy caería en el handler genérico de 500.

### Fase 1 — Modelo, migración y derivaciones del marketplace

`establecimiento/model/FotoEstablecimiento.java`, `@Embeddable`:

- `url` → columna `foto_url`, NOT NULL, length 1000.
- `fileId` → columna `file_id`, **nullable** (las fotos legacy no tienen).

`Establecimiento.fotos` pasa a `List<FotoEstablecimiento>`, conservando
`@ElementCollection`, `@CollectionTable(name = "establecimiento_fotos")` y
`@OrderColumn(name = "orden")`. El `@Column(name = "foto_url")` se mueve del campo al
embeddable.

`V18__file_id_fotos_establecimiento.sql`:

```sql
ALTER TABLE establecimiento_fotos ADD COLUMN file_id VARCHAR(255);

-- Único parcial: el fileId es la clave de borrado y reordenamiento, dos iguales en el
-- mismo establecimiento serían ambiguos. Parcial porque las fotos legacy (cargadas a
-- mano antes de ImageKit) tienen file_id NULL y varias NULL deben poder convivir.
CREATE UNIQUE INDEX uq_establecimiento_fotos_file_id
    ON establecimiento_fotos (establecimiento_id, file_id)
    WHERE file_id IS NOT NULL;
```

Derivaciones a actualizar en `ComplejoPublicoService`:

- Línea 216: `fotoPrincipal = fotos.isEmpty() ? null : fotos.get(0).getUrl()`
- Línea 312: `establecimiento.getFotos().stream().map(FotoEstablecimiento::getUrl).toList()`

`ComplejoDetalleResponse.fotos` y `ComplejoCardResponse.fotoPrincipal` siguen siendo
`List<String>` y `String`. **El JSON público no cambia.**

### Fase 2 — Endpoints

`FotoEstablecimientoController` (controller propio del sub-recurso) y
`FotoEstablecimientoService` (servicio propio; `EstablecimientoService` ya es grande y esto
no comparte nada con su lógica de altas/límites de plan).

Los cuatro resuelven el establecimiento por id y pasan por
`autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email)` — es gestión
de OWNER/ADMIN, sin excepción para empleados.

| Método | Ruta | Body | Respuesta |
|---|---|---|---|
| `GET` | `/api/v1/establecimientos/{id}/fotos` | — | `200` + `List<FotoEstablecimientoResponse>` |
| `POST` | `/api/v1/establecimientos/{id}/fotos` | `multipart/form-data`, parte `archivo` | `201` + la foto creada |
| `DELETE` | `/api/v1/establecimientos/{id}/fotos/{fileId}` | — | `204` |
| `PUT` | `/api/v1/establecimientos/{id}/fotos/orden` | `{ "fileIds": [...] }` | `200` + la lista reordenada |

DTOs en `establecimiento/dto/`:

- `FotoEstablecimientoResponse(String url, String fileId)`
- `ReordenarFotosRequest(@NotEmpty List<String> fileIds)`

Comportamiento:

- **Subir**: valida, sube a la carpeta `/establecimientos/{id}/` con
  `useUniqueFileName(true)`, agrega `{url, fileId}` al FINAL de la lista.
- **Borrar**: busca la foto por `fileId`; si no está → 404. Llama a
  `imageKitService.borrar(fileId)`. **Si ImageKit falla, se saca igual de la lista y se
  loguea el `fileId` a ERROR** para limpieza posterior — decisión explícita: un fallo de
  ImageKit no deja al usuario trabado con una foto que no puede sacar.
- **Reordenar**: los `fileIds` recibidos deben ser **exactamente una permutación** de los
  actuales. Si sobran, faltan o hay repetidos → 400. Si el establecimiento tiene alguna
  foto con `file_id` NULL → 400 (no son direccionables; ver Decisiones). La primera del
  nuevo orden pasa a ser la `fotoPrincipal`.

### Fase 3 — Validación y límites

`ValidadorFoto`, clase chica y testeable por separado:

- **Tipo real por magic bytes, NO por el header.** El `Content-Type` de la parte multipart
  lo manda el cliente y se falsifica trivialmente; la extensión del nombre, más todavía. Se
  inspeccionan los bytes:
  - JPEG: `FF D8 FF`
  - PNG: `89 50 4E 47 0D 0A 1A 0A`
  - WebP: `52 49 46 46` (`RIFF`) en 0..3 y `57 45 42 50` (`WEBP`) en 8..11
  Cualquier otra cosa → 400. Esto *es* la respuesta a "validá el content-type real".
- **Tamaño máximo 5 MB** por archivo → 400. Archivo vacío → 400.
- **Máximo 10 fotos** por establecimiento → 400 al intentar la 11ª.
- `spring.servlet.multipart.max-file-size=6MB` y `max-request-size=6MB` en
  `application.properties`: holgura sobre el límite de 5MB para que el 400 lo produzca
  nuestro validador con un mensaje claro, y no el contenedor.
- `@ExceptionHandler(MaxUploadSizeExceededException.class)` → 400, como red de contención
  para lo que igual supere el límite del contenedor.

Los límites viven como constantes en `ValidadorFoto` (`TAMANIO_MAXIMO_BYTES`,
`MAXIMO_FOTOS_POR_ESTABLECIMIENTO`), no repartidos por el servicio.

### Fase 4 — Auditoría y compensación del huérfano

Valores nuevos en `AccionAuditoria` (sin migración, la columna es VARCHAR sin CHECK):

```
SUBIR_FOTO_ESTABLECIMIENTO
ELIMINAR_FOTO_ESTABLECIMIENTO
REORDENAR_FOTOS_ESTABLECIMIENTO
```

Se registran con
`registroAuditoriaService.registrarSobreEstablecimiento(actor, establecimiento, accion, entidadAfectadaId, detalle)`,
que existe exactamente para "acción administrativa que el propio OWNER/ADMIN ejecuta
directamente sobre un recurso del establecimiento". Como `entidadAfectadaId` es `Long` y la
identidad de una foto es un `fileId` String, va el id del establecimiento en
`entidadAfectadaId` y el `fileId` en `detalle`.

`RegistroAuditoria.detalle` es `VARCHAR(500)`. Para SUBIR y ELIMINAR va el `fileId` solo y
sobra lugar. Para REORDENAR **no se guarda la permutación completa** (10 `fileId` rozarían el
límite y una lista cruda no se lee): se guarda el `fileId` de la nueva foto principal y la
cantidad de fotos, que es la información que realmente importa auditar. El servicio trunca
`detalle` a 500 antes de registrar, para que ningún caso raro haga fallar el INSERT.

`registro_auditoria_empleados.establecimiento_id` es `NOT NULL` y `empleado_id` es nullable,
que es exactamente la forma que produce `registrarSobreEstablecimiento` (setea
`establecimiento` y `actorId`, no `empleado`). No hace falta tocar la tabla.

**Compensación del archivo huérfano.** Si ImageKit acepta el archivo y después el commit
falla, queda un archivo pago que ya nadie referencia. Un `try/catch` dentro del método no lo
cubre: el commit ocurre después de que el método retorna. El hook correcto es una
`TransactionSynchronization`:

```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCompletion(int status) {
        if (status != STATUS_COMMITTED) {
            // borrar de ImageKit + log del fileId
        }
    }
});
```

Si la compensación misma falla, se loguea el `fileId` a ERROR — misma filosofía que el
fallo de borrado del DELETE: nunca romper la respuesta al usuario por un fallo de ImageKit,
siempre dejar el `fileId` en el log para limpieza manual.

### Fase 5 — Idempotencia en la subida

Dos cambios en `IdempotencyFilter`, **ambos aditivos** — los 4 endpoints existentes
(reservas, ventas de buffet) no cambian de comportamiento. Es el código más sensible de la
app y no se refactoriza de paso.

**(a) Matcheo por patrón, en un set nuevo.**

```java
public static final Set<String> PATRONES_PROTEGIDOS = Set.of(
        "/api/v1/establecimientos/{id}/fotos"
);
```

`aplicaAEstaRuta` pasa a ser "POST Y (está en `RUTAS_PROTEGIDAS` por igualdad exacta O
matchea algún patrón de `PATRONES_PROTEGIDOS`)", con `PathPatternParser` (ya en el
classpath, Spring MVC 6 lo usa por default). `RUTAS_PROTEGIDAS` queda intacto.

`RutasProtegidasCoincidenConControllersTest` gana un tercer método que itera
`PATRONES_PROTEGIDOS` reusando `assertRutaTienePostMapping` **sin modificarlo**: ese assert
compara contra `patron.getPatternString()`, que para nuestro mapping es literalmente
`/api/v1/establecimientos/{id}/fotos`.

**(b) Saltear el hasheo del body en multipart.**

```java
boolean esMultipart = request.getContentType() != null
        && request.getContentType().toLowerCase().startsWith("multipart/");
byte[] cuerpoBytes = esMultipart ? new byte[0] : request.getInputStream().readAllBytes();
```

y en multipart se pasa el request original a la cadena, sin envolver.

Degradación documentada: para multipart se pierde la detección de "misma clave con payload
distinto" (la respuesta 422). La idempotencia en sí — no repetir el efecto ante un reintento
— sigue funcionando, que es lo que importa. Detectar payloads distintos exigiría hashear el
archivo entero en memoria en un filtro de security, que es peor negocio.

## Decisiones descartadas

- **Configurar `imagekit.public-key` y `imagekit.url-endpoint`.** El SDK v3 no las acepta y
  nada más las consumiría. Serían properties muertas. Se agregan el día que haya uploads
  directos desde el browser.
- **Indexar fotos por posición (`/fotos/{indice}`) en vez de por `fileId`.** Cubriría
  también las fotos legacy, pero el índice es inestable: con dos ediciones concurrentes se
  borra la foto equivocada. El `fileId` identifica la foto sin ambigüedad.
- **Migrar las fotos legacy a un `file_id` sintético.** Serían direccionables para
  reordenar, pero borrarlas fallaría igual en ImageKit (ese id no existe allá), cambiando
  un problema visible por uno confuso. **Quedan solo-lectura**: se siguen mostrando en el
  marketplace, no se pueden gestionar por API, y si molestan se limpian a mano en la base.
- **`ImageKitService` como interfaz con una implementación falsa para dev** (patrón
  `EmailService`/`LogEmailService`). Un stub que devuelve URLs inventadas produciría fotos
  rotas en el marketplace local, que es peor que un error claro de configuración. Se deja
  como clase concreta, mockeable con Mockito en los tests.
- **Compensar el huérfano con reintentos o una cola.** Un log a ERROR con el `fileId`
  alcanza para el volumen de esta app.

## Riesgos y verificaciones pendientes

1. **¿`builder().privateKey("").build()` explota al arrancar?** Si el builder valida
   no-blank, el default vacío de dev reventaría el contexto de **toda** la suite de tests.
   **Primer paso de la Fase 0**: comprobarlo empíricamente. Si explota, el `ImageKitClient`
   pasa a construirse perezosamente en el primer uso de `ImageKitService`.
2. **¿El `IdempotencyFilter` realmente rompe el multipart?** El mecanismo se dedujo leyendo
   el filtro y la cadena de security; **no se ejecutó**. **Primer paso de la Fase 5**:
   escribir un test que suba un archivo CON `Idempotency-Key` y ver si el archivo llega
   vacío, ANTES de tocar el filtro. Si no se rompe, el cambio (b) no va.
3. **La V18 no la ejercita `./mvnw test`** (Flyway apagado, H2 con `create-drop`). Su
   correctitud se verifica por inspección. El índice único parcial es sintaxis Postgres y
   no correría en H2 de todos modos.
4. **`kotlin-stdlib` y `okhttp` entran como transitivas del SDK.** Verificar con
   `./mvnw dependency:tree` que no colisionen con nada que ya gestione el BOM de Spring Boot.

## Testing

`FotoEstablecimientoServiceTest` (Mockito, con `ImageKitService` mockeado):

- Subida OK como dueño → la foto queda agregada con `url` y `fileId`.
- Subir a un establecimiento ajeno → `AccessDeniedException` (403).
- Tipo inválido, archivo vacío, tamaño > 5MB, y la 11ª foto → 400.
- Borrar llama a `imageKitService.borrar(fileId)` y saca la foto de la lista.
- Si `borrar` tira excepción, la foto se saca de la lista igual.
- Borrar un `fileId` inexistente → 404.
- Reordenar cambia cuál es la primera (la `fotoPrincipal`).
- Reordenar con `fileIds` que no son permutación exacta → 400.
- Reordenar con una foto legacy (`file_id` NULL) presente → 400.

`ValidadorFotoTest`: un PNG declarado como `image/jpeg` pasa (mandan los bytes); un PDF
renombrado a `.jpg` con content-type `image/jpeg` es rechazado; WebP válido aceptado;
archivo de 3 bytes rechazado sin `IndexOutOfBounds`.

`FotoEstablecimientoControllerIntegrationTest`: MockMvc + `MockMultipartFile` + JWT real +
H2, con `@MockitoBean ImageKitService` (el repo no usa hoy ni `@MockBean` ni
`@MockitoBean`; se introduce `@MockitoBean`, que es el no-deprecado en Spring Boot 3.5).
Cubre 201, 403 y 400.

`RegistroAuditoriaService` verificado con `verify(...)` en los tests de servicio, no con
tests propios.

Actualizar `ComplejoPublicoControllerDetalleNoTransactionalTest:83`, que construye
`.fotos(List.of("https://cdn.example.com/foto1.jpg"))` y deja de compilar con el tipo nuevo.

Cierre: `./mvnw test` en verde.
