# Fotos de establecimiento con ImageKit — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que un OWNER/ADMIN pueda subir, listar, borrar y reordenar las fotos de su establecimiento, con los archivos alojados en ImageKit.

**Architecture:** El SDK de ImageKit queda aislado detrás de `ImageKitService` (dos métodos: `subir`, `borrar`) en `core/imagekit/`. `Establecimiento.fotos` pasa de `List<String>` a `List<FotoEstablecimiento>` (`url` + `fileId`), conservando el `@OrderColumn` que define cuál es la foto principal. Un `FotoEstablecimientoController`/`Service` nuevo expone el sub-recurso, autorizado con `AutorizacionEmpleadoService.validarPropietarioOAdmin`. La validación de la imagen se hace por magic bytes, no por el content-type declarado.

**Tech Stack:** Java 21, Spring Boot 3.5.14, Spring Data JPA, Flyway, Lombok, `io.imagekit:image-kit-java:3.5.0`, JUnit 5 + Mockito, H2 en tests.

**Spec:** `docs/superpowers/specs/2026-08-21-fotos-establecimiento-imagekit-design.md`

## Global Constraints

- **Repo:** `c:\Users\USER\Desktop\sacaladelangulo`. Rama actual: `test`.
- **Hay trabajo sin commitear que NO es de esta feature**: `src/test/java/.../publico/controller/ComplejoPublicoControllerIntegrationTest.java`. **Nunca usar `git add -A` ni `git add .`** — stagear siempre por path explícito.
- **SDK:** `io.imagekit:image-kit-java:3.5.0` (Maven Central). La API v2 (`ImageKit.getInstance()`, `FileCreateRequest`, JitPack) **no existe** en esta versión. Firmas exactas en la tabla de la Tarea 1.
- **Migración libre:** V18. Las migraciones ya aplicadas son inmutables — nunca editar una `V{n}` existente.
- **Comandos:** `./mvnw test` corre la suite (excluye `@Tag("testcontainers")`). Usar `./mvnw test -Dtest=NombreDelTest` para un test puntual.
- **Commits:** en español, Conventional Commits, modo imperativo ("agrega", "corrige"). Prohibidas palabras de relleno: robusto, eficiente, optimizado, dinámico, comprehensive, mejorado, potente, flexible, escalable, sólido.
- **Límites:** 5 MB por archivo (`5 * 1024 * 1024`), 10 fotos por establecimiento, tipos `image/jpeg`, `image/png`, `image/webp`.
- **Nunca commitear secretos.** `IMAGEKIT_PRIVATE_KEY` va en `.env.example` con valor vacío, jamás en `.env` ni en properties.
- **Idioma del código:** clases, métodos y variables en español, como el resto del repo (`subir`, `borrar`, `validarPropietarioOAdmin`).

---

### Task 1: Dependencia, configuración y `ImageKitService`

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/resources/application-prod.properties`
- Modify: `.env.example`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/core/imagekit/ImageKitConfig.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/core/imagekit/ImageKitService.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/core/imagekit/FotoSubida.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/core/imagekit/ImageKitException.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/core/exception/GlobalExceptionHandler.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/core/imagekit/ImageKitServiceTest.java`

**Interfaces:**
- Consumes: nada (primera tarea).
- Produces:
  - `record FotoSubida(String url, String fileId)`
  - `ImageKitService.subir(byte[] contenido, String nombreArchivo, String carpeta)` → `FotoSubida`
  - `ImageKitService.borrar(String fileId)` → `void`
  - `class ImageKitException extends RuntimeException`

**API real del SDK (verificada con `javap` contra el jar 3.5.0 — no inventar firmas):**

| Operación | Firma |
|---|---|
| Cliente | `ImageKitOkHttpClient.builder().privateKey(String).build()` → `ImageKitClient` |
| Subir | `client.files().upload(FileUploadParams)` → `FileUploadResponse` |
| Params | `FileUploadParams.builder().file(byte[]).fileName(String).folder(String).useUniqueFileName(boolean).build()` |
| Respuesta | `Optional<String> fileId()`, `Optional<String> url()` |
| Borrar | `client.files().delete(String fileId)` → `void` |

- [ ] **Step 1: Agregar la dependencia**

En `pom.xml`, dentro de `<dependencies>`, después del bloque de `resend-java`:

```xml
<!-- SDK oficial de ImageKit (alojamiento de las fotos de establecimiento). La línea
     v2 (com.github.imagekit-developer, vía JitPack) quedó obsoleta: esta es la v3,
     publicada en Maven Central, con una API distinta. Trae Jackson 2.18.2 y valida en
     runtime una versión MÍNIMA; el BOM de Spring Boot impone 2.21.2, que la supera y
     pasa el chequeo. kotlin-stdlib sube de 1.8.0 a 1.9.25 por el mismo BOM. -->
<dependency>
    <groupId>io.imagekit</groupId>
    <artifactId>image-kit-java</artifactId>
    <version>3.5.0</version>
</dependency>
```

- [ ] **Step 2: Verificar que compila y que no rompió la suite**

Run: `./mvnw -q compile`
Expected: BUILD SUCCESS (la descarga de las transitivas puede tardar la primera vez).

- [ ] **Step 3: Configurar las properties**

En `src/main/resources/application.properties`, después del bloque de `resend.enabled`:

```properties
# Clave privada de ImageKit (alojamiento de fotos de establecimiento). Default VACÍO a
# propósito, y no ausente: los tests setean spring.config.import= y un placeholder sin
# default rompería el arranque de contexto de toda la suite. El SDK construye el cliente
# sin problema con una clave vacía (falla recién al llamar a la API), así que el default
# vacío degrada en dev sin romper nada. application-prod.properties lo pisa SIN default
# para que producción no arranque sin la clave real.
imagekit.private-key=${IMAGEKIT_PRIVATE_KEY:}

# El default de Spring Boot es 1MB: sin esto un archivo de 5MB muere con
# MaxUploadSizeExceededException antes de llegar al controller. Se deja holgura sobre el
# límite de 5MB de ValidadorFoto para que el 400 lo produzca nuestra validación, con un
# mensaje claro, y no el contenedor.
spring.servlet.multipart.max-file-size=6MB
spring.servlet.multipart.max-request-size=6MB
```

En `src/main/resources/application-prod.properties`, al final:

```properties
# --- ImageKit ----------------------------------------------------------------
# SIN default (a diferencia de la base): si falta la env var, la app NO arranca, en vez
# de quedar sirviendo un endpoint de subida que falla en cada request. Mismo criterio
# que DB_HOST/DB_NAME.
imagekit.private-key=${IMAGEKIT_PRIVATE_KEY}
```

En `.env.example`, al final:

```
# Clave PRIVADA de ImageKit. Es un secreto: nunca commitear el valor real.
# Se saca del dashboard de ImageKit > Developer options > API keys.
IMAGEKIT_PRIVATE_KEY=
```

- [ ] **Step 4: Crear `FotoSubida` y `ImageKitException`**

`core/imagekit/FotoSubida.java`:

```java
package com.matiasmeira.sacaladelangulo.core.imagekit;

/**
 * Resultado de subir un archivo a ImageKit: la URL pública con la que se sirve y el
 * fileId con el que después se lo borra. Los dos se persisten en FotoEstablecimiento.
 */
public record FotoSubida(String url, String fileId) {
}
```

`core/imagekit/ImageKitException.java`:

```java
package com.matiasmeira.sacaladelangulo.core.imagekit;

/**
 * Falla al hablar con ImageKit (red, credenciales, respuesta incompleta). Se mapea a 502
 * en GlobalExceptionHandler: que el proveedor externo se caiga no es un error de esta
 * app, y sin esta excepción caería en el handler genérico de 500.
 */
public class ImageKitException extends RuntimeException {

    public ImageKitException(String message) {
        super(message);
    }

    public ImageKitException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 5: Crear `ImageKitConfig`**

`core/imagekit/ImageKitConfig.java`:

```java
package com.matiasmeira.sacaladelangulo.core.imagekit;

import io.imagekit.client.ImageKitClient;
import io.imagekit.client.okhttp.ImageKitOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Único punto donde se construye el cliente del SDK de ImageKit. El SDK recomienda un
 * solo cliente por aplicación (tiene su propio pool de conexiones y de hilos), así que va
 * como bean singleton.
 */
@Configuration
public class ImageKitConfig {

    /**
     * Siempre se llama a privateKey(), aunque el valor sea vacío: omitir el setter hace
     * que build() lance IllegalStateException("`privateKey` is required, but was not
     * set") y tumbe el arranque. Con la clave vacía el cliente se construye bien y falla
     * recién al llamar a la API, que es el comportamiento que se quiere en dev/tests.
     */
    @Bean
    public ImageKitClient imageKitClient(@Value("${imagekit.private-key}") String privateKey) {
        return ImageKitOkHttpClient.builder()
                .privateKey(privateKey)
                .build();
    }
}
```

- [ ] **Step 6: Escribir el test de `ImageKitService` (falla)**

`src/test/java/com/matiasmeira/sacaladelangulo/core/imagekit/ImageKitServiceTest.java`:

```java
package com.matiasmeira.sacaladelangulo.core.imagekit;

import io.imagekit.client.ImageKitClient;
import io.imagekit.models.files.FileUploadParams;
import io.imagekit.models.files.FileUploadResponse;
import io.imagekit.services.blocking.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ImageKitService - aislamiento del SDK de ImageKit")
class ImageKitServiceTest {

    @Mock
    private ImageKitClient imageKitClient;

    @Mock
    private FileService fileService;

    private ImageKitService imageKitService;

    @BeforeEach
    void setUp() {
        imageKitService = new ImageKitService(imageKitClient);
    }

    @Test
    @DisplayName("subir_devuelveUrlYFileId_yMandaLaCarpetaAlSdk")
    void subir_devuelveUrlYFileId_yMandaLaCarpetaAlSdk() {
        FileUploadResponse respuesta = FileUploadResponse.builder()
                .url("https://ik.imagekit.io/demo/foto_abc.jpg")
                .fileId("file_abc")
                .build();
        when(imageKitClient.files()).thenReturn(fileService);
        when(fileService.upload(any(FileUploadParams.class))).thenReturn(respuesta);

        FotoSubida resultado = imageKitService.subir(new byte[]{1, 2, 3}, "foto.jpg", "/establecimientos/7/");

        assertThat(resultado.url()).isEqualTo("https://ik.imagekit.io/demo/foto_abc.jpg");
        assertThat(resultado.fileId()).isEqualTo("file_abc");

        // folder() devuelve Optional<String>: hasValue() compara el contenido exacto.
        ArgumentCaptor<FileUploadParams> captor = ArgumentCaptor.forClass(FileUploadParams.class);
        verify(fileService).upload(captor.capture());
        assertThat(captor.getValue().folder()).hasValue("/establecimientos/7/");
    }

    @Test
    @DisplayName("subir_lanzaImageKitException_siLaRespuestaNoTraeFileId")
    void subir_lanzaImageKitException_siLaRespuestaNoTraeFileId() {
        FileUploadResponse sinFileId = FileUploadResponse.builder()
                .url("https://ik.imagekit.io/demo/foto_abc.jpg")
                .build();
        when(imageKitClient.files()).thenReturn(fileService);
        when(fileService.upload(any(FileUploadParams.class))).thenReturn(sinFileId);

        assertThatThrownBy(() -> imageKitService.subir(new byte[]{1}, "foto.jpg", "/establecimientos/7/"))
                .isInstanceOf(ImageKitException.class);
    }

    @Test
    @DisplayName("subir_envuelveLaExcepcionDelSdk_enImageKitException")
    void subir_envuelveLaExcepcionDelSdk_enImageKitException() {
        when(imageKitClient.files()).thenReturn(fileService);
        when(fileService.upload(any(FileUploadParams.class)))
                .thenThrow(new RuntimeException("timeout hablando con ImageKit"));

        assertThatThrownBy(() -> imageKitService.subir(new byte[]{1}, "foto.jpg", "/establecimientos/7/"))
                .isInstanceOf(ImageKitException.class);
    }

    @Test
    @DisplayName("borrar_delegaElFileIdAlSdk")
    void borrar_delegaElFileIdAlSdk() {
        when(imageKitClient.files()).thenReturn(fileService);

        imageKitService.borrar("file_abc");

        verify(fileService).delete("file_abc");
    }

    @Test
    @DisplayName("borrar_envuelveLaExcepcionDelSdk_enImageKitException")
    void borrar_envuelveLaExcepcionDelSdk_enImageKitException() {
        when(imageKitClient.files()).thenReturn(fileService);
        org.mockito.Mockito.doThrow(new RuntimeException("500 de ImageKit"))
                .when(fileService).delete("file_abc");

        assertThatThrownBy(() -> imageKitService.borrar("file_abc"))
                .isInstanceOf(ImageKitException.class);
    }
}
```

- [ ] **Step 7: Correr el test y verificar que falla**

Run: `./mvnw test -Dtest=ImageKitServiceTest`
Expected: FAIL de compilación — `ImageKitService` no existe todavía.

- [ ] **Step 8: Implementar `ImageKitService`**

`core/imagekit/ImageKitService.java`:

```java
package com.matiasmeira.sacaladelangulo.core.imagekit;

import io.imagekit.client.ImageKitClient;
import io.imagekit.models.files.FileUploadParams;
import io.imagekit.models.files.FileUploadResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Única clase del proyecto que toca el SDK de ImageKit. El resto del código habla de
 * FotoSubida y de fileIds, sin conocer los tipos del SDK: si mañana se cambia de
 * proveedor, el cambio queda contenido acá.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImageKitService {

    private final ImageKitClient imageKitClient;

    /**
     * Sube el archivo y devuelve la URL pública con la que se sirve más el fileId con el
     * que después se lo borra.
     *
     * useUniqueFileName(true): ImageKit le agrega un sufijo al nombre, así que dos fotos
     * con el mismo nombre de archivo no se pisan entre sí.
     */
    public FotoSubida subir(byte[] contenido, String nombreArchivo, String carpeta) {
        FileUploadResponse respuesta;
        try {
            respuesta = imageKitClient.files().upload(FileUploadParams.builder()
                    .file(contenido)
                    .fileName(nombreArchivo)
                    .folder(carpeta)
                    .useUniqueFileName(true)
                    .build());
        } catch (RuntimeException ex) {
            throw new ImageKitException("Falló la subida del archivo a ImageKit", ex);
        }

        // url() y fileId() son Optional en el SDK. Sin los dos la foto no se puede ni
        // mostrar ni borrar después, así que se corta acá en vez de persistir una fila
        // inutilizable.
        String url = respuesta.url()
                .orElseThrow(() -> new ImageKitException("ImageKit no devolvió la url del archivo subido"));
        String fileId = respuesta.fileId()
                .orElseThrow(() -> new ImageKitException("ImageKit no devolvió el fileId del archivo subido"));

        return new FotoSubida(url, fileId);
    }

    public void borrar(String fileId) {
        try {
            imageKitClient.files().delete(fileId);
        } catch (RuntimeException ex) {
            throw new ImageKitException("Falló el borrado del archivo " + fileId + " en ImageKit", ex);
        }
    }
}
```

- [ ] **Step 9: Correr el test y verificar que pasa**

Run: `./mvnw test -Dtest=ImageKitServiceTest`
Expected: PASS, 5 tests.

Firmas del SDK usadas acá, ya verificadas con `javap` contra el jar 3.5.0 — si algo no
compila, el error está en el código, no en estas firmas:
`FileUploadResponse.builder()` (estático) → `.url(String)` → `.fileId(String)` → `.build()`,
y `FileUploadParams.folder()` → `Optional<String>`.

- [ ] **Step 10: Mapear `ImageKitException` a 502**

En `GlobalExceptionHandler`, junto a los otros handlers, siguiendo el estilo de los existentes:

```java
@ExceptionHandler(ImageKitException.class)
public ResponseEntity<Map<String, String>> handleImageKitException(ImageKitException ex) {
    log.error("Error hablando con ImageKit", ex);
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(Map.of("error", "El servicio de imágenes no está disponible en este momento."));
}
```

Copiar la forma exacta del cuerpo (`Map.of("error", ...)` o el tipo que usen los handlers vecinos) mirando los que ya están en el archivo. Si la clase no tiene `log`, agregarle `@Slf4j` o usar el logger que ya tenga.

- [ ] **Step 11: Correr la suite completa**

Run: `./mvnw test`
Expected: PASS. Confirma que agregar el SDK no rompió el arranque de contexto de ningún `@SpringBootTest` (el riesgo de la clave vacía, ya descartado empíricamente, se re-confirma acá de punta a punta).

- [ ] **Step 12: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/main/resources/application-prod.properties .env.example src/main/java/com/matiasmeira/sacaladelangulo/core/imagekit src/main/java/com/matiasmeira/sacaladelangulo/core/exception/GlobalExceptionHandler.java src/test/java/com/matiasmeira/sacaladelangulo/core/imagekit
git commit -m "feat(imagekit): agrega el SDK de ImageKit aislado en ImageKitService"
```

---

### Task 2: `FotoEstablecimiento`, migración V18 y derivaciones del marketplace

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/model/FotoEstablecimiento.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/model/Establecimiento.java:78-89`
- Create: `src/main/resources/db/migration/V18__file_id_fotos_establecimiento.sql`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoService.java:216`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoService.java:312`
- Modify: `src/test/java/com/matiasmeira/sacaladelangulo/publico/controller/ComplejoPublicoControllerDetalleNoTransactionalTest.java:83`

**Interfaces:**
- Consumes: nada de la Tarea 1.
- Produces:
  - `FotoEstablecimiento` con `getUrl()`, `getFileId()`, `setUrl(String)`, `setFileId(String)`, constructor `FotoEstablecimiento(String url, String fileId)` y `FotoEstablecimiento.builder().url(..).fileId(..).build()`.
  - `Establecimiento.getFotos()` → `List<FotoEstablecimiento>` (antes `List<String>`).

- [ ] **Step 1: Crear el `@Embeddable`**

`establecimiento/model/FotoEstablecimiento.java`:

```java
package com.matiasmeira.sacaladelangulo.establecimiento.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Una foto del establecimiento: la URL con la que se sirve y el fileId de ImageKit con el
 * que se la borra y se la identifica en los endpoints de gestión.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
public class FotoEstablecimiento {

    @Column(name = "foto_url", nullable = false, length = 1000)
    private String url;

    /**
     * Nullable a propósito: las fotos cargadas a mano contra la base antes de integrar
     * ImageKit no tienen fileId. Quedan visibles en el marketplace pero no se pueden
     * gestionar por API (no son direccionables) — ver el spec, "Decisiones descartadas".
     */
    @Column(name = "file_id", length = 255)
    private String fileId;
}
```

- [ ] **Step 2: Cambiar el tipo de `Establecimiento.fotos`**

En `Establecimiento.java`, reemplazar el bloque de `fotos` (líneas 78-89) por:

```java
    /**
     * Fotos del complejo, en el orden en que se muestran (la primera es la
     * "fotoPrincipal" de la card pública). @OrderColumn persiste ese orden explícitamente
     * (columna "orden"): sin ella Hibernate no garantiza qué foto es la primera al releer.
     * Se gestionan vía FotoEstablecimientoService (subida/borrado contra ImageKit).
     */
    @ElementCollection
    @CollectionTable(name = "establecimiento_fotos", joinColumns = @JoinColumn(name = "establecimiento_id"))
    @OrderColumn(name = "orden")
    @lombok.Builder.Default
    private java.util.List<FotoEstablecimiento> fotos = new java.util.ArrayList<>();
```

Se va el `@Column(name = "foto_url", nullable = false)` del campo: esa columna ahora la declara el `@Embeddable`.

- [ ] **Step 3: Escribir la migración V18**

`src/main/resources/db/migration/V18__file_id_fotos_establecimiento.sql`:

```sql
-- V18 — fileId de ImageKit en las fotos de establecimiento
--
-- establecimiento_fotos pasa de guardar solo la URL a guardar también el fileId que
-- devuelve ImageKit al subir: es la clave con la que después se borra el archivo y con la
-- que los endpoints de gestión identifican cada foto.
--
-- NULLABLE a propósito: las filas cargadas a mano antes de esta integración no tienen
-- fileId. Se siguen mostrando en el marketplace, pero no se pueden borrar ni reordenar
-- por API.
ALTER TABLE establecimiento_fotos ADD COLUMN file_id VARCHAR(255);

-- Único PARCIAL: el fileId es la clave de borrado y de reordenamiento, así que dos fotos
-- con el mismo fileId en un establecimiento serían ambiguas. Parcial (WHERE file_id IS
-- NOT NULL) porque las fotos legacy tienen NULL y varias NULL tienen que poder convivir
-- en el mismo establecimiento — un UNIQUE común lo permitiría en Postgres, pero dejarlo
-- explícito documenta la intención y evita depender de esa semántica.
CREATE UNIQUE INDEX uq_establecimiento_fotos_file_id
    ON establecimiento_fotos (establecimiento_id, file_id)
    WHERE file_id IS NOT NULL;
```

- [ ] **Step 4: Actualizar las dos derivaciones del marketplace**

En `ComplejoPublicoService.java`, línea 216:

```java
        String fotoPrincipal = establecimiento.getFotos().isEmpty()
                ? null
                : establecimiento.getFotos().get(0).getUrl();
```

En `ComplejoPublicoService.java`, línea 312 (el argumento `fotos` del `new ComplejoDetalleResponse(...)`), reemplazar `List.copyOf(establecimiento.getFotos())` por:

```java
                establecimiento.getFotos().stream().map(FotoEstablecimiento::getUrl).toList(),
```

Agregar el import `com.matiasmeira.sacaladelangulo.establecimiento.model.FotoEstablecimiento` si no está.

`ComplejoDetalleResponse.fotos` sigue siendo `List<String>` y `ComplejoCardResponse.fotoPrincipal` sigue siendo `String`: **el JSON público no cambia**. No tocar esos records.

- [ ] **Step 5: Arreglar el test que deja de compilar**

En `ComplejoPublicoControllerDetalleNoTransactionalTest.java:83`, cambiar:

```java
                .fotos(List.of("https://cdn.example.com/foto1.jpg"))
```

por:

```java
                .fotos(new java.util.ArrayList<>(List.of(FotoEstablecimiento.builder()
                        .url("https://cdn.example.com/foto1.jpg")
                        .fileId("file_seed_1")
                        .build())))
```

Agregar el import de `FotoEstablecimiento`. El assert de la línea 90 (`jsonPath("$.fotos[0]").value("https://cdn.example.com/foto1.jpg")`) **no cambia**: es justamente la prueba de que el contrato público sigue devolviendo strings.

Se usa `new ArrayList<>(...)` y no `List.of(...)` porque Hibernate necesita una lista mutable para una `@ElementCollection`.

- [ ] **Step 6: Compilar y correr los tests del marketplace**

Run: `./mvnw test -Dtest='ComplejoPublico*'`
Expected: PASS. Si algún otro test no compila por el cambio de tipo, arreglarlo con el mismo patrón del Step 5.

- [ ] **Step 7: Correr la suite completa**

Run: `./mvnw test`
Expected: PASS.

Nota: la V18 **no** se ejecuta en los tests (corren con `spring.flyway.enabled=false` y H2 `ddl-auto=create-drop`; el esquema lo genera Hibernate). Su correctitud se verifica leyéndola. El `CREATE UNIQUE INDEX ... WHERE` es sintaxis Postgres y no correría en H2 de todos modos.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/model/FotoEstablecimiento.java src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/model/Establecimiento.java src/main/resources/db/migration/V18__file_id_fotos_establecimiento.sql src/main/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoService.java src/test/java/com/matiasmeira/sacaladelangulo/publico/controller/ComplejoPublicoControllerDetalleNoTransactionalTest.java
git commit -m "feat(establecimientos): las fotos pasan a guardar el fileId de ImageKit ademas de la url"
```

---

### Task 3: `ValidadorFoto` — tipo real por magic bytes, tamaño y cantidad

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/service/ValidadorFoto.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/service/ValidadorFotoTest.java`

**Interfaces:**
- Consumes: nada.
- Produces:
  - `ValidadorFoto.validar(byte[] contenido, int cantidadDeFotosActuales)` → `void`, lanza `IllegalArgumentException` (que `GlobalExceptionHandler` ya mapea a **400**).
  - `ValidadorFoto.TAMANIO_MAXIMO_BYTES` = `5 * 1024 * 1024`
  - `ValidadorFoto.MAXIMO_FOTOS_POR_ESTABLECIMIENTO` = `10`

- [ ] **Step 1: Escribir el test (falla)**

`src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/service/ValidadorFotoTest.java`:

```java
package com.matiasmeira.sacaladelangulo.establecimiento.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ValidadorFoto - tipo real por magic bytes, tamaño y cantidad")
class ValidadorFotoTest {

    private final ValidadorFoto validadorFoto = new ValidadorFoto();

    /** Cabecera JPEG (FF D8 FF) seguida de relleno. */
    private static byte[] jpeg() {
        byte[] bytes = new byte[64];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    /** Cabecera PNG (89 50 4E 47 0D 0A 1A 0A) seguida de relleno. */
    private static byte[] png() {
        byte[] bytes = new byte[64];
        byte[] firma = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(firma, 0, bytes, 0, firma.length);
        return bytes;
    }

    /** "RIFF" en 0..3 y "WEBP" en 8..11, con el tamaño en el medio. */
    private static byte[] webp() {
        byte[] bytes = new byte[64];
        System.arraycopy("RIFF".getBytes(), 0, bytes, 0, 4);
        System.arraycopy("WEBP".getBytes(), 0, bytes, 8, 4);
        return bytes;
    }

    /** Cabecera de PDF: "%PDF-". */
    private static byte[] pdf() {
        byte[] bytes = new byte[64];
        System.arraycopy("%PDF-".getBytes(), 0, bytes, 0, 5);
        return bytes;
    }

    @Test
    @DisplayName("acepta_jpegPngYWebpValidos")
    void acepta_jpegPngYWebpValidos() {
        assertThatCode(() -> validadorFoto.validar(jpeg(), 0)).doesNotThrowAnyException();
        assertThatCode(() -> validadorFoto.validar(png(), 0)).doesNotThrowAnyException();
        assertThatCode(() -> validadorFoto.validar(webp(), 0)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rechaza_pdfDisfrazadoDeJpeg")
    void rechaza_pdfDisfrazadoDeJpeg() {
        // El nombre y el content-type declarado dirían "image/jpeg"; los bytes no mienten.
        assertThatThrownBy(() -> validadorFoto.validar(pdf(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("imagen");
    }

    @Test
    @DisplayName("rechaza_archivoVacio")
    void rechaza_archivoVacio() {
        assertThatThrownBy(() -> validadorFoto.validar(new byte[0], 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rechaza_archivoMasCortoQueLaFirma_sinIndexOutOfBounds")
    void rechaza_archivoMasCortoQueLaFirma_sinIndexOutOfBounds() {
        assertThatThrownBy(() -> validadorFoto.validar(new byte[]{(byte) 0xFF, (byte) 0xD8}, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rechaza_riffQueNoEsWebp")
    void rechaza_riffQueNoEsWebp() {
        // Un WAV también empieza con "RIFF": sin chequear también "WEBP" en 8..11 pasaría.
        byte[] wav = new byte[64];
        System.arraycopy("RIFF".getBytes(), 0, wav, 0, 4);
        System.arraycopy("WAVE".getBytes(), 0, wav, 8, 4);

        assertThatThrownBy(() -> validadorFoto.validar(wav, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rechaza_archivoMayorA5MB")
    void rechaza_archivoMayorA5MB() {
        byte[] gigante = new byte[ValidadorFoto.TAMANIO_MAXIMO_BYTES + 1];
        byte[] firma = jpeg();
        System.arraycopy(firma, 0, gigante, 0, 3);

        assertThatThrownBy(() -> validadorFoto.validar(gigante, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5");
    }

    @Test
    @DisplayName("rechaza_cuandoYaHayElMaximoDeFotos")
    void rechaza_cuandoYaHayElMaximoDeFotos() {
        assertThatThrownBy(() ->
                validadorFoto.validar(jpeg(), ValidadorFoto.MAXIMO_FOTOS_POR_ESTABLECIMIENTO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10");
    }

    @Test
    @DisplayName("acepta_laDecimaFoto")
    void acepta_laDecimaFoto() {
        assertThatCode(() ->
                validadorFoto.validar(jpeg(), ValidadorFoto.MAXIMO_FOTOS_POR_ESTABLECIMIENTO - 1))
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `./mvnw test -Dtest=ValidadorFotoTest`
Expected: FAIL de compilación — `ValidadorFoto` no existe.

- [ ] **Step 3: Implementar `ValidadorFoto`**

`establecimiento/service/ValidadorFoto.java`:

```java
package com.matiasmeira.sacaladelangulo.establecimiento.service;

import org.springframework.stereotype.Component;

/**
 * Valida el archivo de una foto de establecimiento ANTES de mandarlo a ImageKit.
 *
 * El tipo se determina por los magic bytes del contenido, NO por el content-type que
 * declara la parte multipart ni por la extensión del nombre: los dos los controla el
 * cliente y se falsifican trivialmente. Subir un archivo arbitrario a un CDN público bajo
 * la cuenta del negocio es exactamente lo que esta clase evita.
 */
@Component
public class ValidadorFoto {

    public static final int TAMANIO_MAXIMO_BYTES = 5 * 1024 * 1024;
    public static final int MAXIMO_FOTOS_POR_ESTABLECIMIENTO = 10;

    /** Mínimo de bytes para poder leer la firma más larga (PNG y WebP necesitan 12). */
    private static final int BYTES_MINIMOS = 12;

    public void validar(byte[] contenido, int cantidadDeFotosActuales) {
        if (contenido == null || contenido.length == 0) {
            throw new IllegalArgumentException("El archivo está vacío.");
        }
        if (contenido.length > TAMANIO_MAXIMO_BYTES) {
            throw new IllegalArgumentException("La imagen no puede superar los 5 MB.");
        }
        if (cantidadDeFotosActuales >= MAXIMO_FOTOS_POR_ESTABLECIMIENTO) {
            throw new IllegalArgumentException(
                    "El establecimiento ya tiene el máximo de 10 fotos. Borrá una antes de subir otra.");
        }
        if (contenido.length < BYTES_MINIMOS || !esImagenSoportada(contenido)) {
            throw new IllegalArgumentException("El archivo no es una imagen JPEG, PNG ni WebP.");
        }
    }

    private boolean esImagenSoportada(byte[] bytes) {
        return esJpeg(bytes) || esPng(bytes) || esWebp(bytes);
    }

    private boolean esJpeg(byte[] bytes) {
        return (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;
    }

    private boolean esPng(byte[] bytes) {
        byte[] firma = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        return empiezaCon(bytes, firma, 0);
    }

    /**
     * "RIFF" en 0..3 y "WEBP" en 8..11. Los 4 bytes del medio son el tamaño del archivo.
     * Hay que mirar los dos bloques: un WAV o un AVI también empiezan con "RIFF".
     */
    private boolean esWebp(byte[] bytes) {
        return empiezaCon(bytes, "RIFF".getBytes(), 0) && empiezaCon(bytes, "WEBP".getBytes(), 8);
    }

    private boolean empiezaCon(byte[] bytes, byte[] firma, int desde) {
        if (bytes.length < desde + firma.length) {
            return false;
        }
        for (int i = 0; i < firma.length; i++) {
            if (bytes[desde + i] != firma[i]) {
                return false;
            }
        }
        return true;
    }
}
```

- [ ] **Step 4: Correr el test y verificar que pasa**

Run: `./mvnw test -Dtest=ValidadorFotoTest`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/service/ValidadorFoto.java src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/service/ValidadorFotoTest.java
git commit -m "feat(establecimientos): agrega ValidadorFoto con deteccion de tipo por magic bytes"
```

---

### Task 4: `FotoEstablecimientoService` — listar, subir, borrar, reordenar

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/dto/FotoEstablecimientoResponse.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/dto/ReordenarFotosRequest.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/service/FotoEstablecimientoService.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/empleado/model/AccionAuditoria.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/service/FotoEstablecimientoServiceTest.java`

**Interfaces:**
- Consumes: `ImageKitService.subir/borrar`, `FotoSubida`, `ImageKitException` (Tarea 1); `FotoEstablecimiento`, `Establecimiento.getFotos()` (Tarea 2); `ValidadorFoto.validar` (Tarea 3).
- Produces:
  - `record FotoEstablecimientoResponse(String url, String fileId)`
  - `record ReordenarFotosRequest(List<String> fileIds)`
  - `FotoEstablecimientoService.listar(Long establecimientoId, String email)` → `List<FotoEstablecimientoResponse>`
  - `FotoEstablecimientoService.subir(Long establecimientoId, byte[] contenido, String nombreArchivo, String email)` → `FotoEstablecimientoResponse`
  - `FotoEstablecimientoService.borrar(Long establecimientoId, String fileId, String email)` → `void`
  - `FotoEstablecimientoService.reordenar(Long establecimientoId, List<String> fileIds, String email)` → `List<FotoEstablecimientoResponse>`
  - Valores nuevos de `AccionAuditoria`: `SUBIR_FOTO_ESTABLECIMIENTO`, `ELIMINAR_FOTO_ESTABLECIMIENTO`, `REORDENAR_FOTOS_ESTABLECIMIENTO`

- [ ] **Step 1: Crear los DTOs**

`establecimiento/dto/FotoEstablecimientoResponse.java`:

```java
package com.matiasmeira.sacaladelangulo.establecimiento.dto;

/**
 * Una foto tal como la ve el panel del dueño. Expone el fileId (a diferencia de la zona
 * pública, que solo manda URLs) porque es la clave con la que el panel pide borrar y
 * reordenar.
 */
public record FotoEstablecimientoResponse(String url, String fileId) {
}
```

`establecimiento/dto/ReordenarFotosRequest.java`:

```java
package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Nuevo orden de las fotos, por fileId. La primera de la lista pasa a ser la foto
 * principal de la card pública.
 */
public record ReordenarFotosRequest(
        @NotEmpty(message = "Hay que mandar la lista de fileIds en el nuevo orden.")
        List<String> fileIds
) {
}
```

- [ ] **Step 2: Agregar los valores de auditoría**

En `empleado/model/AccionAuditoria.java`, al final del enum, antes del `}`:

```java
    // Gestión de fotos del complejo por parte del dueño/admin (ver el spec de fotos con
    // ImageKit): tocan la cara pública del establecimiento en el marketplace.
    SUBIR_FOTO_ESTABLECIMIENTO,
    ELIMINAR_FOTO_ESTABLECIMIENTO,
    REORDENAR_FOTOS_ESTABLECIMIENTO
```

Agregar la coma al final del último valor existente (`ACTUALIZAR_CANCHA`). No hace falta migración: la columna `accion` es `VARCHAR(255)` sin CHECK constraint.

- [ ] **Step 3: Escribir el test (falla)**

`src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/service/FotoEstablecimientoServiceTest.java`:

```java
package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.imagekit.FotoSubida;
import com.matiasmeira.sacaladelangulo.core.imagekit.ImageKitException;
import com.matiasmeira.sacaladelangulo.core.imagekit.ImageKitService;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.FotoEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.FotoEstablecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FotoEstablecimientoService - gestión de fotos del complejo")
class FotoEstablecimientoServiceTest {

    private static final Long ESTABLECIMIENTO_ID = 7L;
    private static final String EMAIL_DUENO = "dueno@test.com";

    @Mock
    private EstablecimientoRepository establecimientoRepository;
    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;
    @Mock
    private ImageKitService imageKitService;
    @Mock
    private RegistroAuditoriaService registroAuditoriaService;

    private FotoEstablecimientoService servicio;
    private Establecimiento establecimiento;
    private Usuario dueno;

    @BeforeEach
    void setUp() {
        servicio = new FotoEstablecimientoService(
                establecimientoRepository,
                autorizacionEmpleadoService,
                imageKitService,
                new ValidadorFoto(),
                registroAuditoriaService);

        dueno = Usuario.builder().id(1L).email(EMAIL_DUENO).rol(Role.OWNER).build();
        establecimiento = Establecimiento.builder()
                .id(ESTABLECIMIENTO_ID)
                .nombre("Complejo Test")
                .dueno(dueno)
                .fotos(new ArrayList<>())
                .build();

        when(establecimientoRepository.findById(ESTABLECIMIENTO_ID)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(any(), anyString())).thenReturn(dueno);
    }

    private static byte[] jpeg() {
        byte[] bytes = new byte[64];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    private static FotoEstablecimiento foto(String fileId) {
        return FotoEstablecimiento.builder()
                .url("https://ik.imagekit.io/demo/" + fileId + ".jpg")
                .fileId(fileId)
                .build();
    }

    @Test
    @DisplayName("subir_agregaLaFotoAlFinal_conUrlYFileId")
    void subir_agregaLaFotoAlFinal_conUrlYFileId() {
        establecimiento.getFotos().add(foto("file_1"));
        when(imageKitService.subir(any(), anyString(), anyString()))
                .thenReturn(new FotoSubida("https://ik.imagekit.io/demo/nueva.jpg", "file_nueva"));

        FotoEstablecimientoResponse resultado =
                servicio.subir(ESTABLECIMIENTO_ID, jpeg(), "nueva.jpg", EMAIL_DUENO);

        assertThat(resultado.url()).isEqualTo("https://ik.imagekit.io/demo/nueva.jpg");
        assertThat(resultado.fileId()).isEqualTo("file_nueva");
        assertThat(establecimiento.getFotos()).hasSize(2);
        assertThat(establecimiento.getFotos().get(1).getFileId()).isEqualTo("file_nueva");
        verify(imageKitService).subir(any(), eq("nueva.jpg"), eq("/establecimientos/7/"));
        verify(registroAuditoriaService).registrarSobreEstablecimiento(
                eq(dueno), eq(establecimiento), eq(AccionAuditoria.SUBIR_FOTO_ESTABLECIMIENTO), anyLong(), anyString());
    }

    @Test
    @DisplayName("subir_aEstablecimientoAjeno_lanzaAccessDenied_ySinTocarImageKit")
    void subir_aEstablecimientoAjeno_lanzaAccessDenied_ySinTocarImageKit() {
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(any(), anyString()))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThatThrownBy(() -> servicio.subir(ESTABLECIMIENTO_ID, jpeg(), "x.jpg", "otro@test.com"))
                .isInstanceOf(AccessDeniedException.class);

        verify(imageKitService, never()).subir(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("subir_archivoQueNoEsImagen_lanza400_ySinTocarImageKit")
    void subir_archivoQueNoEsImagen_lanza400_ySinTocarImageKit() {
        byte[] pdf = new byte[64];
        System.arraycopy("%PDF-".getBytes(), 0, pdf, 0, 5);

        assertThatThrownBy(() -> servicio.subir(ESTABLECIMIENTO_ID, pdf, "trampa.jpg", EMAIL_DUENO))
                .isInstanceOf(IllegalArgumentException.class);

        verify(imageKitService, never()).subir(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("subir_cuandoYaHay10Fotos_lanza400")
    void subir_cuandoYaHay10Fotos_lanza400() {
        for (int i = 0; i < ValidadorFoto.MAXIMO_FOTOS_POR_ESTABLECIMIENTO; i++) {
            establecimiento.getFotos().add(foto("file_" + i));
        }

        assertThatThrownBy(() -> servicio.subir(ESTABLECIMIENTO_ID, jpeg(), "onceava.jpg", EMAIL_DUENO))
                .isInstanceOf(IllegalArgumentException.class);

        verify(imageKitService, never()).subir(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("borrar_llamaAImageKit_ySacaLaFotoDeLaLista")
    void borrar_llamaAImageKit_ySacaLaFotoDeLaLista() {
        establecimiento.getFotos().add(foto("file_1"));
        establecimiento.getFotos().add(foto("file_2"));

        servicio.borrar(ESTABLECIMIENTO_ID, "file_1", EMAIL_DUENO);

        verify(imageKitService).borrar("file_1");
        assertThat(establecimiento.getFotos()).hasSize(1);
        assertThat(establecimiento.getFotos().get(0).getFileId()).isEqualTo("file_2");
    }

    @Test
    @DisplayName("borrar_siImageKitFalla_igualSacaLaFotoDeLaLista")
    void borrar_siImageKitFalla_igualSacaLaFotoDeLaLista() {
        establecimiento.getFotos().add(foto("file_1"));
        doThrow(new ImageKitException("500 de ImageKit")).when(imageKitService).borrar("file_1");

        servicio.borrar(ESTABLECIMIENTO_ID, "file_1", EMAIL_DUENO);

        assertThat(establecimiento.getFotos()).isEmpty();
    }

    @Test
    @DisplayName("borrar_fileIdInexistente_lanzaEntityNotFound")
    void borrar_fileIdInexistente_lanzaEntityNotFound() {
        establecimiento.getFotos().add(foto("file_1"));

        assertThatThrownBy(() -> servicio.borrar(ESTABLECIMIENTO_ID, "file_no_existe", EMAIL_DUENO))
                .isInstanceOf(com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException.class);

        verify(imageKitService, never()).borrar(anyString());
    }

    @Test
    @DisplayName("reordenar_cambiaCualEsLaFotoPrincipal")
    void reordenar_cambiaCualEsLaFotoPrincipal() {
        establecimiento.getFotos().add(foto("file_1"));
        establecimiento.getFotos().add(foto("file_2"));
        establecimiento.getFotos().add(foto("file_3"));

        List<FotoEstablecimientoResponse> resultado =
                servicio.reordenar(ESTABLECIMIENTO_ID, List.of("file_3", "file_1", "file_2"), EMAIL_DUENO);

        assertThat(establecimiento.getFotos().get(0).getFileId()).isEqualTo("file_3");
        assertThat(resultado.get(0).fileId()).isEqualTo("file_3");
        assertThat(resultado).hasSize(3);
    }

    @Test
    @DisplayName("reordenar_conFileIdsQueNoSonPermutacionExacta_lanza400")
    void reordenar_conFileIdsQueNoSonPermutacionExacta_lanza400() {
        establecimiento.getFotos().add(foto("file_1"));
        establecimiento.getFotos().add(foto("file_2"));

        // Falta uno
        assertThatThrownBy(() -> servicio.reordenar(ESTABLECIMIENTO_ID, List.of("file_1"), EMAIL_DUENO))
                .isInstanceOf(IllegalArgumentException.class);

        // Sobra uno que no existe
        assertThatThrownBy(() -> servicio.reordenar(
                ESTABLECIMIENTO_ID, List.of("file_1", "file_2", "file_9"), EMAIL_DUENO))
                .isInstanceOf(IllegalArgumentException.class);

        // Repetido
        assertThatThrownBy(() -> servicio.reordenar(
                ESTABLECIMIENTO_ID, List.of("file_1", "file_1"), EMAIL_DUENO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reordenar_conFotoLegacySinFileId_lanza400")
    void reordenar_conFotoLegacySinFileId_lanza400() {
        establecimiento.getFotos().add(FotoEstablecimiento.builder()
                .url("https://cdn.viejo.com/a.jpg")
                .fileId(null)
                .build());
        establecimiento.getFotos().add(foto("file_2"));

        assertThatThrownBy(() -> servicio.reordenar(ESTABLECIMIENTO_ID, List.of("file_2"), EMAIL_DUENO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("listar_devuelveLasFotosEnOrden")
    void listar_devuelveLasFotosEnOrden() {
        establecimiento.getFotos().add(foto("file_1"));
        establecimiento.getFotos().add(foto("file_2"));

        List<FotoEstablecimientoResponse> resultado = servicio.listar(ESTABLECIMIENTO_ID, EMAIL_DUENO);

        assertThat(resultado).extracting(FotoEstablecimientoResponse::fileId)
                .containsExactly("file_1", "file_2");
    }
}
```

- [ ] **Step 4: Correr el test y verificar que falla**

Run: `./mvnw test -Dtest=FotoEstablecimientoServiceTest`
Expected: FAIL de compilación — `FotoEstablecimientoService` no existe.

- [ ] **Step 5: Implementar el servicio**

`establecimiento/service/FotoEstablecimientoService.java`:

```java
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
```

- [ ] **Step 6: Correr el test y verificar que pasa**

Run: `./mvnw test -Dtest=FotoEstablecimientoServiceTest`
Expected: PASS, 11 tests.

- [ ] **Step 7: Correr la suite completa**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/dto/FotoEstablecimientoResponse.java src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/dto/ReordenarFotosRequest.java src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/service/FotoEstablecimientoService.java src/main/java/com/matiasmeira/sacaladelangulo/empleado/model/AccionAuditoria.java src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/service/FotoEstablecimientoServiceTest.java
git commit -m "feat(establecimientos): agrega FotoEstablecimientoService con subida, borrado y reorden"
```

---

### Task 5: `FotoEstablecimientoController` y test de integración

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/controller/FotoEstablecimientoController.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/core/exception/GlobalExceptionHandler.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/controller/FotoEstablecimientoControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `FotoEstablecimientoService.listar/subir/borrar/reordenar`, `FotoEstablecimientoResponse`, `ReordenarFotosRequest` (Tarea 4); `ImageKitService` (Tarea 1, mockeado en el test).
- Produces: los cuatro endpoints bajo `/api/v1/establecimientos/{id}/fotos`.

- [ ] **Step 1: Implementar el controller**

`establecimiento/controller/FotoEstablecimientoController.java`:

```java
package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.FotoEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.ReordenarFotosRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.service.FotoEstablecimientoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Gestión de las fotos de un establecimiento. Sub-recurso propio y no métodos más de
 * EstablecimientoController: el ciclo de vida de las fotos (multipart, ImageKit, orden)
 * no tiene nada que ver con el alta y edición del establecimiento.
 *
 * @PreAuthorize filtra por rol; la validación de que ESTE establecimiento sea del usuario
 * la hace el servicio con validarPropietarioOAdmin.
 */
@RestController
@RequestMapping("/api/v1/establecimientos/{id}/fotos")
@RequiredArgsConstructor
public class FotoEstablecimientoController {

    private final FotoEstablecimientoService fotoEstablecimientoService;

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<List<FotoEstablecimientoResponse>> listar(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(fotoEstablecimientoService.listar(id, userDetails.getUsername()));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<FotoEstablecimientoResponse> subir(
            @PathVariable Long id,
            @RequestParam("archivo") MultipartFile archivo,
            @AuthenticationPrincipal UserDetails userDetails) throws IOException {
        byte[] contenido = archivo.getBytes();
        String nombreArchivo = archivo.getOriginalFilename() == null
                ? "foto"
                : archivo.getOriginalFilename();

        FotoEstablecimientoResponse foto =
                fotoEstablecimientoService.subir(id, contenido, nombreArchivo, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(foto);
    }

    @DeleteMapping("/{fileId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<Void> borrar(
            @PathVariable Long id,
            @PathVariable String fileId,
            @AuthenticationPrincipal UserDetails userDetails) {
        fotoEstablecimientoService.borrar(id, fileId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/orden")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<List<FotoEstablecimientoResponse>> reordenar(
            @PathVariable Long id,
            @RequestBody @Valid ReordenarFotosRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(
                fotoEstablecimientoService.reordenar(id, request.fileIds(), userDetails.getUsername()));
    }
}
```

- [ ] **Step 2: Agregar la red de contención del tamaño**

En `GlobalExceptionHandler`, con la misma forma de cuerpo que los handlers vecinos:

```java
/**
 * El contenedor corta la request antes de que llegue al controller cuando supera
 * spring.servlet.multipart.max-file-size. Sin este handler saldría un 500. El límite
 * real del negocio (5MB) lo aplica ValidadorFoto con un mensaje propio; esto es sólo
 * la red de abajo.
 */
@ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
public ResponseEntity<Map<String, String>> handleMaxUploadSizeExceeded(
        org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", "El archivo es demasiado grande. El máximo es 5 MB."));
}
```

- [ ] **Step 3: Escribir el test de integración (falla)**

`src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/controller/FotoEstablecimientoControllerIntegrationTest.java`:

Mismo patrón que `EstablecimientoControllerServiciosIntegrationTest`: MockMvc + JWT real + H2 con su propio nombre de base (`testdb-fotos-establecimiento`, para no compartir esquema con otros tests). `jwtService.generateToken(UsuarioUserDetailsMapper.map(usuario))` es la firma real, ya verificada — es la que usan `AdminUsuarioControllerTest`, `UsuarioControllerMeTest` y `UsuarioControllerEliminarMeTest`.

```java
package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.auth.service.JwtService;
import com.matiasmeira.sacaladelangulo.auth.service.UsuarioUserDetailsMapper;
import com.matiasmeira.sacaladelangulo.core.imagekit.FotoSubida;
import com.matiasmeira.sacaladelangulo.core.imagekit.ImageKitService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.ReordenarFotosRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.FotoEstablecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-fotos-establecimiento;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("/api/v1/establecimientos/{id}/fotos")
class FotoEstablecimientoControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private EstablecimientoRepository establecimientoRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Se mockea el borde externo y nada más: el resto del camino (security, multipart,
     * validación, JPA) corre de verdad. Sin esto el test pegaría contra ImageKit.
     */
    @MockitoBean
    private ImageKitService imageKitService;

    private static byte[] jpeg() {
        byte[] bytes = new byte[64];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    private Usuario seedDueno(String email) {
        return usuarioRepository.save(Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
    }

    private Establecimiento seedEstablecimiento(Usuario dueno, String slug, List<FotoEstablecimiento> fotos) {
        return establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo " + slug)
                .direccion("Calle 1")
                .slug(slug)
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .fotos(new ArrayList<>(fotos))
                .build());
    }

    private String tokenDe(Usuario usuario) {
        return jwtService.generateToken(UsuarioUserDetailsMapper.map(usuario));
    }

    @Test
    @DisplayName("POST_comoDueno_devuelve201ConUrlYFileId")
    void post_comoDueno_devuelve201ConUrlYFileId() throws Exception {
        Usuario dueno = seedDueno("dueno-fotos-ok@test.com");
        Establecimiento establecimiento = seedEstablecimiento(dueno, "complejo-fotos-ok", List.of());
        when(imageKitService.subir(any(), anyString(), anyString()))
                .thenReturn(new FotoSubida("https://ik.imagekit.io/demo/nueva.jpg", "file_nueva"));

        mockMvc.perform(multipart("/api/v1/establecimientos/" + establecimiento.getId() + "/fotos")
                        .file(new MockMultipartFile("archivo", "foto.jpg", "image/jpeg", jpeg()))
                        .header("Authorization", "Bearer " + tokenDe(dueno)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value("https://ik.imagekit.io/demo/nueva.jpg"))
                .andExpect(jsonPath("$.fileId").value("file_nueva"));
    }

    @Test
    @DisplayName("POST_aEstablecimientoAjeno_devuelve403")
    void post_aEstablecimientoAjeno_devuelve403() throws Exception {
        Usuario dueno = seedDueno("dueno-fotos-propio@test.com");
        Usuario intruso = seedDueno("dueno-fotos-intruso@test.com");
        Establecimiento ajeno = seedEstablecimiento(dueno, "complejo-fotos-ajeno", List.of());

        mockMvc.perform(multipart("/api/v1/establecimientos/" + ajeno.getId() + "/fotos")
                        .file(new MockMultipartFile("archivo", "foto.jpg", "image/jpeg", jpeg()))
                        .header("Authorization", "Bearer " + tokenDe(intruso)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST_conPdfDisfrazadoDeJpeg_devuelve400")
    void post_conPdfDisfrazadoDeJpeg_devuelve400() throws Exception {
        Usuario dueno = seedDueno("dueno-fotos-pdf@test.com");
        Establecimiento establecimiento = seedEstablecimiento(dueno, "complejo-fotos-pdf", List.of());
        byte[] pdf = new byte[64];
        System.arraycopy("%PDF-".getBytes(), 0, pdf, 0, 5);

        mockMvc.perform(multipart("/api/v1/establecimientos/" + establecimiento.getId() + "/fotos")
                        .file(new MockMultipartFile("archivo", "trampa.jpg", "image/jpeg", pdf))
                        .header("Authorization", "Bearer " + tokenDe(dueno)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT_orden_cambiaLaFotoPrincipal")
    void put_orden_cambiaLaFotoPrincipal() throws Exception {
        Usuario dueno = seedDueno("dueno-fotos-orden@test.com");
        Establecimiento establecimiento = seedEstablecimiento(dueno, "complejo-fotos-orden", List.of(
                FotoEstablecimiento.builder().url("https://ik.io/a.jpg").fileId("file_a").build(),
                FotoEstablecimiento.builder().url("https://ik.io/b.jpg").fileId("file_b").build()));

        mockMvc.perform(put("/api/v1/establecimientos/" + establecimiento.getId() + "/fotos/orden")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReordenarFotosRequest(List.of("file_b", "file_a"))))
                        .header("Authorization", "Bearer " + tokenDe(dueno)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileId").value("file_b"));
    }
}
```

- [ ] **Step 4: Correr el test y verificar que falla**

Run: `./mvnw test -Dtest=FotoEstablecimientoControllerIntegrationTest`
Expected: como el controller ya se implementó en el Step 1, este test tiene que pasar directo. Si falla, el error está en el controller o en el servicio, no en el test.

- [ ] **Step 5: Correr el test y verificar que pasa**

Run: `./mvnw test -Dtest=FotoEstablecimientoControllerIntegrationTest`
Expected: PASS, 4 tests.

- [ ] **Step 6: Correr la suite completa**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/controller/FotoEstablecimientoController.java src/main/java/com/matiasmeira/sacaladelangulo/core/exception/GlobalExceptionHandler.java src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/controller/FotoEstablecimientoControllerIntegrationTest.java
git commit -m "feat(establecimientos): expone los endpoints de fotos bajo /establecimientos/{id}/fotos"
```

---

### Task 6: Idempotencia en la subida

**Files:**
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/core/idempotencia/IdempotencyFilter.java`
- Modify: `src/test/java/com/matiasmeira/sacaladelangulo/core/RutasProtegidasCoincidenConControllersTest.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/controller/FotoEstablecimientoControllerIntegrationTest.java` (agregar tests)

**Interfaces:**
- Consumes: los endpoints de la Tarea 5.
- Produces: `IdempotencyFilter.PATRONES_PROTEGIDOS` → `Set<String>`.

**Contexto crítico:** `IdempotencyFilter` protege hoy las reservas y las ventas de buffet — el código más sensible de la app. Los cambios de esta tarea son **aditivos**: `RUTAS_PROTEGIDAS` y su matcheo por igualdad exacta **no se tocan**. No refactorizar nada de paso.

- [ ] **Step 1: Averiguar PRIMERO si el multipart realmente se rompe**

Antes de tocar el filtro, agregar este test a `FotoEstablecimientoControllerIntegrationTest`:

```java
    @Test
    @DisplayName("POST_conIdempotencyKey_elArchivoLlegaCompleto")
    void post_conIdempotencyKey_elArchivoLlegaCompleto() throws Exception {
        Usuario dueno = seedDueno("dueno-fotos-idem@test.com");
        Establecimiento establecimiento = seedEstablecimiento(dueno, "complejo-fotos-idem", List.of());
        when(imageKitService.subir(any(), anyString(), anyString()))
                .thenReturn(new FotoSubida("https://ik.imagekit.io/demo/idem.jpg", "file_idem"));

        mockMvc.perform(multipart("/api/v1/establecimientos/" + establecimiento.getId() + "/fotos")
                        .file(new MockMultipartFile("archivo", "foto.jpg", "image/jpeg", jpeg()))
                        .header("Authorization", "Bearer " + tokenDe(dueno))
                        .header("Idempotency-Key", "clave-de-prueba-1"))
                .andExpect(status().isCreated());
    }
```

Run: `./mvnw test -Dtest=FotoEstablecimientoControllerIntegrationTest#post_conIdempotencyKey_elArchivoLlegaCompleto`

**Este test pasa hoy aunque el filtro rompa el multipart**, porque la ruta todavía no está protegida — sirve como línea de base. Registrar el resultado y seguir al Step 2.

- [ ] **Step 2: Agregar el set de patrones al filtro**

En `IdempotencyFilter`, debajo de `RUTAS_PROTEGIDAS`:

```java
    /**
     * Rutas protegidas que llevan un id adentro, y por eso NO se pueden matchear por
     * igualdad de string como RUTAS_PROTEGIDAS. Se guardan como patrón y se matchean con
     * PathPattern. Separado y no fusionado con el set de arriba a propósito: el matcheo
     * exacto de las 4 rutas de reserva/venta es más barato y ya está probado, y no se
     * cambia su comportamiento por agregar esto.
     *
     * Público por el mismo motivo que RUTAS_PROTEGIDAS: lo verifica
     * RutasProtegidasCoincidenConControllersTest.
     */
    public static final Set<String> PATRONES_PROTEGIDOS = Set.of(
            "/api/v1/establecimientos/{id}/fotos"
    );

    private static final PathPatternParser PARSER = new PathPatternParser();
    private static final List<PathPattern> PATRONES_COMPILADOS = PATRONES_PROTEGIDOS.stream()
            .map(PARSER::parse)
            .toList();
```

Imports: `org.springframework.web.util.pattern.PathPattern`, `org.springframework.web.util.pattern.PathPatternParser`, `org.springframework.http.server.PathContainer`, `java.util.List`.

Y cambiar `aplicaAEstaRuta`:

```java
    private boolean aplicaAEstaRuta(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        if (RUTAS_PROTEGIDAS.contains(uri)) {
            return true;
        }
        PathContainer path = PathContainer.parsePath(uri);
        return PATRONES_COMPILADOS.stream().anyMatch(patron -> patron.matches(path));
    }
```

- [ ] **Step 3: Correr el test de idempotencia y ver si el multipart se rompe**

Run: `./mvnw test -Dtest=FotoEstablecimientoControllerIntegrationTest#post_conIdempotencyKey_elArchivoLlegaCompleto`

Ahora la ruta SÍ está protegida y el filtro drena el body.

- **Si PASA**: el multipart no se rompe. **Saltear el Step 4** y anotarlo en el commit.
- **Si FALLA** (archivo vacío, `IllegalArgumentException` de validación, o error de parts): confirmado el diagnóstico. Ir al Step 4.

- [ ] **Step 4: (solo si el Step 3 falló) Saltear el hasheo del body en multipart**

En `doFilterInternal`, reemplazar:

```java
        byte[] cuerpoBytes = request.getInputStream().readAllBytes();
        HttpServletRequest requestConCuerpoCacheado = new CachedBodyHttpServletRequest(request, cuerpoBytes);
        String hashCuerpo = calcularHash(cuerpoBytes);
```

por:

```java
        // En multipart NO se toca el input stream. CachedBodyHttpServletRequest sólo
        // overridea getInputStream()/getReader(); getParts() delega al request original de
        // Tomcat, así que drenar el stream acá le deja el archivo vacío al controller.
        // El precio es perder la detección de "misma clave con otro payload" (el 422) para
        // multipart: la idempotencia en sí, que es no repetir el efecto, sigue intacta.
        boolean esMultipart = request.getContentType() != null
                && request.getContentType().toLowerCase().startsWith("multipart/");
        byte[] cuerpoBytes = esMultipart ? new byte[0] : request.getInputStream().readAllBytes();
        HttpServletRequest requestConCuerpoCacheado = esMultipart
                ? request
                : new CachedBodyHttpServletRequest(request, cuerpoBytes);
        String hashCuerpo = calcularHash(cuerpoBytes);
```

Run: `./mvnw test -Dtest=FotoEstablecimientoControllerIntegrationTest`
Expected: PASS.

- [ ] **Step 5: Extender el test guardián de rutas**

En `RutasProtegidasCoincidenConControllersTest`, agregar:

```java
    @Test
    @DisplayName("patronesDeIdempotencyFilter_MapeanAUnPostMappingReal")
    void patronesDeIdempotencyFilter_MapeanAUnPostMappingReal() {
        IdempotencyFilter.PATRONES_PROTEGIDOS.forEach(this::assertRutaTienePostMapping);
    }
```

No hay que tocar `assertRutaTienePostMapping` ni `esPostMappingDeEsaRuta`: comparan contra `patron.getPatternString()`, que para este mapping es exactamente `/api/v1/establecimientos/{id}/fotos`.

Run: `./mvnw test -Dtest=RutasProtegidasCoincidenConControllersTest`
Expected: PASS, 3 tests.

- [ ] **Step 6: Verificar que no se rompieron reservas ni ventas**

Run: `./mvnw test -Dtest=IdempotencyFilterTest`
Expected: PASS — los tests existentes del filtro siguen verdes, que es la prueba de que el cambio fue aditivo.

- [ ] **Step 7: Correr la suite completa**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/core/idempotencia/IdempotencyFilter.java src/test/java/com/matiasmeira/sacaladelangulo/core/RutasProtegidasCoincidenConControllersTest.java src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/controller/FotoEstablecimientoControllerIntegrationTest.java
git commit -m "feat(idempotencia): protege la subida de fotos matcheando rutas con id por patron"
```

---

## Cierre

- [ ] **Correr la suite completa una última vez**

Run: `./mvnw test`
Expected: PASS, sin tests salteados que antes corrían.

- [ ] **Verificar que no quedó nada sin commitear de esta feature**

Run: `git status --short`
Expected: la única modificación pendiente debe ser `src/test/java/.../publico/controller/ComplejoPublicoControllerIntegrationTest.java`, que **es trabajo previo del usuario y no se toca**.
