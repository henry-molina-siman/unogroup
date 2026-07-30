
# Implementación — Sistema de Ensambles (Orquestador + UnoGroup)

**Proyecto:** HUENSA-001 — Integración de Pedidos que Requieren Ensamble
**Documento:** Detalle técnico de implementación de la aplicación: estructura de repositorios, estructura de paquetes, DDL, entidades JPA, configuración Spring Boot, contrato OpenAPI. Describe el **estado actual del código**, tal como está construido hoy. **La infraestructura (Terraform, GKE, Cloud SQL, Pub/Sub, Secret Manager) se documenta por separado** — ver nota en §7.
**Documento complementario:** `HUENSA-001_Diseno_Requerimientos_Modulo_Integracion_Ensamble.md` — principios, arquitectura de los dos microservicios, casos de uso, mapeo de campos y contrato OpenAPI. Léelo primero si buscas el *por qué* de una decisión; este documento es el *cómo*.
**Stack:** Java 21 · Spring Boot 4.x · MySQL 8.0+ (Cloud SQL, instancia ya existente) · GKE (clúster ya existente) · GCP Pub/Sub · OpenFeign · Lombok · Bean Validation · SLF4J

> **Historial de cambios, hallazgos de auditoría de código y trabajo técnico pendiente:** ver `HUENSA-001_Implementacion_Bitacora_Decisiones_Modulo_Integracion_Ensamble.md`. Este documento solo describe el estado actual del código; no lleva changelog ni anotaciones de sesión. Las secciones marcadas ⚠ **TBD** son piezas que ya sabemos que faltan pero todavía no se han cerrado en detalle — su historial y contexto vive en esa bitácora.

**Nomenclatura de tablas:** la instancia/schema de Cloud SQL donde vive este módulo se comparte con otros sistemas de Siman, no es exclusiva del módulo de ensambles. Las tres tablas (y sus constraints/índices/trigger) adoptan el prefijo `ensamble_` para evitar colisiones de nombre — ver DDL en §2 y `@Table(name = ...)` en las entidades JPA de §3 (ver Diseño §2.6 para el detalle de la decisión).

**Repositorios:** `orquestador-app` y `unogroup-app` viven en dos repositorios Git independientes, sin `pom.xml` padre ni módulo común entre ellos (ver §1.1).

---

## Índice

1. [Arquitectura de Repositorios](#1-arquitectura-de-repositorios)
   - 1.1 Repositorios Git y estructura de proyecto
   - 1.2 Estructura de paquetes — `orquestador-app`
   - 1.3 Estructura de paquetes — `unogroup-app`
   - 1.4 Responsabilidad de cada capa
2. [DDL MySQL](#2-ddl-mysql) — modelo de persistencia (`orquestador-app`)
3. [Entidades JPA](#3-entidades-jpa) — `orquestador-app`
4. `application.yml` — `orquestador-app`
5. `application.yml` — `unogroup-app`
6. Dependencias Maven (un `pom.xml` independiente por repositorio)
   - 6.1 `orquestador-app/pom.xml`
   - 6.2 `unogroup-app/pom.xml`
7. Contenerización y despliegue en Kubernetes
   - 7.1 Dockerfile
   - 7.2 Kubernetes — `orquestador-app`
   - 7.3 Kubernetes — `unogroup-app`
8. Infraestructura Terraform — nota de alcance
9. Trabajo pendiente

---

# 1. Arquitectura de Repositorios

## 1.1 Repositorios Git y estructura de proyecto

**`orquestador-app` y `unogroup-app` son dos proyectos independientes, cada uno con su propio repositorio Git.** No hay un `pom.xml` padre agregador, ni módulo Maven común entre ellos: cada repositorio contiene un único proyecto Maven `jar`, autocontenido, con su propio historial de commits, versionado (tags/releases) y pipeline de CI/CD. Las entidades JPA viven únicamente en `orquestador-app` (el único que las usa, ver §3); los enums de dominio se definen localmente en cada app, con los mismos valores — la consistencia entre ambos la garantiza el contrato OpenAPI (`HUENSA-001_openapi_V3.yaml`, que ya tiene esos valores como `enum:` en sus schemas), no una dependencia de build compartida.

**Repositorio `orquestador-app`** (git remote propio):

```
orquestador-app/
├── pom.xml            # standalone, sin parent interno — ver §6.1
├── src/
├── Dockerfile
└── k8s/
    ├── deployment.yaml
    └── service.yaml
```

**Repositorio `unogroup-app`** (git remote propio):

```
unogroup-app/
├── pom.xml            # standalone, sin parent interno — ver §6.2
├── src/
├── Dockerfile
└── k8s/               # Kustomize — estructura completa en §7.3
    ├── base/
    └── overlays/
```

**Dónde se refleja esto en el resto del documento:**
- **§6 (Dependencias Maven):** no hay `pom.xml` padre. Cada proyecto declara su propio `<parent>` apuntando directamente a `spring-boot-starter-parent`, e importa el BOM de Spring Cloud en su propio `dependencyManagement`. Ver §6.1 y §6.2.
- **§7.1 (Dockerfile):** el build no copia un `pom.xml` padre ni usa `-pl` (build de un módulo dentro de un reactor) — cada `Dockerfile` vive en la raíz de su propio repositorio y compila un proyecto Maven normal, de un solo módulo.
- **CI/CD:** cada repositorio tiene su propio pipeline (build, test, imagen de contenedor, tag) — versiones de imagen independientes para `orquestador-app` y `unogroup-app`.
- **Consistencia de contrato:** la fuente de verdad entre ambos es el contrato OpenAPI (`HUENSA-001_openapi_V3.yaml`), nunca una dependencia de build compartida.

## 1.2 Estructura de paquetes — `orquestador-app`

```
com.siman.ensambles.orquestador
├── messaging/               # consumer de POST /internal/eventos: decodifica el envelope,
│   │                        # lee attributes.origen/tipo_evento (flujo solo aplica para
│   │                        # origen=guias — ver Diseño §2.5), rutea al mapper correspondiente
│   └── mapper/               # WmsEventoMapper (WMS — extrae whseId+externOrderKey del disparador,
│                              # sea EventoWmsCrear, WmsUP05Payload o WmsUP06Payload; para UP06
│                              # itera Head[] y produce una extracción por cada orden del lote) y
│                              # EventoGuiasMapper (Guías — CARM/TARM/DARM comparten un único
│                              # schema/mapper; 1 evento → N entidades, iterando `items[]`) —
│                              # EventoAsseMapper/EventoEnsaMapper (versión anterior) quedan
│                              # retirados — asumían que el evento ya traía los datos de negocio
├── controller/               # GET /solicitudes/{ordenId} — trazabilidad (público)
│                              # POST /internal/orquestador/solicitudes/resultado — recibe
│                              # el callback de unogroup-app (ver §1.4.4b)
├── service/                  # idempotencia (ordenId+sku), fan-out de updates sin sku,
│                              # transición de estado interno hasta ENRIQUECIDA, y también
│                              # la transición final al recibir el callback
├── enrichment/                # Paso central y obligatorio para WMS: WmsShipmentClient
│                              # consulta GET /wms/dw/v1/shipment/get-shipment/{whseId}/
│                              # {externOrderKey} tanto en creación como en actualización;
│                              # filtra orderdetails[] por ext_udf_str10 no nulo para decidir
│                              # qué líneas generan sub-orden y con qué flujo (ASSE/ENSA) cada
│                              # una. Para Guías, resuelve los campos marcados
│                              # "consulta: RMS" en el Diseño §4.3 (item_brand/category/description)
├── client/                    # @FeignClient hacia unogroup-app — POST /internal/unogroup/solicitudes
│                              # con el payload_enriquecido completo (no una referencia)
├── domain/                     # entidades JPA (SolicitudEnsamble, SolicitudHistorial,
│   │                          # BitacoraPartner) y enums de dominio (EstadoInterno,
│   │                          # TrackingStatus, FlujoEnsamble) — único módulo que las usa,
│   │                          # `unogroup-app` no tiene acceso a base de datos y define
│   │                          # su propia copia local de los enums (ver §1.1).
│   └── enums/
├── repository/                 # Spring Data JPA sobre las entidades de domain/
│                              # (findByOrdenIdAndSku, findByOrdenId — fan-out). Único de los
│                              # dos módulos con acceso a base de datos.
├── reconciliation/            # job periódico (ver Diseño §2.11) — filas atascadas en
│                              # RECIBIDA, ENRIQUECIDA sin notificar, o notificada sin callback
└── config/
```

## 1.3 Estructura de paquetes — `unogroup-app`

`unogroup-app` no tiene paquete `repository/` — no accede a base de datos (ver Diseño §2.3/§2.4). Tiene, en cambio, `callback/` para reportar el resultado de vuelta al Orquestador. La comunicación con Solution One vive directo en `client/`/`dto`/`mapper`, sin anidarla bajo un paquete "adapter": con UnoGroup sin ninguna otra responsabilidad de negocio (todo su código existe únicamente para hablarle a Solution One), UnoGroup **es** el adapter — no lo contiene. Si en el futuro se reemplaza UnoGroup por otro partner, no se cambia un adapter interno: se construye otro microservicio.

```
com.siman.ensambles.unogroup
├── controller/                # POST /internal/unogroup/solicitudes — recibe la notificación
│                              # del Orquestador, con el payload_enriquecido completo en el
│                              # body (endpoint interno, nunca expuesto fuera del clúster)
├── service/                   # orquesta traducción + envío + reintento síncrono a partir del
│                              # payload recibido (ver Diseño §2.11 — sin scheduler, sin
│                              # tabla de polling, sin lectura de base de datos)
├── client/                     # SolutionOneClient — @FeignClient, GET token, POST upload
│                              # (path + mkdir_parents como query params, body binario,
│                              # Content-Type: application/json confirmado — ver §6, Diseño §2.9)
├── dto/                        # DTOs en formato Solution One (external_reference,
│                              # customer_location_*, tracking_status...) — nunca expuestos
│                              # fuera de este módulo
├── enums/                      # copia local de EstadoInterno/TrackingStatus/FlujoEnsamble
│                              # con los mismos valores que orquestador-app y que el YAML —
│                              # ya no se comparte vía módulo común (§1.1); la consistencia
│                              # entre ambas apps la garantiza el contrato OpenAPI, no el classpath
├── mapper/                     # payload recibido (lenguaje Siman) → dto de Solution One
├── callback/                   # @FeignClient hacia orquestador-app — POST /internal/orquestador/
│                              # solicitudes/resultado, con el resultado final y las
│                              # transacciones[] (incluyendo AUTH_TOKEN) — ver Diseño §2.4
└── config/
```

## 1.4 Responsabilidad de cada capa

### 1.4.1 `orquestador-app` — `messaging`

- Recibe el `POST /internal/eventos` (push de Pub/Sub — ver `HUENSA-001_openapi_V3.yaml`).
- Decodifica `message.data` (base64) y lee `message.attributes.origen`/`tipo_evento` **antes** de deserializar el contenido — evita tener que "adivinar" el schema inspeccionando el JSON.
- Rutea al mapper correspondiente según `origen`+`tipo_evento`. Para `origen=wms`, `flujo` no se lee ni se persiste como atributo de transporte — no aplica (Diseño §2.5): ASSE/ENSA se determinan por línea, dentro del `enrichment`, después de consultar el shipment, nunca al momento de publicar. Para `origen=guias`, `flujo` (`CARM`/`TARM`/`DARM`) sigue siendo obligatorio y sí se persiste directo.
- El ruteo lee directamente `message.attributes.tipo_evento` (`CREAR`/`UP05`/`UP06` para WMS; `creacion`/`actualizacion` para Guías) — no hace falta inferir la transición comparando contra el estado actual de la sub-orden.
- ⚠ **Gap conocido — `origen=guias` con `tipo_evento=actualizacion` no está implementado.** `EventoRouter.rutearGuias` ignora el valor de `tipoEvento` y siempre invoca el mapper de creación. Pendiente real: construir el camino de actualización para CARM/TARM/DARM (detalle en la Bitácora de Implementación).
- **`WmsEventoMapper`:** para `origen=wms`, el mapper no construye la entidad directamente desde el payload — el payload (`EventoWmsCrear` en creación; `WmsUP05Payload`/`WmsUP06Payload` en actualización) es solo un disparador. El mapper extrae únicamente `whseId`+`externOrderKey`:
  - Creación y UP05: un solo `Head`, una sola extracción.
  - UP06: `Head` es un **arreglo de órdenes** — el mapper itera `Head[]` y produce una extracción de `whseId`+`externOrderKey` por cada orden del lote (fan-out de primer nivel). Cada una se procesa de forma independiente en los pasos siguientes.
  - Cada extracción se delega a `enrichment` (§1.4.3), que consulta el shipment y devuelve las líneas calificadas (`ext_udf_str10` no nulo) — de ahí sale el fan-out de segundo nivel (por línea/SKU), tanto para creación (nuevas entidades `RECIBIDA`) como para actualización (aplicado sobre sub-órdenes ya existentes, vía el fan-out del `service` descrito en §1.4.2).
- **Fan-out de creación para origen `guias`:** el evento unificado `EventoGuias` (CARM/TARM/DARM) trae **un solo `ordenId` con `items[]`** — el aplicativo de Guías no publica un evento por SKU. `EventoGuiasMapper` itera `items[]` y produce una entidad `SolicitudEnsamble` (`estado_interno = RECIBIDA`) por cada elemento, todas bajo el mismo `ordenId`, antes de pasar cada una al `service` para enriquecimiento y notificación individual. Esto es un fan-out distinto al de WMS: para Guías ocurre en el `mapper`, leyendo directamente `items[]` del propio evento (sin consulta externa); para WMS ocurre en el `enrichment`, después de una consulta HTTP.

### 1.4.2 `orquestador-app` — `service`

- **Idempotencia de creación:** constraint única `(orden_id, sku)` en `ensamble_solicitud` — captura `DataIntegrityViolationException` y la traduce a resultado idempotente (no error, no notifica a `unogroup-app`), sin excepción — ver Diseño §2.6. El valor de `accion` viaja explícito en `SolicitudNotificacionRequest.accion` (`create`/`update`, contrato OpenAPI) — `create` desde `crear()` en el primer insert, `update` desde `aplicarActualizacion()` (ver línea siguiente); nunca se infiere de la verificación de duplicado. Con el fan-out de creación de Guías (§1.4.1) y el fan-out por línea de WMS (vía `enrichment`, §1.4.3), esta constraint también protege contra reintentos de entrega de Pub/Sub del mismo evento: cada `(ordenId, sku)` sigue siendo único aunque el evento completo se reprocese.
- **Fan-out de actualización:** si el evento no trae `sku` (o, para WMS, si el `enrichment` devuelve varias líneas calificadas para el mismo `ordenId`), busca todas las sub-órdenes bajo ese `ordenId` que califiquen, aplica el cambio a cada una y notifica a `unogroup-app` con `accion=update` por cada sub-orden afectada. No hay despachos parciales, así que no hace falta cruzar contra `detail[]`/`pickDetail[]` del evento crudo — todas las sub-órdenes calificadas de la orden se actualizan juntas. **No confundir con el fan-out de creación de `EventoGuiasMapper` (§1.4.1)** — este actúa sobre sub-órdenes ya persistidas, aquel sobre `items[]` de un evento entrante.
- **Manejo de update huérfano:** si no existe ninguna sub-orden para el `ordenId`, no asume error inmediato — aplica ventana de tolerancia corta (condición de carrera con la creación, que puede no haberse procesado aún).
- Transiciona `estado_interno`: `RECIBIDA` → (enriquecimiento) → `ENRIQUECIDA`. Al completar el enriquecimiento, escribe el resultado en `payload_enriquecido` (columna separada de `payload_origen`, que se mantiene inmutable como crudo de auditoría — ver §2 y §3). Al llegar a `ENRIQUECIDA`, invoca `client` para notificar al UnoGroup.
- Registra en `SolicitudHistorial` cada transición de negocio — **no** el detalle de llamadas HTTP (eso vive exclusivamente en `ensamble_bitacora_partner`, poblada por este mismo servicio a partir del callback que reporta `unogroup-app`, ver §1.4.4b).

### 1.4.3 `orquestador-app` — `enrichment`

- **Paso central y obligatorio para WMS, no opcional:** `WmsShipmentClient` consulta `GET /wms/dw/v1/shipment/get-shipment/{whseId}/{externOrderKey}` (`WmsShipmentDetail`, ver openapi) **siempre** que `WmsEventoMapper` (§1.4.1) entrega una extracción — tanto en creación como en actualización, sin excepción; es la única fuente real de los datos de negocio.
- **Filtrado y determinación de flujo:** de la respuesta, filtra `orderdetails[]` por `ext_udf_str10` no nulo. Cada línea calificada determina su propio `FlujoEnsamble` (`ASSE`→`service_location=casa`, `ENSA`→`service_location=centro_distribucion`, vía la regla derivada en Diseño §4.1) — el flujo nunca llega como atributo de transporte, se calcula aquí, por línea.
- **En creación:** cada línea calificada se convierte en una nueva entidad `SolicitudEnsamble` (`estado_interno = RECIBIDA` → `ENRIQUECIDA` tras completar el mapeo de campos).
- **En actualización:** las líneas calificadas identifican qué sub-órdenes ya persistidas (por `ordenId`+`sku`) deben transicionar `tracking_status` — delega al fan-out de actualización del `service` (§1.4.2).
- Para los campos que ni siquiera `WmsShipmentDetail` trae (`item_brand`, `item_category`, `item_description`, `customer_vip`, `latitud`/`longitud` — ver Diseño §4.1/§4.3, A5), este paquete sigue siendo responsable de la consulta a RMS como fallback adicional.
- Para Guías, resuelve los campos marcados `consulta: RMS` en el Diseño §4.3.
- Responsabilidad exclusiva de este paquete: **nunca** debe vivir lógica de enriquecimiento dentro de `messaging` ni de `service`, para mantener la responsabilidad de "completar datos" separada de "recibir evento" y de "orquestar transición de estado".
- ⚠ **Pendiente:** fuente exacta de `tracking_order_time` (`adddate` vs. `orderdate` de `WmsShipmentDetail`) y de `tracking_dispatched_time`/`tracking_delivered_time` (campo `fecha` del payload crudo vs. hora de recepción del evento) — ver Bitácora de Diseño, A6/A7.

### 1.4.4 `orquestador-app` — `client`

- `@FeignClient` hacia `unogroup-app`, endpoint `POST /internal/unogroup/solicitudes` (ver `HUENSA-001_openapi_V3.yaml`).
- Payload: **el `payload_enriquecido` completo, más el campo `accion` explícito** (`create`/`update`, ver §1.4.2) — no una referencia. UnoGroup no tiene dónde ir a buscar el contenido, y no tiene que inferir si es creación o actualización a partir de la forma del payload (ver Diseño §2.4).
- Reintento corto en el momento (2-3 intentos, backoff de segundos) ante fallas transitorias — ver Diseño §2.11. El job de `reconciliation` es la red de seguridad para el caso donde incluso este reintento corto falle del todo.
- No espera el resultado final en la respuesta — UnoGroup responde `202` de inmediato; el resultado llega después por el callback (§1.4.4b).

### 1.4.4b `orquestador-app` — `controller` (endpoint de callback)

- Expone `POST /internal/orquestador/solicitudes/resultado` (nombre tentativo, contrato exacto pendiente — Bitácora de Diseño, F8), que recibe el callback de `unogroup-app` con el resultado final y el registro completo de cada transacción HTTP (`transacciones[]`, `AUTH_TOKEN` incluido — contrato v3).
- Delega a `service`, que inserta cada elemento de `transacciones` como una fila de `ensamble_bitacora_partner` y transiciona `estado_interno` según `resultadoFinal` — este controller es la única vía por la que `ensamble_bitacora_partner` recibe datos (ver §1.4.2 y §2, ownership actualizado).

### 1.4.4c `orquestador-app` — `client/wms`

- `WmsShipmentClient` — `@FeignClient` hacia la API de WMS, `GET /wms/dw/v1/shipment/get-shipment/{whseId}/{externOrderKey}`. Vive junto a (o dentro de) `enrichment`, no dentro de `client/` — ese paquete es exclusivamente para la comunicación hacia `unogroup-app`, un contrato interno distinto con su propio ciclo de vida.
- Sin autenticación definida todavía — mismo pendiente estructural que el resto de credenciales externas (ver §7, placeholders `TBD_*`).
- Sin política de reintento definida todavía para esta llamada específica — a diferencia del cliente hacia `unogroup-app` (§1.4.4), que sí tiene reintento corto documentado.

### 1.4.5 `orquestador-app` — `reconciliation`

> ⚠ **Estado actual: no implementado.** Esta sección describe el diseño acordado, pero el paquete `reconciliation` **no existe** en `src/main/java` del repositorio actual, ni la clave `ensambles.reconciliation.*` en `application.yml` (a diferencia de lo mostrado en §4 de este documento). Detalle en la Bitácora de Implementación §2.1.

- Job periódico (orden de 15-30 min, valor exacto ⚠ TBD — Bitácora de Diseño, F7) que revisa filas atascadas en **tres** zonas (Diseño §2.11):
  - `RECIBIDA` por más de N minutos sin pasar a `ENRIQUECIDA`.
  - `ENRIQUECIDA` por más de N minutos sin recibir el callback de UnoGroup.
  - ⚠ **TBD (Bitácora de Diseño, F16):** cómo distinguir "UnoGroup nunca recibió la notificación" de "UnoGroup sí procesó pero el callback se perdió" — sin acceso de UnoGroup a base de datos, esta distinción no es trivial. Puede requerir que `orquestador-app` registre el intento de notificación (no solo el resultado) para poder diferenciar los dos casos.
- Vive en `orquestador-app` porque el Orquestador es quien tiene visibilidad completa del ciclo de vida (crea la fila, notifica, y recibe el callback); UnoGroup no tiene ningún estado persistente propio para consultar.

### 1.4.6 `unogroup-app` — `controller` / `service`

UnoGroup no lee de base de datos — procesa lo que recibe en el body y reporta por callback.

- `controller` recibe la notificación con el `payload_enriquecido` completo y el campo `accion` (`create`/`update`) en el body. Responde `202` de inmediato (siempre antes de procesar — comunicación asíncrona, ver Diseño §2.4) y delega el procesamiento a `service`, que corre después de responder.
- `service` lee `accion` directamente del body — **nunca infiere** creación vs. actualización a partir de qué campos trae `payloadEnriquecido` (ver Diseño §2.4). Traduce el payload recibido, invoca `client`/`mapper`, aplica la política de reintentos síncrona de la tabla en Diseño §2.9 (backoff exponencial para 500, una vez para 401, sin reintento para 400/403/413). Cada llamada real (auth + upload) se captura como una transacción HTTP genérica (`CapturingFeignClient`, envoltorio único sobre el `Client` de Feign — method/url/headers/body enmascarados, response o error) — `service` solo anexa la metadata de negocio (secuencia/proposito/esReintento).
- Al terminar (éxito o fallo definitivo), invoca `callback` con el resultado final y la lista completa de `transacciones` (contrato v3) — este es el único momento en que UnoGroup "entrega" lo que hizo; no queda ningún registro de la ejecución dentro de `unogroup-app` una vez que el callback se envía.

### 1.4.7 `unogroup-app` — `client` / `dto` / `mapper`

**Construcción del `path` (query param) — algoritmo vigente (Diseño §2.9):**

```
{ruta-base}/{accion}/{fecha_envio}/{accion}_{timestamp_orden}_{external_reference}_{sku}.json
```

| Componente | Valor | Fuente |
|---|---|---|
| `ruta-base` | `siman` | Prefijo común; `SolutionOneFileNaming` agrega `/create` o `/update` según la `accion`. Historial de cómo se llegó a este valor: ver Bitácora de Diseño §3.3 (C3) |
| `fecha_envio` | Fecha **actual** al momento de subir (no una fecha de negocio), formato `yyyyMMdd` |
| `accion` | `create` para creación, `update` para actualización — recibido explícitamente en el campo `accion` de la notificación del Orquestador (§1.4.2/§1.4.4), nunca inferido en `unogroup-app` |
| `timestamp_orden` | El valor de `tracking_order_time` **del pedido**, formateado `yyyyMMddHHmmss` — **no** la hora actual de envío |
| `external_reference` | `ordenId`, directo |
| `sku` | `sku` de la sub-orden. **Siempre incluido, en creación y en actualización** — aunque el body de actualización no lleva `item_sku` (§4.4), el nombre de archivo sí, y es lo que le permite a UnoGroup identificar la sub-orden exacta |

**Implementación de referencia:**

```java
package com.siman.ensambles.unogroup.client;

import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.ZoneOffset;

public class SolutionOneFileNaming {

    private static final DateTimeFormatter FECHA_CARPETA =
        DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIMESTAMP_ARCHIVO =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    public static String construirPath(String rutaBase, String accion,
            Instant trackingOrderTime, String externalReference, String sku) {
        String fecha = FECHA_CARPETA.format(Instant.now());       // fecha de envío, no de negocio
        String timestamp = TIMESTAMP_ARCHIVO.format(trackingOrderTime); // del pedido, no "ahora"
        // sku siempre incluido — en creación Y en actualización (Bitácora de Diseño, F20),
        // aunque el body de actualización no lo lleve como campo (§4.4).
        String nombreArchivo = String.format("%s_%s_%s_%s.json", accion, timestamp, externalReference, sku);
        return String.format("%s/%s/%s/%s", rutaBase, accion, fecha, nombreArchivo);
    }
}
```

**Resuelto 2026-07-29 (v3):** el nombre de archivo generado ya no se persiste como columna propia (`ensamble_solicitud.nombre_archivo` y `ensamble_bitacora_partner.nombre_archivo` se eliminaron) — el `path` completo, filename incluido, viaja embebido en `request.url` de la transacción `UPLOAD_CREATE`/`UPLOAD_UPDATE` correspondiente dentro de `ResultadoSolicitud.transacciones[]` (contrato v3). Extraer el nombre limpio desde ahí (si se necesita) requiere conocer el algoritmo `SolutionOneFileNaming` — se hace en un helper de aplicación, no en SQL ni en el contrato.



- Traduce el `payload_enriquecido` recibido (lenguaje Siman) al formato binario que espera Solution One, vía `mapper`. El resultado de esa transformación (`request.body`) se incluye en la transacción HTTP correspondiente dentro del callback — es, literalmente, el `request.body` que `CapturingFeignClient` captura (sin enmascarar, limitación conocida) y que `service` reporta dentro de `transacciones[]`; ya no existe una columna `payload_partner` separada en `ensamble_solicitud` (v3), UnoGroup no lo guarda él mismo en ningún lado, solo lo reporta.

**Mapeo de campos** (`Campo API` → `Campo Solution One`, ver Diseño §4.4 para los JSON de ejemplo completos — este mapeo define el contenido de `request.body` en cada transacción `UPLOAD_CREATE`/`UPLOAD_UPDATE`, no una columna `payload_partner` propia, eliminada en v3):

| Campo API (`payload_enriquecido`) | Campo Solution One (`request.body`) | Solo creación |
|---|---|---|
| `ordenId` | `external_reference` | No |
| `numeroFactura` | `external_reference_alt_1` | Sí |
| *(sin nombre — ver Diseño §4.3)* | `external_reference_alt_2` | Sí |
| `nombreCliente` | `customer_name` | Sí |
| `telefonoCliente` | `customer_phone` | Sí |
| `correoCliente` | `customer_email` | Sí |
| `clienteVip` | `customer_vip` | Sí |
| `direccion` | `customer_address` | Sí |
| `ciudad` | `customer_city` | Sí |
| `departamento` | `customer_state` | Sí |
| `pais` | `customer_country` | Sí |
| `latitud` | `customer_latitude` | Sí |
| `longitud` | `customer_longitude` | Sí |
| `tipoServicio` | `service_type` | Sí |
| `ubicacionServicio` | `service_location` | Sí |
| `referenciaUbicacionServicio` | `service_location_reference` | Sí |
| `sku` | `item_sku` | **Sí — no viaja en actualizaciones** |
| `marcaProducto` | `item_brand` | Sí |
| `categoriaProducto` | `item_category` | Sí |
| `descripcionProducto` | `item_description` | Sí |
| `cantidad` | `item_quantity` | Sí |
| `estado` | `tracking_status` | No |
| `fechaOrden` | `tracking_order_time` | Sí |
| `fechaPlanificadaDespacho` | `tracking_dispatch_plan_time` | Sí |
| `fechaPlanificadaEntrega` | `tracking_delivery_plan_time` | Sí |
| `fechaRealDespacho` | `tracking_dispatched_time` | **No — solo actualización** |
| `fechaRealEntrega` | `tracking_delivered_time` | **No — solo actualización** |

⚠ **Dos huecos que siguen sin resolver, ninguno bloquea implementar el resto de la tabla:**
- El campo API de `external_reference_alt_2` no tiene nombre asignado todavía (marcado `TBD` incluso en el mapeo ya confirmado — ver Diseño §4.3).
- El valor de `service_type` para el flujo DARM no está confirmado (solo se vio `"armado"` en el ejemplo — ver Bitácora de Diseño, F18).
- Construye el query param `path` — algoritmo confirmado, ver §1.4.7 — y siempre incluye `mkdir_parents=true` (ver Diseño §6.3 — evita que la ausencia de este parámetro se confunda con un error de permisos, `403`).
- Gestiona la obtención del JWT (`GET /api/v2/user/token`) vía `SolutionOneTokenManager` — **sin caché entre solicitudes**: cada llamada a `procesar()` adquiere un token nuevo, y `SolutionOneRetryPolicy` vuelve a llamarlo una sola vez si la subida responde `401` (ver tabla de reintentos, Diseño §2.9). No hay estado de expiración cacheado en memoria.
- ✅ **Confirmado contra el ambiente de prueba real:** el body de la carga es binario (no serializado automáticamente por Feign) — sí requiere un `Encoder` de Feign personalizado, y `Content-Type: application/json` funciona para ese body binario (Bitácora de Diseño, F9, resuelto).

### 1.4.8 `unogroup-app` — `callback`

- `@FeignClient` hacia `orquestador-app`, endpoint `POST /internal/orquestador/solicitudes/resultado` (§1.4.4b).
- Se invoca al terminar de procesar — éxito o fallo definitivo hacia Solution One, nunca a mitad de un reintento hacia Solution One (esa es una política distinta, ver §1.4.7).
**Política de reintentos:** dado que `unogroup-app` no persiste nada, perder esta llamada significa perder el resultado del procesamiento sin dejar ningún estado consultable salvo logs — merece más resiliencia que la notificación entrante (2-3 intentos, ver Diseño §2.11), pero sin llegar al backoff largo de Solution One (que existe para tolerar un tercero externo inestable, no un blip de red interna del clúster):

| Parámetro | Valor |
|---|---|
| Máximo de intentos | 5 |
| Backoff inicial | 500 ms |
| Multiplicador | 2 (500ms → 1s → 2s → 4s → 8s ≈ 15.5s en total) |
| Si se agotan los 5 intentos | Se registra en logs a nivel `ERROR`, en formato estructurado (incluyendo `ordenId`, `sku`, `resultadoFinal` y el detalle completo de `transacciones[]`) — no se descarta el resultado, queda recuperable manualmente desde logs aunque no exista un estado consultable en base de datos. |

- La reconciliación (zona 3, Diseño §2.11 / Bitácora de Diseño F16) sigue siendo necesaria como red de seguridad final — este reintento reduce drásticamente la probabilidad de llegar a esa zona (cubre blips transitorios, que son la inmensa mayoría de los casos), pero no la elimina: si `orquestador-app` está caído más de ~15.5s seguidos, el callback se agota igual y la reconciliación es lo único que queda.

---

# 2. DDL MySQL

**Módulo:** `orquestador-app` — el DDL en sí no vive en un módulo Maven (es SQL, no Java), pero se versiona junto al código de `orquestador-app` porque describe exactamente las entidades que ese módulo expone. Único módulo con acceso a esta base de datos (ver §1.1).

**Estado físico (Flyway):** el script consolidado de abajo es el **estado final v3**, para lectura de referencia — el schema real se construye incrementalmente vía `src/main/resources/db/migration/`: `V1__init.sql` (schema original), `V2__rename_tables_ensamble_prefix.sql` (prefijo `ensamble_`), `V3__drop_ensamble_solicitud_fecha_columns.sql` (elimina 5 columnas de fecha nunca acordadas en el diseño), `V4__recreate_ensamble_bitacora_partner_v3.sql` (`DROP`+`CREATE` de `ensamble_bitacora_partner` al modelo `transacciones[]`) y `V5__drop_ensamble_solicitud_payload_partner_nombre_archivo.sql` (elimina `payload_partner`/`nombre_archivo`, redundantes frente a `ensamble_bitacora_partner` v3). Flyway no permite editar una migración ya versionada, así que cada cambio de schema se agrega como archivo nuevo, nunca modificando `V1`/`V2`/etc. in situ.

```sql
-- ============================================================
-- Sistema de Ensambles — DDL MySQL 8.0+
-- Propiedad exclusiva de orquestador-app (ver Diseño §2.6) —
-- unogroup-app no tiene acceso a esta base de datos.
-- ============================================================
-- Modelo: cada sub-orden (orden_id + sku) es la unidad atómica.
-- Un orden_id puede tener N sub-órdenes (pedido multi-ítem).
-- Nomenclatura en lenguaje Siman desde el inicio (orden_id, no
-- externorderkey, que es el nombre crudo de WMS).
-- ============================================================

SET time_zone = '+00:00';

-- ----------------------------------------------------------
-- Tabla principal: sub-órdenes.
-- Escritura: exclusiva de orquestador-app (creación, enriquecimiento,
-- y transición final al recibir el callback de unogroup-app) — ver
-- Diseño §2.6.1, tabla de dueño de escritura. unogroup-app no tiene
-- acceso a esta base de datos.
-- ----------------------------------------------------------
CREATE TABLE ensamble_solicitud (
    id                  BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    orden_id            VARCHAR(32)     NOT NULL,
    sku                 VARCHAR(50)     NOT NULL,
    flujo               VARCHAR(10)     NOT NULL,
    -- Estado interno del pipeline entre los dos microservicios.
    -- Sin EN_PROCESO (descartado, ver Diseño §2.7 — no hay polling
    -- ni competencia entre workers en el modelo v2).
    estado_interno      VARCHAR(20)     NOT NULL DEFAULT 'RECIBIDA',
    -- Estado de negocio, espejo del enum del contrato del partner
    tracking_status     VARCHAR(15)     NOT NULL DEFAULT 'creada',
    -- Payload tal como llegó del origen (auditoría/replay), lenguaje Siman,
    -- inmutable — se escribe una sola vez, al recibir el evento.
    payload_origen      JSON            NOT NULL,
    -- Resultado del enriquecimiento: el JSON completo en lenguaje Siman,
    -- con todos los campos ya rellenados. Lo escribe el Orquestador al
    -- marcar ENRIQUECIDA; es lo que lee unogroup-app para armar la petición
    -- a Solution One (no lee payload_origen directamente).
    payload_enriquecido JSON,
    -- payload_partner y nombre_archivo se eliminaron en v3 (V5__drop_...):
    -- quedaban redundantes frente a ensamble_bitacora_partner, que ahora
    -- captura esta información por transacción (request_body/request_url
    -- de la última UPLOAD_CREATE/UPLOAD_UPDATE).
    fecha_creacion      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    fecha_actualizacion TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_ensamble_solicitud_orden_sku UNIQUE (orden_id, sku),

    CONSTRAINT ck_ensamble_solicitud_flujo
        CHECK (flujo IN ('ASSE','ENSA','CARM','TARM','DARM')),

    CONSTRAINT ck_ensamble_solicitud_estado_int
        CHECK (estado_interno IN (
            'RECIBIDA', 'ENRIQUECIDA', 'ENVIADA_PARTNER',
            'ACEPTADA_PARTNER', 'RECHAZADA_PARTNER'
        )),

    CONSTRAINT ck_ensamble_solicitud_tracking
        CHECK (tracking_status IN (
            'creada', 'alistada', 'despachada', 'entregada', 'retornada'
        ))

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Sub-orden única (orden_id + sku). Unidad atómica compartida entre orquestador-app y unogroup-app.';

CREATE INDEX ix_ensamble_solicitud_orden_id ON ensamble_solicitud (orden_id);
CREATE INDEX ix_ensamble_solicitud_estado ON ensamble_solicitud (estado_interno);

-- ----------------------------------------------------------
-- Trigger: mantener fecha_actualizacion sincronizada
-- ----------------------------------------------------------
DELIMITER $$
CREATE TRIGGER trg_ensamble_solicitud_upd
    BEFORE UPDATE ON ensamble_solicitud
    FOR EACH ROW
BEGIN
    SET NEW.fecha_actualizacion = CURRENT_TIMESTAMP(6);
END$$
DELIMITER ;

-- ----------------------------------------------------------
-- Historial de negocio — angostada en v2 (ver Diseño §2.6):
-- solo transiciones de estado_interno/tracking_status. El
-- detalle de cada llamada HTTP vive exclusivamente en
-- ensamble_bitacora_partner, no aquí.
-- Escritura: exclusiva de orquestador-app (incluida la transición
-- terminal, a partir del callback de unogroup-app).
-- ----------------------------------------------------------
CREATE TABLE ensamble_solicitud_historial (
    id                  BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    solicitud_id        BIGINT          NOT NULL,
    estado_interno_ant  VARCHAR(20),
    estado_interno_nvo  VARCHAR(20)     NOT NULL,
    tracking_status_ant VARCHAR(15),
    tracking_status_nvo VARCHAR(15),
    evento              VARCHAR(50)     NOT NULL,
    detalle             VARCHAR(500),
    fecha_evento        TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_ensamble_historial_solicitud
        FOREIGN KEY (solicitud_id) REFERENCES ensamble_solicitud (id)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_ensamble_historial_solicitud ON ensamble_solicitud_historial (solicitud_id);

-- ----------------------------------------------------------
-- Bitácora de transacciones HTTP hacia Solution One — modelo v3
-- (transacción genérica request/response/error). Reemplaza el
-- modelo v2 de columnas sueltas y acopladas a Solution One
-- (tipo_peticion, url, metodo_http, codigo_http, respuesta_body,
-- intento_num, exitoso) — no retrocompatible, recreada con
-- DROP TABLE + CREATE TABLE (V4__recreate_ensamble_bitacora_partner_v3.sql).
-- Escritura exclusiva de orquestador-app, poblada a partir del
-- callback que reporta unogroup-app (unogroup-app no tiene acceso a
-- base de datos — reporta, no escribe).
--
-- Resuelve la discrepancia de NOT NULL de v2 (Diseño F8): request_url/
-- request_method son NOT NULL igual que antes url/metodo_http, pero en
-- v3 son obligatorios en el contrato (HttpRequest.required) — ya no
-- hace falta el workaround de hardcodear "N/D" (ver Bitácora de
-- Implementación §2.1, resuelto).
-- ----------------------------------------------------------
CREATE TABLE ensamble_bitacora_partner (
    id                      BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,

    -- orden_id/sku dejan de ser nullable en v3 — viven siempre en
    -- ResultadoSolicitud (nivel superior), así que toda transacción,
    -- incluidas las de AUTH_TOKEN, ya viene asociada a una orden/sku.
    solicitud_id            BIGINT          NOT NULL,
    orden_id                VARCHAR(32)     NOT NULL,
    sku                     VARCHAR(50)     NOT NULL,

    -- Metadata SIMAN (TransaccionMetadata) — clasificación interna,
    -- no forma parte del intercambio HTTP en sí.
    secuencia               SMALLINT        NOT NULL,
    proposito               VARCHAR(20)     NOT NULL,   -- 'AUTH_TOKEN' | 'UPLOAD_CREATE' | 'UPLOAD_UPDATE'
    es_reintento            CHAR(1)         NOT NULL DEFAULT 'N',

    -- Request (HttpRequest) — siempre presente.
    request_method          VARCHAR(10)     NOT NULL,
    request_url             VARCHAR(500)    NOT NULL,   -- enmascarada (query string)
    request_timestamp       TIMESTAMP(6)    NOT NULL,
    request_content_type    VARCHAR(100),
    request_headers         JSON,                        -- enmascarados (lista fija de nombres sensibles)
    request_body            MEDIUMTEXT,                  -- SIN enmascarar (limitación conocida v3)

    -- Response (HttpResponse) — presente solo si hubo respuesta HTTP
    -- válida; NULL en conjunto si la transacción terminó en error.
    response_status_code    SMALLINT,
    response_timestamp      TIMESTAMP(6),
    response_duration_ms    INT,
    response_content_type   VARCHAR(100),
    response_headers        JSON,                        -- enmascarados
    response_body           MEDIUMTEXT,                  -- SIN enmascarar (limitación conocida v3)

    -- Error (TransaccionError) — presente solo si NO hubo respuesta HTTP
    -- válida (timeout, red, DNS, serialización previa al envío).
    error_tipo               VARCHAR(30),    -- 'TIMEOUT' | 'CONEXION_RECHAZADA' | 'DNS' | 'SERIALIZACION' | 'DESCONOCIDO'
    error_mensaje            VARCHAR(500),
    error_timestamp          TIMESTAMP(6),
    error_duration_ms        INT,

    fecha_registro           TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_ensamble_bitacora_solicitud
        FOREIGN KEY (solicitud_id) REFERENCES ensamble_solicitud (id),

    CONSTRAINT ck_ensamble_bitacora_proposito
        CHECK (proposito IN ('AUTH_TOKEN', 'UPLOAD_CREATE', 'UPLOAD_UPDATE')),

    CONSTRAINT ck_ensamble_bitacora_reintento
        CHECK (es_reintento IN ('S', 'N')),

    CONSTRAINT ck_ensamble_bitacora_error_tipo
        CHECK (error_tipo IS NULL OR error_tipo IN ('TIMEOUT', 'CONEXION_RECHAZADA', 'DNS', 'SERIALIZACION', 'DESCONOCIDO')),

    -- response_status_code y error_tipo son mutuamente excluyentes:
    -- exactamente uno de los dos debe estar presente por fila.
    CONSTRAINT ck_ensamble_bitacora_response_xor_error
        CHECK (
            (response_status_code IS NOT NULL AND error_tipo IS NULL)
            OR
            (response_status_code IS NULL AND error_tipo IS NOT NULL)
        )

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Bitácora de transacciones HTTP hacia el partner de ensamble (formato genérico request/response/error). Escritura exclusiva de orquestador-app, poblada a partir del callback de unogroup-app.';

CREATE INDEX ix_ensamble_bitacora_solicitud    ON ensamble_bitacora_partner (solicitud_id);
CREATE INDEX ix_ensamble_bitacora_orden_sku    ON ensamble_bitacora_partner (orden_id, sku);
CREATE INDEX ix_ensamble_bitacora_status_fecha ON ensamble_bitacora_partner (response_status_code, fecha_registro);
CREATE INDEX ix_ensamble_bitacora_proposito    ON ensamble_bitacora_partner (proposito);

-- ============================================================
-- Notas de diseño para el equipo de implementación:
--
-- 0. Convención de nombres: todas las tablas del módulo llevan el
--    prefijo `ensamble_` (schema de Cloud SQL compartido con otros
--    sistemas de Siman). La tabla principal es `ensamble_solicitud`
--    (sin repetir "ensamble" dos veces); las demás conservan su
--    nombre original con el prefijo antepuesto. Constraints, índices
--    y el trigger siguen el mismo prefijo por consistencia — ver
--    Diseño §2.6.
--
-- 1. uq_ensamble_solicitud_orden_sku es la llave de idempotencia de creación.
--    Un INSERT duplicado se captura como DataIntegrityViolationException
--    en Spring y se traduce a resultado idempotente (no HTTP 409 — el
--    consumer de Pub/Sub no expone códigos HTTP de negocio al publicador).
--
-- 2. payload_origen / payload_enriquecido / request_headers / response_headers
--    usan JSON nativo de MySQL 8.0+, que valida el formato automáticamente
--    en INSERT/UPDATE. request_body/response_body son MEDIUMTEXT (hasta
--    16MB), no JSON — capturan el body completo tal cual, sin asumir que
--    sea JSON válido (contrato genérico, no acoplado a un partner).
--
-- 3. Todas las fechas se almacenan en UTC. Configurar el DataSource
--    con serverTimezone=UTC en la URL JDBC en ambas apps.
--
-- 4. No hay tabla de cola/reintentos (solicitud_reintento se eliminó,
--    ver Diseño §2.6) — el job de reconciliación (§1.4.5 de este
--    documento) trabaja directamente sobre estado_interno + fecha_actualizacion,
--    sin tabla dedicada.
--
-- 5. ENGINE=InnoDB requerido para foreign keys y transacciones ACID.
--    utf8mb4 soporta el juego completo Unicode.
--
-- 6. Pendiente de definir antes de Fase 1 final: TTL/purga de
--    ensamble_solicitud_historial y ensamble_bitacora_partner (retención de datos
--    no definida aún como requerimiento) — más urgente en v3, porque
--    capturar el body completo de cada transacción aumenta
--    significativamente el volumen frente al modelo v2.
-- ============================================================
```

---

# 3. Entidades JPA

**Módulo:** `orquestador-app` (`domain/`) — único módulo que las usa (ver §1.1).

```java
package com.siman.ensambles.orquestador.domain;

import com.siman.ensambles.orquestador.domain.enums.EstadoInterno;
import com.siman.ensambles.orquestador.domain.enums.FlujoEnsamble;
import com.siman.ensambles.orquestador.domain.enums.TrackingStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "ensamble_solicitud",
       uniqueConstraints = @UniqueConstraint(columnNames = {"orden_id", "sku"}))
@Getter @Setter                       // NO @Data — ver nota abajo
@NoArgsConstructor @AllArgsConstructor @Builder
public class SolicitudEnsamble {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "orden_id", nullable = false, length = 32)
    private String ordenId;

    @Column(name = "sku", nullable = false, length = 50)
    private String sku;

    @Enumerated(EnumType.STRING)
    @Column(name = "flujo", nullable = false, length = 10)
    private FlujoEnsamble flujo;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_interno", nullable = false, length = 20)
    private EstadoInterno estadoInterno;

    @Enumerated(EnumType.STRING)
    @Column(name = "tracking_status", nullable = false, length = 15)
    private TrackingStatus trackingStatus;

    @Lob
    @Column(name = "payload_origen", nullable = false, columnDefinition = "json")
    private String payloadOrigen;

    @Lob
    @Column(name = "payload_enriquecido", columnDefinition = "json")
    private String payloadEnriquecido;

    // payloadPartner/nombreArchivo se eliminaron en v3 — ver
    // ensamble_bitacora_partner (request_body/request_url de la última
    // transacción UPLOAD_CREATE/UPLOAD_UPDATE).

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private Instant fechaCreacion;

    @Column(name = "fecha_actualizacion", nullable = false)
    private Instant fechaActualizacion;

    @OneToMany(mappedBy = "solicitud", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @Builder.Default
    private List<SolicitudHistorial> historial = new ArrayList<>();
}
```

```java
package com.siman.ensambles.orquestador.domain;

import com.siman.ensambles.orquestador.domain.enums.EstadoInterno;
import com.siman.ensambles.orquestador.domain.enums.TrackingStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "ensamble_solicitud_historial")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SolicitudHistorial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "solicitud_id", nullable = false)
    private SolicitudEnsamble solicitud;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_interno_ant", length = 20)
    private EstadoInterno estadoInternoAnterior;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_interno_nvo", nullable = false, length = 20)
    private EstadoInterno estadoInternoNuevo;

    @Enumerated(EnumType.STRING)
    @Column(name = "tracking_status_ant", length = 15)
    private TrackingStatus trackingStatusAnterior;

    @Enumerated(EnumType.STRING)
    @Column(name = "tracking_status_nvo", length = 15)
    private TrackingStatus trackingStatusNuevo;

    @Column(name = "evento", nullable = false, length = 50)
    private String evento;               // ej: 'CREATE_RECIBIDO', 'ENRIQUECIDA', 'ENVIADA_PARTNER'

    @Column(name = "detalle", length = 500)
    private String detalle;

    @Column(name = "fecha_evento", nullable = false, updatable = false)
    private Instant fechaEvento;
}
```

```java
package com.siman.ensambles.orquestador.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * Bitácora de transacciones HTTP hacia el partner de ensamble — modelo v3
 * (transacción genérica request/response/error), reemplaza el modelo v2 de
 * columnas sueltas (tipoPeticion/url/metodoHttp/codigoHttp/exitoso...).
 */
@Entity
@Table(name = "ensamble_bitacora_partner")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BitacoraPartner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Ya no es opcional en v3 — orden_id/sku viven siempre en
    // ResultadoSolicitud (nivel superior), toda transacción viene asociada.
    @Column(name = "solicitud_id", nullable = false)
    private Long solicitudId;

    @Column(name = "orden_id", nullable = false, length = 32)
    private String ordenId;

    @Column(name = "sku", nullable = false, length = 50)
    private String sku;

    // SMALLINT en el DDL -> Short, no Integer (ddl-auto: validate exige el
    // tipo exacto — mismo patrón que responseStatusCode más abajo).
    @Column(name = "secuencia", nullable = false)
    private Short secuencia;

    @Column(name = "proposito", nullable = false, length = 20)
    private String proposito;   // AUTH_TOKEN | UPLOAD_CREATE | UPLOAD_UPDATE

    @Column(name = "es_reintento", nullable = false, length = 1)
    private String esReintento;   // 'S' | 'N'

    @Column(name = "request_method", nullable = false, length = 10)
    private String requestMethod;

    @Column(name = "request_url", nullable = false, length = 500)
    private String requestUrl;

    @Column(name = "request_timestamp", nullable = false)
    private Instant requestTimestamp;

    @Column(name = "request_content_type", length = 100)
    private String requestContentType;

    @Lob
    @Column(name = "request_headers", columnDefinition = "json")
    private String requestHeaders;

    @Lob
    @Column(name = "request_body", columnDefinition = "mediumtext")
    private String requestBody;

    @Column(name = "response_status_code")
    private Short responseStatusCode;

    @Column(name = "response_timestamp")
    private Instant responseTimestamp;

    @Column(name = "response_duration_ms")
    private Integer responseDurationMs;

    @Column(name = "response_content_type", length = 100)
    private String responseContentType;

    @Lob
    @Column(name = "response_headers", columnDefinition = "json")
    private String responseHeaders;

    @Lob
    @Column(name = "response_body", columnDefinition = "mediumtext")
    private String responseBody;

    @Column(name = "error_tipo", length = 30)
    private String errorTipo;   // TIMEOUT | CONEXION_RECHAZADA | DNS | SERIALIZACION | DESCONOCIDO

    @Column(name = "error_mensaje", length = 500)
    private String errorMensaje;

    @Column(name = "error_timestamp")
    private Instant errorTimestamp;

    @Column(name = "error_duration_ms")
    private Integer errorDurationMs;

    @Column(name = "fecha_registro", nullable = false, updatable = false)
    private Instant fechaRegistro;
}
```

**Sobre `@Data` y relaciones lazy:** se evita `@Data` en entidades JPA porque genera `equals()`/`hashCode()`/`toString()` sobre todos los campos, incluyendo asociaciones lazy — fuerza su inicialización fuera de sesión y puede disparar `LazyInitializationException`. Se usa `@Getter`/`@Setter`/`@Builder` explícitos.

**Enums (`orquestador-app/domain/enums`):**

```java
package com.siman.ensambles.orquestador.domain.enums;

public enum EstadoInterno {
    RECIBIDA, ENRIQUECIDA, ENVIADA_PARTNER, ACEPTADA_PARTNER, RECHAZADA_PARTNER
    // Sin EN_PROCESO — descartado en v2, ver Diseño §2.7
}
```

```java
package com.siman.ensambles.orquestador.domain.enums;

public enum TrackingStatus {
    creada, alistada, despachada, entregada, retornada
}
```

```java
package com.siman.ensambles.orquestador.domain.enums;

public enum FlujoEnsamble {
    ASSE, ENSA, CARM, TARM, DARM
}
```

**Copia local en `unogroup-app` (`unogroup-app/enums`) — mismos valores, sin dependencia de build hacia `orquestador-app`:**

```java
package com.siman.ensambles.unogroup.enums;

public enum EstadoInterno {
    RECIBIDA, ENRIQUECIDA, ENVIADA_PARTNER, ACEPTADA_PARTNER, RECHAZADA_PARTNER
}
```

```java
package com.siman.ensambles.unogroup.enums;

public enum TrackingStatus {
    creada, alistada, despachada, entregada, retornada
}
```

**Nota:** estos enums están duplicados, no se comparten vía un módulo Maven — la fuente de verdad es el contrato OpenAPI (`HUENSA-001_openapi_V3.yaml`), donde los mismos valores están declarados como `enum:` en los schemas correspondientes; conviene validar contra ese archivo, no asumir que el código Java de una app es la referencia para la otra. El riesgo de desincronización entre ambas copias, y el trade-off frente a compartirlas vía módulo común, está documentado en la Bitácora de Implementación §2.3.

---

# 4. `application.yml` — `orquestador-app`

**Nota sobre Pub/Sub:** el mecanismo de entrada es **push vía Ingress** (ver Diseño §2.1), no un listener que jala mensajes — `orquestador-app` no tiene configuración de *subscriber* de Pub/Sub; es, desde el punto de vista de Spring Boot, simplemente un controller REST más. Lo que sí necesita es **validación del JWT que Pub/Sub adjunta a cada push** (para confirmar que la petición realmente viene de Pub/Sub y no de cualquiera que descubra la URL del Ingress).

```yaml
# ============================================================
# orquestador-app — Configuración base Spring Boot
# ============================================================
spring:
  application:
    name: orquestador-app

  # ----------------------------------------------------------
  # MySQL — vía Cloud SQL Auth Proxy como sidecar del propio pod.
  # La app siempre le habla a localhost; el proxy es quien
  # resuelve el túnel real hacia la instancia de Cloud SQL
  # (instancia ya existente, ver §7). No hay perfil "stub":
  # el ambiente de prueba real ya está disponible.
  # ----------------------------------------------------------
  datasource:
    url: ${DB_URL:jdbc:mysql://127.0.0.1:3306/ensambles?serverTimezone=UTC&useUnicode=true&characterEncoding=utf8mb4}
    username: ${DB_USERNAME}   # obligatorio — Secret Manager, sin default
    password: ${DB_PASSWORD}   # obligatorio — Secret Manager, sin default
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      connection-timeout: 20000
      maximum-pool-size: 10
      minimum-idle: 2

  jpa:
    hibernate:
      ddl-auto: validate   # el DDL real vive en el script versionado junto a orquestador-app (§2)
    show-sql: false
    properties:
      hibernate:
        jdbc:
          time_zone: UTC   # todas las fechas en UTC, coherente con el DDL

server:
  port: ${SERVER_PORT:8080}

# ----------------------------------------------------------
# Configuración propia de orquestador-app
# ----------------------------------------------------------
ensambles:

  # Validación del push de Pub/Sub — ver nota sobre el mecanismo
  # push vía Ingress al inicio de esta sección.
  pubsub-push:
    # Pub/Sub firma cada request push con un JWT de identidad de
    # la cuenta de servicio configurada al crear la suscripción.
    # Se valida audience + issuer antes de procesar el mensaje.
    audiencia-esperada: ${PUBSUB_PUSH_AUDIENCE:https://ensambles.siman.com/internal/eventos}
    cuenta-servicio-esperada: ${PUBSUB_PUSH_SERVICE_ACCOUNT:}   # obligatorio, sin default — Secret/ConfigMap de GKE

  # Cliente hacia unogroup-app — notificación con payload_enriquecido
  # completo (no una referencia) — vía Service de Kubernetes, DNS
  # interno del clúster.
  unogroup-client:
    base-url: ${UNOGROUP_APP_URL:http://svc-unogroup.ensambles.svc.cluster.local}
    timeout-conexion-ms: ${UNOGROUP_CLIENT_TIMEOUT_CONN_MS:2000}
    timeout-lectura-ms: ${UNOGROUP_CLIENT_TIMEOUT_READ_MS:3000}
    reintentos:
      # Reintento corto en el momento (Diseño §2.11) — no confundir
      # con la política de reintentos hacia Solution One, que vive
      # en unogroup-app, no aquí.
      max-intentos: ${UNOGROUP_CLIENT_MAX_INTENTOS:3}
      backoff-inicial-ms: ${UNOGROUP_CLIENT_BACKOFF_MS:500}

  # Job de reconciliación (Diseño §2.11) — red de seguridad para
  # las tres zonas atascadas (RECIBIDA sin enriquecer, ENRIQUECIDA
  # sin notificar al UnoGroup, notificada sin callback recibido,
  # ver Bitácora de Diseño, F16). Intervalo exacto aún sin cerrar
  # (Bitácora de Diseño, F7) — el default de abajo es un punto de
  # partida, no una decisión final.
  # ⚠ Diseño de referencia: esta sección (y el job en sí, §1.4.5)
  # todavía no existen en el código — ver Bitácora de Implementación §2.1.
  reconciliation:
    habilitado: ${RECONCILIATION_HABILITADO:true}
    intervalo-cron: ${RECONCILIATION_CRON:0 */15 * * * *}   # cada 15 min — ⚠ TBD, valor final sin confirmar
    umbral-recibida-minutos: ${RECONCILIATION_UMBRAL_RECIBIDA_MIN:10}
    umbral-enriquecida-minutos: ${RECONCILIATION_UMBRAL_ENRIQUECIDA_MIN:10}
    umbral-callback-minutos: ${RECONCILIATION_UMBRAL_CALLBACK_MIN:10}   # ⚠ TBD F16 — mecanismo de detección aún sin definir

  # Endpoint que recibe el callback de unogroup-app con el resultado
  # (ver §1.4.4b). Mismo mecanismo de auth pendiente que la
  # notificación en sentido contrario (Bitácora de Diseño, F8).
  callback-receiver:
    # ⚠ Esta clave existe pero hoy no tiene ningún efecto — ver
    # Bitácora de Implementación §2.1.
    autenticacion-habilitada: ${CALLBACK_RECEIVER_AUTH_HABILITADA:false}   # ⚠ TBD — ver Diseño F8

  # Fuentes de enriquecimiento (Diseño §4.1/§4.2, columna "consulta:")
  # URLs reales pendientes de confirmar con los equipos dueños.
  enrichment:
    rms:
      base-url: ${RMS_BASE_URL:}          # ⚠ TBD — pendiente de confirmar con equipo RMS
    wms-api:
      base-url: ${WMS_API_BASE_URL:}      # ⚠ TBD — pendiente de confirmar con equipo WMS

logging:
  level:
    root: INFO
    com.siman.ensambles.orquestador: ${LOG_LEVEL_APP:INFO}
    # Nunca DEBUG en producción sin enmascarado — nombre, teléfono,
    # correo, dirección nunca en texto plano incluso en debug.
```

**Puntos que quedan explícitamente abiertos con este archivo, no resueltos por defaults razonables:**
- `PUBSUB_PUSH_SERVICE_ACCOUNT` no tiene default — es una credencial de configuración, no un secreto en sí, pero se deja sin valor por defecto para forzar su definición explícita antes de aceptar tráfico real (mismo principio que las credenciales de Solution One, §5).
- Las URLs de `rms`/`wms-api` están vacías a propósito — todavía no existe una fuente confirmada para varios de los campos que el Orquestador necesita completar (Bitácora de Diseño, preguntas A5 y equivalentes de Guías Manuales).
- El cron de reconciliación tiene un valor de arranque (`*/15`) pero sigue siendo un placeholder hasta que se cierre F7.

---

# 5. `application.yml` — `unogroup-app`

```yaml
# ============================================================
# unogroup-app — Configuración base Spring Boot
# ============================================================
spring:
  application:
    name: unogroup-app

server:
  port: ${SERVER_PORT:8080}

# ----------------------------------------------------------
# OpenFeign — dos clientes: hacia Solution One y hacia el
# callback de orquestador-app
# ----------------------------------------------------------
feign:
  client:
    config:
      solutionOneClient:
        connect-timeout: ${SOLUTIONONE_TIMEOUT_CONN_MS:3000}
        read-timeout: ${SOLUTIONONE_TIMEOUT_READ_MS:5000}
        logger-level: ${SOLUTIONONE_FEIGN_LOG_LEVEL:basic}
        # Sin Retryer propio (NEVER_RETRY) — la política de reintentos
        # vive en `service`, no en Feign, para no duplicar lógica en
        # dos capas (ver Diseño §2.11 y §1.4.6 de este documento).
      orquestadorCallbackClient:
        connect-timeout: ${CALLBACK_TIMEOUT_CONN_MS:2000}
        read-timeout: ${CALLBACK_TIMEOUT_READ_MS:3000}
        logger-level: ${CALLBACK_FEIGN_LOG_LEVEL:basic}

# ----------------------------------------------------------
# Configuración propia de unogroup-app
# ----------------------------------------------------------
ensambles:

  adapter:
    solutionone:
      base-url: ${SOLUTIONONE_BASE_URL:https://data.solution1.us}
      token-path: ${SOLUTIONONE_TOKEN_PATH:/api/v2/user/token}
      upload-path: ${SOLUTIONONE_UPLOAD_PATH:/api/v2/user/files/upload}
      # Basic Auth — vacíos por defecto para forzar configuración
      # explícita vía Secret Manager antes de poder llamar a Solution One real.
      usuario: ${SOLUTIONONE_USUARIO:}
      password: ${SOLUTIONONE_PASSWORD:}
      # Prefijo común de ruta — SolutionOneFileNaming agrega /create o
      # /update según la acción (ver §1.4.7; historial en Bitácora de
      # Diseño §3.3, C3).
      ruta-base: ${SOLUTIONONE_RUTA_BASE:siman}
      # Confirmado en Diseño §6.3 — debe ser true en producción; false
      # expone el sistema a 403 cuando la carpeta padre no existe todavía.
      # Configurable (no fijo) a pedido — usar con cuidado: cambiarlo a
      # false sin que la carpeta destino exista se traduce en 403, que
      # puede confundirse con un problema de permisos.
      mkdir-parents: ${SOLUTIONONE_MKDIR_PARENTS:true}
      reintentos:
        # Síncronos, en el mismo hilo (Diseño §2.11) — sin scheduler,
        # sin tabla de polling. Java 21 + virtual threads hacen barato
        # el Thread.sleep entre intentos.
        max-intentos: ${SOLUTIONONE_MAX_INTENTOS:5}
        backoff-inicial-ms: ${SOLUTIONONE_BACKOFF_MS:1000}
        backoff-multiplicador: 2

  # Endpoint interno que recibe la notificación del Orquestador
  # (payload_enriquecido completo).
  # ⚠ TBD (Bitácora de Diseño, F8): mecanismo de autenticación entre
  # servicios dentro del clúster — hoy no hay ninguno definido más
  # allá de que el Service es ClusterIP (no alcanzable desde fuera
  # del clúster). Si se decide agregar algo (ej. token compartido,
  # mTLS interno), la configuración iría aquí.
  notificacion:
    autenticacion-habilitada: ${NOTIFICACION_AUTH_HABILITADA:false}   # ⚠ TBD — ver Diseño F8

  # Cliente de callback hacia orquestador-app — ver Diseño §2.4, §1.4.8
  # de este documento. Se invoca al terminar de procesar, con el
  # resultado final y las transacciones[] (incluyendo AUTH_TOKEN).
  callback:
    orquestador-url: ${ORQUESTADOR_APP_URL:http://svc-orquestador.ensambles.svc.cluster.local}
    # Más intentos que el reintento corto de la notificación entrante
    # (blips de red interna), sin
    # llegar al backoff largo de Solution One (tercero externo inestable,
    # no comparable con un blip dentro del clúster). Si se agotan los 5
    # intentos, el resultado se registra en logs a nivel ERROR (formato
    # estructurado, recuperable manualmente) y queda en manos de la
    # reconciliación (zona 3, Diseño §2.11 / Bitácora de Diseño F16) como red de seguridad final.
    reintentos:
      max-intentos: ${CALLBACK_MAX_INTENTOS:5}
      backoff-inicial-ms: ${CALLBACK_BACKOFF_MS:500}
      backoff-multiplicador: 2

logging:
  level:
    root: INFO
    com.siman.ensambles.unogroup: ${LOG_LEVEL_APP:INFO}
    # Nunca loguear payload completo con PII en INFO o superior.
    # A nivel DEBUG, enmascarado (nunca en texto plano).
```

**Diferencias frente al `application.yml` de `orquestador-app` (§4):**
- **`unogroup-app` no tiene sección `spring.datasource`/`spring.jpa`** — no accede a MySQL de ninguna forma. Confirma en configuración lo que ya establece el Diseño (§2.3): es un servicio sin estado.
- **Solo `unogroup-app` tiene la sección `adapter.solutionone`** con credenciales — confirma lo que el diagrama de infraestructura ya mostraba: solo este pod accede a Secret Manager para las credenciales de UnoGroup.
- **No hay sección de Pub/Sub** — `unogroup-app` nunca consume eventos, solo recibe la notificación interna del Orquestador.
- **`mkdir-parents` es configurable vía variable de entorno** (`SOLUTIONONE_MKDIR_PARENTS`, default `true`) — el default sigue siendo `true` para no reproducir por accidente el escenario de 403 documentado en el Diseño §6.3, pero queda como interruptor disponible si el equipo lo necesita.
- **`notificacion.autenticacion-habilitada` queda en `false` por defecto** — no es la decisión final; F8 (Bitácora de Diseño) sigue sin resolver, y queda como interruptor explícito para cuando se decida.
- **Aparece `ensambles.callback`** — la config del cliente Feign que reporta el resultado de vuelta al Orquestador. Su política de reintentos queda deliberadamente sin decidir (ver comentario en el YAML).

---

# 6. Dependencias Maven

`orquestador-app` no depende de `spring-cloud-gcp-starter-pubsub` — con el mecanismo de entrada confirmado como *push vía Ingress* (Diseño §2.1), no consume la API de suscriptor de GCP en absoluto: Pub/Sub le habla por HTTP como a cualquier otro cliente REST. En su lugar, depende de `spring-boot-starter-oauth2-resource-server` para **validar el JWT que Pub/Sub adjunta a cada push** (ver §4). Tampoco depende de `com.h2database:h2` — no hay ningún perfil que necesite una base de datos en memoria; el ambiente de prueba real ya está disponible.

## 6.1 `orquestador-app/pom.xml`

```xml
<project>
  <modelVersion>4.0.0</modelVersion>

  <!-- Sin parent interno (ensambles-parent eliminado — ver §1.1) —
       este repositorio es un proyecto Maven standalone, de un solo módulo. -->
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <!-- Versión exacta de Spring Boot 4.x a confirmar al momento de
         iniciar la implementación (release train en movimiento). -->
    <version>4.1.0</version>
    <relativePath/>
  </parent>

  <groupId>com.siman.ensambles</groupId>
  <artifactId>orquestador-app</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>jar</packaging>

  <properties>
    <java.version>21</java.version>
    <!-- Spring Cloud release train compatible con la versión de Spring
         Boot elegida — verificar contra la matriz de compatibilidad
         antes de fijarla (spring.io/projects/spring-cloud). Debe
         mantenerse igual a la de unogroup-app/pom.xml (§6.2) — ya no
         hay un pom padre común que lo imponga automáticamente. -->
    <spring-cloud.version>2025.1.2</spring-cloud.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <!-- spring-boot-dependencies ya viene gestionado por el parent
           spring-boot-starter-parent — solo hace falta importar aquí
           el BOM de Spring Cloud. -->
      <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-dependencies</artifactId>
        <version>${spring-cloud.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <!-- Sin coordenada interna de módulo común (ensambles-common
           eliminado, ver §1.1) — orquestador-app y unogroup-app no
           dependen entre sí en absoluto. -->
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <!-- MySQL 8.0+ -->
    <dependency>
      <groupId>com.mysql</groupId>
      <artifactId>mysql-connector-j</artifactId>
      <scope>runtime</scope>
    </dependency>

    <!-- Cliente hacia unogroup-app (notificación con payload_enriquecido)
         y receptor del callback de vuelta -->
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-starter-openfeign</artifactId>
    </dependency>

    <!-- Validación del JWT que Pub/Sub adjunta a cada push (ver §4).
         Los tokens de identidad de Pub/Sub son JWT estándar firmados
         por Google — se validan como cualquier JWT de un resource
         server, contra el JWK set público de Google. -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
    </dependency>

    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <optional>true</optional>
    </dependency>
    <dependency>
      <groupId>org.mapstruct</groupId>
      <artifactId>mapstruct</artifactId>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

## 6.2 `unogroup-app/pom.xml`

```xml
<project>
  <modelVersion>4.0.0</modelVersion>

  <!-- Sin parent interno (ensambles-parent eliminado, §1.1) —
       este repositorio es un proyecto Maven standalone, de un solo módulo.
       Vive en su propio repositorio Git, independiente de orquestador-app. -->
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
    <relativePath/>
  </parent>

  <groupId>com.siman.ensambles</groupId>
  <artifactId>unogroup-app</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>jar</packaging>

  <properties>
    <java.version>21</java.version>
    <!-- Debe mantenerse igual a la de orquestador-app/pom.xml (§6.1) —
         ya no hay un pom padre común que lo imponga automáticamente. -->
    <spring-cloud.version>2025.1.2</spring-cloud.version>
    <mapstruct.version>1.6.3</mapstruct.version>
    <!-- Solo para pruebas — no forman parte del contrato ni del runtime. -->
    <wiremock.version>3.9.2</wiremock.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-dependencies</artifactId>
        <version>${spring-cloud.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
        <version>${mapstruct.version}</version>
      </dependency>
      <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct-processor</artifactId>
        <version>${mapstruct.version}</version>
      </dependency>
      <dependency>
        <groupId>org.wiremock</groupId>
        <artifactId>wiremock-standalone</artifactId>
        <version>${wiremock.version}</version>
        <scope>test</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <!-- Sin dependencia a ensambles-common (eliminado, §1.1) —
         los enums de dominio que necesita (EstadoInterno, TrackingStatus)
         se definen localmente en unogroup-app/enums, ver §3. -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <!-- Añadida durante la implementación (no estaba en una versión anterior
         de esta lista): expone /actuator/health/readiness y
         /actuator/health/liveness, referenciados por los probes del
         Deployment (§7.3) — sin esta dependencia esos endpoints no existen. -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <!-- Cliente hacia Solution One (ver §5, feign.client.config.solutionOneClient)
         y hacia el callback de orquestador-app (feign.client.config.orquestadorCallbackClient) -->
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-starter-openfeign</artifactId>
    </dependency>
    <!-- Añadida durante la implementación: spring-cloud-starter-openfeign no
         trae feign-jackson por defecto (usa los HttpMessageConverters de
         Spring) — se necesita explícitamente como fallback dentro de
         SolutionOneBinaryEncoder (ver más abajo) para las llamadas del mismo
         cliente que sí son JSON (ej. GET /api/v2/user/token). -->
    <dependency>
      <groupId>io.github.openfeign</groupId>
      <artifactId>feign-jackson</artifactId>
    </dependency>
    <!-- Añadida durante la implementación: Spring Boot 4.x usa internamente
         un ObjectMapper de "Jackson 3" (tools.jackson), incompatible con
         com.fasterxml.jackson.databind — no hay bean Spring del tipo
         Jackson 2 para autowire. Como SolutionOneCreatePayload/UpdatePayload
         usan anotaciones Jackson 2 (mismo Jackson que feign-jackson, arriba),
         SolicitudProcesamientoService construye su propio ObjectMapper
         Jackson 2 en vez de depender de la autoconfiguración de Spring —
         esta dependencia trae jackson-datatype-jsr310 para serializar los
         campos Instant con ese ObjectMapper. -->
    <dependency>
      <groupId>com.fasterxml.jackson.datatype</groupId>
      <artifactId>jackson-datatype-jsr310</artifactId>
      <version>2.21.4</version>
    </dependency>

    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <optional>true</optional>
    </dependency>
    <dependency>
      <groupId>org.mapstruct</groupId>
      <artifactId>mapstruct</artifactId>
    </dependency>
    <dependency>
      <groupId>org.mapstruct</groupId>
      <artifactId>mapstruct-processor</artifactId>
      <scope>provided</scope>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <!-- Añadida durante la implementación: simula Solution One y el
         endpoint de callback de orquestador-app en los tests de
         SolutionOneRetryPolicy y OrquestadorCallbackSender — no forma
         parte del contrato ni del runtime (scope test). -->
    <dependency>
      <groupId>org.wiremock</groupId>
      <artifactId>wiremock-standalone</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <configuration>
          <annotationProcessorPaths>
            <path>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
            </path>
            <path>
              <groupId>org.mapstruct</groupId>
              <artifactId>mapstruct-processor</artifactId>
              <version>${mapstruct.version}</version>
            </path>
          </annotationProcessorPaths>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

`spring-cloud-starter-openfeign` trae por defecto un encoder basado en Jackson (JSON), pero la carga hacia Solution One es un **body binario** (Diseño §2.9/§6.1) — ninguna de las dependencias listadas arriba resuelve eso automáticamente: hace falta un `Encoder` de Feign personalizado dentro de `unogroup-app/client`. Contra el ambiente de prueba real, **`Content-Type: application/json` funciona** para ese body binario — no hace falta `application/octet-stream` ni una librería adicional (`feign-okhttp` u otra). Ejemplo del encoder:

```java
package com.siman.ensambles.unogroup.client;

import feign.RequestTemplate;
import feign.codec.Encoder;
import feign.jackson.JacksonEncoder;
import java.lang.reflect.Type;

public class SolutionOneBinaryEncoder implements Encoder {
    private final Encoder fallback = new JacksonEncoder(); // para otras llamadas del mismo cliente (ej. token)

    @Override
    public void encode(Object requestBody, Type bodyType, RequestTemplate template) {
        if (requestBody instanceof byte[] bytes) {
            template.body(bytes, null);
            template.header("Content-Type", "application/json"); // confirmado contra ambiente de prueba real
        } else {
            fallback.encode(requestBody, bodyType, template);
        }
    }
}
```

---

# 7. Contenerización y despliegue en Kubernetes

**Decisión:** Dockerfile y manifiestos de Kubernetes describen cómo corre el código de este documento — no son recursos de infraestructura compartida (eso es Terraform: clúster, instancia, tópico, secretos como *servicio*, ver §8). Se documentan aquí, junto al código que despliegan, **dentro del mismo repositorio Git de cada app** (§1.1) — no centralizados en una carpeta de infra separada ni en un tercer repositorio.

**Repositorio `orquestador-app`:**

```
orquestador-app/
├── pom.xml
├── src/
├── Dockerfile
└── k8s/
    ├── deployment.yaml
    └── service.yaml
```

**Repositorio `unogroup-app`:**

```
unogroup-app/
├── pom.xml
├── src/
├── Dockerfile
└── k8s/
    ├── base/
    │   ├── deployment.yaml
    │   ├── service.yaml
    │   ├── configmap.yaml
    │   ├── secret.yaml
    │   ├── serviceaccount.yaml
    │   └── kustomization.yaml
    └── overlays/
        ├── local/
        │   ├── kustomization.yaml
        │   ├── deployment-patch.yaml
        │   └── configmap-patch.yaml
        └── prod/
            ├── kustomization.yaml
            ├── deployment-patch.yaml
            ├── serviceaccount-patch.yaml
            └── configmap-patch.yaml
```

> ⚠ **Estado de `orquestador-app/k8s` sin confirmar.** La columna `orquestador-app` de arriba es la estructura originalmente diseñada (manifiestos planos). `unogroup-app/k8s` sí evolucionó a **Kustomize** (`base/` + `overlays/{local,prod}`, detalle en §7.3) durante la implementación — queda pendiente confirmar si `orquestador-app/k8s` tuvo una evolución equivalente (ver Bitácora de Implementación §2.2, ítem 7 de §4).

## 7.1 Dockerfile — `orquestador-app` (y `unogroup-app`, mismo patrón)

```dockerfile
# ============================================================
# Dockerfile — en la raíz del repositorio orquestador-app
# Build multi-stage: compila con Maven, corre sobre JRE 21 slim.
# Mismo patrón, en su propio repositorio, para unogroup-app/Dockerfile
# (cambia únicamente el artifactId del jar resultante).
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Sin pom.xml padre que copiar ni reactor Maven multi-módulo
# (ensambles-parent eliminado, §1.1) — este repositorio contiene
# un único proyecto Maven standalone (§6.1), así que el build es
# el de cualquier proyecto Spring Boot de un solo módulo: sin -pl
# (build de un módulo dentro de un reactor) ni -am (also-make).
COPY pom.xml .
RUN mvn -q dependency:go-offline

COPY src src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Usuario no root — estándar de seguridad, no ejecutar como root en el contenedor.
RUN addgroup -S ensambles && adduser -S ensambles -G ensambles
USER ensambles

COPY --from=build /build/target/orquestador-app-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

## 7.2 Kubernetes — `orquestador-app`

> ⚠ **Sin confirmar contra el repositorio real** — ver nota al inicio de §7. El YAML de abajo es la estructura originalmente diseñada; puede no reflejar el estado actual del repositorio de `orquestador-app`.

```yaml
# orquestador-app/k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: orquestador-app
  namespace: ensambles
spec:
  replicas: 2   # múltiples réplicas — el reintento corto y el job de
                # reconciliación (§4) ya están diseñados para tolerar esto
                # sin duplicar trabajo, a diferencia del scheduler descartado
                # en el Diseño §2.11.
  selector:
    matchLabels:
      app: orquestador-app
  template:
    metadata:
      labels:
        app: orquestador-app
    spec:
      serviceAccountName: orquestador-app-sa   # Workload Identity — acceso a
                                                  # Cloud SQL vía IAM, sin claves JSON
      containers:
        - name: orquestador-app
          image: TBD_REGISTRY/orquestador-app:TBD_TAG
          ports:
            - containerPort: 8080
          envFrom:
            - configMapRef:
                name: orquestador-app-config      # PUBSUB_PUSH_AUDIENCE, UNOGROUP_APP_URL, etc.
            - secretRef:
                name: orquestador-app-db-secret   # DB_USERNAME, DB_PASSWORD
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            initialDelaySeconds: 10
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            initialDelaySeconds: 20

        # Sidecar — mismo patrón confirmado en el diagrama de infraestructura:
        # la app le habla a 127.0.0.1, el proxy resuelve el túnel real hacia
        # la instancia de Cloud SQL ya existente (ver §8).
        - name: cloud-sql-auth-proxy
          image: gcr.io/cloud-sql-connectors/cloud-sql-proxy:latest
          args:
            - "--structured-logs"
            - "--port=3306"
            - "TBD_PROJECT:TBD_REGION:TBD_INSTANCIA"   # instancia Cloud SQL existente
          securityContext:
            runAsNonRoot: true
---
# orquestador-app/k8s/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: svc-orquestador
  namespace: ensambles
spec:
  type: ClusterIP
  selector:
    app: orquestador-app
  ports:
    - port: 80
      targetPort: 8080
```

**Nota:** `svc-orquestador` es el destino de Ingress (Diseño §2.1) — el recurso de Ingress en sí (con su regla de host/path hacia este Service) se define al configurar el balanceador de entrada del proyecto, fuera del alcance de este documento.

## 7.3 Kubernetes — `unogroup-app`

`unogroup-app/k8s` usa **Kustomize**: una capa `base/` con los recursos comunes, y overlays por ambiente (`local`, `prod`) que la parchean. El `namespace` no se repite en cada manifiesto — lo fija una sola vez `base/kustomization.yaml`. Los valores de imagen/tag no son un placeholder de texto dentro del YAML del Deployment — son un transformer de Kustomize (`images:`) en cada overlay, sustituido en CI vía `kustomize edit set image`.

```yaml
# unogroup-app/k8s/base/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: unogroup-app
spec:
  replicas: 2
  selector:
    matchLabels:
      app: unogroup-app
  template:
    metadata:
      labels:
        app: unogroup-app
    spec:
      serviceAccountName: unogroup-app-sa   # Workload Identity — distinto del
                                             # SA del Orquestador: solo este
                                             # tiene permiso IAM sobre los
                                             # secretos de Solution One.
      containers:
        - name: unogroup-app
          image: unogroup-app:base   # nombre/tag reales via `images:` en overlays/*
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8080
          envFrom:
            - configMapRef:
                name: unogroup-app-config          # SOLUTIONONE_BASE_URL, ORQUESTADOR_APP_URL, timeouts, etc.
            - secretRef:
                name: unogroup-app-solutionone-secret  # SOLUTIONONE_USUARIO, SOLUTIONONE_PASSWORD
          resources:
            requests:
              cpu: 250m
              memory: 384Mi
            limits:
              cpu: 1
              memory: 768Mi
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            initialDelaySeconds: 10
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            initialDelaySeconds: 20
        # Sin sidecar cloud-sql-auth-proxy — unogroup-app no accede a MySQL
        # (Diseño §2.3/§2.4). Su Deployment queda con un solo contenedor.
---
# unogroup-app/k8s/base/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: svc-unogroup
spec:
  type: ClusterIP   # solo alcanzable dentro del clúster — nunca expuesto
                     # fuera de GKE (Diseño §2.3)
  selector:
    app: unogroup-app
  ports:
    - port: 80
      targetPort: 8080
---
# unogroup-app/k8s/base/serviceaccount.yaml
# Workload Identity — distinto del SA del Orquestador: solo este tiene
# permiso IAM sobre los secretos de Solution One en GCP Secret Manager.
# El binding GSA <-> KSA (anotación iam.gke.io/gcp-service-account) es
# específico de cada ambiente/proyecto GCP y se agrega vía overlays/prod.
apiVersion: v1
kind: ServiceAccount
metadata:
  name: unogroup-app-sa
---
# unogroup-app/k8s/base/configmap.yaml
# Valores por defecto — reflejan los defaults de src/main/resources/application.yml.
# Los overlays sobreescriben solo las claves que difieren por ambiente.
apiVersion: v1
kind: ConfigMap
metadata:
  name: unogroup-app-config
data:
  SERVER_PORT: "8080"
  SOLUTIONONE_BASE_URL: "https://data.solution1.us"
  SOLUTIONONE_TOKEN_PATH: "/api/v2/user/token"
  SOLUTIONONE_UPLOAD_PATH: "/api/v2/user/files/upload"
  SOLUTIONONE_RUTA_BASE: "siman"
  SOLUTIONONE_MKDIR_PARENTS: "true"
  SOLUTIONONE_MAX_INTENTOS: "5"
  SOLUTIONONE_BACKOFF_MS: "1000"
  SOLUTIONONE_TIMEOUT_CONN_MS: "3000"
  SOLUTIONONE_TIMEOUT_READ_MS: "5000"
  SOLUTIONONE_FEIGN_LOG_LEVEL: "basic"
  DARM_SERVICE_TYPE: "armado"   # ⚠ TBD F18 — ver Bitácora de Diseño
  NOTIFICACION_AUTH_HABILITADA: "false"   # ⚠ TBD F8 — ver Bitácora de Diseño
  ORQUESTADOR_APP_URL: "http://svc-orquestador.ensambles.svc.cluster.local"
  CALLBACK_TIMEOUT_CONN_MS: "2000"
  CALLBACK_TIMEOUT_READ_MS: "3000"
  CALLBACK_FEIGN_LOG_LEVEL: "basic"
  CALLBACK_MAX_INTENTOS: "5"
  CALLBACK_BACKOFF_MS: "500"
  LOG_LEVEL_APP: "INFO"
---
# unogroup-app/k8s/base/secret.yaml
# PLACEHOLDER — no reales. Nunca commitear usuario/password reales aquí.
# Cada ambiente debe reemplazar estos valores antes de aplicar (kubectl
# create secret --dry-run + apply, sealed-secrets, SOPS, o un ExternalSecret
# respaldado por GCP Secret Manager — unogroup-app-sa ya tiene el IAM
# necesario vía Workload Identity, ver serviceaccount.yaml).
apiVersion: v1
kind: Secret
metadata:
  name: unogroup-app-solutionone-secret
type: Opaque
stringData:
  SOLUTIONONE_USUARIO: "CHANGE_ME"
  SOLUTIONONE_PASSWORD: "CHANGE_ME"
---
# unogroup-app/k8s/base/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: ensambles   # un solo lugar para el namespace, no repetido por manifiesto
resources:
  - serviceaccount.yaml
  - configmap.yaml
  - secret.yaml
  - deployment.yaml
  - service.yaml
labels:
  - pairs:
      app.kubernetes.io/name: unogroup-app
      app.kubernetes.io/part-of: ensambles
    includeSelectors: false
```

**Overlays por ambiente:**

```yaml
# unogroup-app/k8s/overlays/local/kustomization.yaml — clúster local (kind/minikube)
resources: [../../base]
patches:
  - path: deployment-patch.yaml   # replicas: 1, recursos más chicos, imagePullPolicy: IfNotPresent
  - path: configmap-patch.yaml    # LOG_LEVEL_APP=DEBUG, logs de Feign en "full"
images:
  - name: unogroup-app
    newTag: local   # build local: `docker build -t unogroup-app:local .`

# unogroup-app/k8s/overlays/prod/kustomization.yaml — GKE
resources: [../../base]
patches:
  - path: deployment-patch.yaml       # replicas: 3, recursos de prod, imagePullPolicy: Always
  - path: serviceaccount-patch.yaml   # anotación iam.gke.io/gcp-service-account (Workload Identity)
  - path: configmap-patch.yaml        # punto único para SOLUTIONONE_BASE_URL si prod usa un host distinto
images:
  - name: unogroup-app
    newName: TBD_REGISTRY/unogroup-app   # TODO: path real de Artifact Registry
    newTag: TBD_TAG                       # TODO: SHA/tag del build en CI
```

**Diferencia deliberada, visible en el manifiesto:** solo `unogroup-app` monta `unogroup-app-solutionone-secret` y su `ServiceAccount` es distinto del Orquestador — confirma en Kubernetes lo que ya establecimos en el Diseño (§1.1): el Orquestador nunca tiene acceso a las credenciales de UnoGroup. **Sin sidecar `cloud-sql-auth-proxy`** — `unogroup-app` queda con un único contenedor, sin acceso a base de datos en absoluto (a diferencia de `orquestador-app`, §7.2).

**Placeholders `TBD_*` restantes, ahora aislados en `overlays/prod`** (ya no mezclados con el resto del Deployment): `newName`/`newTag` de imagen en `overlays/prod/kustomization.yaml`, el `TBD_PROJECT_ID` de la anotación de Workload Identity en `overlays/prod/serviceaccount-patch.yaml`, y las credenciales `CHANGE_ME` de `base/secret.yaml` — todos dependen de decisiones de infraestructura (§8) o de CI/CD que todavía no se han tomado.

**Comandos de referencia** (build local, aplicar overlay, port-forward, redeploy) — ver `README.md` del repositorio, que los documenta con el detalle operativo completo; no se duplican aquí para no tener dos fuentes de verdad sobre el mismo comando.

---

# 8. Infraestructura Terraform — nota de alcance

**Este documento no cubre la infraestructura gestionada por Terraform** — se documentará por separado. Acotación de alcance:

- **El clúster de GKE y la instancia de Cloud SQL (MySQL) ya existen** — no son recursos que este proyecto deba crear. Lo que sí falta a nivel de infraestructura para este proyecto específico: base de datos y usuario nuevos dentro de la instancia existente, namespace de Kubernetes, los `ServiceAccount`/Workload Identity referenciados en §7.2/§7.3, tópico y suscripción de Pub/Sub, y las entradas de Secret Manager para credenciales de UnoGroup y de base de datos (sincronizadas hacia los `Secret` de Kubernetes que consumen los manifiestos de §7).

---

# 9. Trabajo pendiente

Lo que sigue son los puntos que hoy afectan el código y siguen sin cerrar. El historial completo de cómo se llegó a este estado — hallazgos de auditoría, correcciones sobre versiones previas de este documento, y los ítems ya resueltos — vive en `HUENSA-001_Implementacion_Bitacora_Decisiones_Modulo_Integracion_Ensamble.md` §4. Las preguntas que dependen de un tercero o de una decisión de negocio/arquitectura (no solo de escribir código) están en la Bitácora de Diseño, Índice Maestro de Preguntas Abiertas (`F1`–`F20`, `A1`–`A7`, `C1`–`C7`).

1. Construir el ruteo real de `actualizacion` para `origen=guias` (§1.4.1) — hoy siempre se trata como creación.
2. Construir el paquete `reconciliation` (§1.4.5) — hoy no existe en el código.
3. ~~Decidir si se agrega `url`/`metodoHttp` al contrato del callback o se relaja el `NOT NULL` de `ensamble_bitacora_partner` (§2, §3)~~ — **Resuelto 2026-07-29 vía v3:** el contrato v3 (`ResultadoSolicitud.transacciones[]`) reemplaza `intentos[]` por completo con un modelo de transacción HTTP genérica; `request.method`/`request.url` son obligatorios, así que `request_method`/`request_url` (`ensamble_bitacora_partner`) siempre se pueblan con valores reales. `ensamble_bitacora_partner` se recreó (`V4__recreate_ensamble_bitacora_partner_v3.sql`) y `BitacoraPartner`/`SolicitudEnsamble` (esta última pierde `payloadPartner`/`nombreArchivo`) se actualizaron. ~~**Pendiente real remanente:** `unogroup-app` debía implementar el interceptor de captura de transacciones HTTP.~~ — **Resuelto (confirmado 2026-07-30):** `unogroup-app` implementa el interceptor vía `CapturingFeignClient` (envoltorio sobre el `Client` de Feign — method/url/headers/body enmascarados, response o error), ver §1.4.6 de este documento.
4. `EventoGuiasMapper` — comportamiento exacto si `items[]` viene vacío o con SKUs duplicados dentro del mismo evento.
5. Autenticación y política de reintento de `WmsShipmentClient` (§1.4.4c) — sin definir.
6. Agregar validación `@Size` a los DTOs de entrada de `unogroup-app`, para reflejar los `maxLength` del contrato OpenAPI.
7. Confirmar si `orquestador-app/k8s` migró a Kustomize igual que `unogroup-app/k8s` (§7.2, §7.3), o sigue con manifiestos planos.
8. Valores reales de imagen/registro, proyecto/región/instancia de Cloud SQL, nombres de Secret/ConfigMap — placeholders `TBD_*` en ambos repositorios, depende de infraestructura (§8).
9. Pipelines de CI/CD — un pipeline por repositorio (build, test, imagen, tag); no se ha definido herramienta ni configuración.
10. Autenticación de los endpoints internos de notificación y callback (hoy deshabilitada en ambos sentidos) — ver Diseño F8.
11. Cómo detecta la reconciliación la zona 3 (UnoGroup procesó pero el callback se perdió) — ver Diseño F16, bloqueado además por el ítem 2 de esta lista.
12. Schema del mensaje de Tracking/Beetrack (actualización de entrega) — ver Diseño F13.
13. ~~Reflejar `respuesta_body`/`error_mensaje` de `ensamble_bitacora_partner` (§2, §3) en el diagrama entidad-relación de Requerimientos §2.6.1~~ — sin objeto tras v3: esas columnas puntuales del modelo v2 ya no existen; el ER de Requerimientos §2.6.1 ya refleja el modelo v3 completo (`request_*`/`response_*`/`error_*`).
14. Confirmar con el equipo de Diseño si `SolutionOneCreatePayload`/`SolutionOneUpdatePayload` (openapi) se mantienen como documentación de referencia del formato esperado dentro de `request.body`, o se retiran del contrato por quedar sin uso formal (`oneOf`) ya que `body` es ahora un string genérico.
15. Implementar la consulta de "último payload enviado"/"último archivo usado" desde `ensamble_bitacora_partner` (guía §4.3) — no agregada todavía porque ningún flujo actual de `orquestador-app` lee `payload_partner`/`nombre_archivo` (se confirmó al migrar); agregar cuando exista un consumidor real.
16. Definir política de retención/TTL para `ensamble_bitacora_partner` — más urgente en v3 por capturar bodies completos (mismo punto abierto que ya existía en v2, ver nota 6 del DDL en §2).
