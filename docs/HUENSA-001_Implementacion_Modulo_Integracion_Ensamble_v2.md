
# Implementación — Sistema de Ensambles (Orquestador + UnoGroup)

**Proyecto:** HUENSA-001 — Integración de Pedidos que Requieren Ensamble
**Documento:** Detalle técnico de implementación de la aplicación: estructura de repositorios, estructura de paquetes, DDL, entidades JPA, configuración Spring Boot, contrato OpenAPI. **La infraestructura (Terraform, GKE, Cloud SQL, Pub/Sub, Secret Manager) se documenta por separado** — ver nota en §7.
**Documento complementario:** `HUENSA-001_Diseno_Modulo_Integracion_Ensamble_v2.md` — principios, arquitectura de los dos microservicios, casos de uso, mapeo de campos y contrato OpenAPI. Léelo primero si buscas el *por qué* de una decisión; este documento es el *cómo*.
**Stack:** Java 21 · Spring Boot 4.x · MySQL 8.0+ (Cloud SQL, instancia ya existente) · GKE (clúster ya existente) · GCP Pub/Sub · OpenFeign · Lombok · Bean Validation · SLF4J

**Estado de este documento:** en construcción, sección por sección, en la misma sesión donde se decidieron los puntos que refleja. Las secciones marcadas ⚠ **TBD** son piezas que ya sabemos que faltan pero todavía no se han cerrado en detalle.

**Actualización de nomenclatura (esta sesión):** la instancia/schema de Cloud SQL donde vive este módulo se comparte con otros sistemas de Siman, no es exclusiva del módulo de ensambles. Las tres tablas (y sus constraints/índices/trigger) adoptan el prefijo `ensamble_` para evitar colisiones de nombre — ver DDL actualizado en §2 y `@Table(name = ...)` en las entidades JPA de §3. No hay cambio de columnas, tipos ni relaciones; ver el documento de Diseño §2.6 para el detalle de la decisión.

> **Corrección de reconciliación:** una versión intermedia de este documento había vuelto, sin nota explicativa, a un proyecto Maven multi-módulo (`ensambles-parent` agregando `orquestador-app`/`unogroup-app` como módulos de un mismo reactor). Eso contradecía la decisión ya confirmada de **dos repositorios Git independientes, sin `pom.xml` padre ni módulo común entre ellos** (ver §1.1). Esa decisión de repos separados sigue siendo la vigente — se restaura en esta versión, y la sección de módulos Maven vuelve a reflejarla.

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
9. Pendientes que afectan la implementación

---

# 1. Arquitectura de Repositorios

## 1.1 Repositorios Git y estructura de proyecto

> **Decisión confirmada, vigente: se elimina el proyecto Maven multi-módulo `ensambles-parent`.** `orquestador-app` y `unogroup-app` no viven como módulos de un mismo reactor Maven en un solo repositorio — son **dos proyectos independientes, cada uno con su propio repositorio Git**. No hay un `pom.xml` padre agregador, ni un directorio raíz común que los contenga a ambos: cada repositorio contiene un único proyecto Maven `jar`, autocontenido, con su propio historial de commits, versionado (tags/releases) y pipeline de CI/CD.

> Esto es una extensión natural de una decisión ya tomada en una sesión anterior: se había eliminado `ensambles-common`, el módulo Maven que compartía entidades JPA y enums de dominio entre los dos microservicios, porque ese motivo (base de datos compartida) ya no existía — nació cuando la base de datos era compartida entre los dos microservicios, y ese motivo desapareció cuando `unogroup-app` dejó de tener acceso a MySQL. Las entidades JPA ahora viven únicamente en `orquestador-app` (el único que las usa). Los 3 enums de dominio ya no se comparten vía un `.jar` común — cada app define los suyos localmente, con los mismos valores; la consistencia entre ambos la garantiza el contrato OpenAPI (`HUENSA-001_openapi_v2.yaml`, que ya tiene esos valores como `enum:` en sus schemas), no una dependencia de build compartida entre dos servicios que se diseñaron para evolucionar por separado. Sin ningún artefacto Java compartido entre ambos, mantenerlos en un solo repositorio multi-módulo dejó de aportar valor: el reactor Maven de un monorepo solo tiene sentido cuando hay módulos que se construyen o versionan juntos, y aquí ya no los había — solo los mantenía juntos la conveniencia de un solo `git clone`. Separar los repositorios hace explícito, también a nivel de control de versiones, lo que ya era cierto a nivel de código: son dos servicios que se diseñaron para evolucionar, versionarse y desplegarse por separado (Diseño §1.1, §2.3).

**Decisión confirmada:** dos repositorios Git independientes, uno por microservicio, cada uno con su propio proyecto Maven `jar` de Spring Boot, sin ningún módulo ni `pom.xml` padre común entre ellos.

```
repositorio: orquestador-app                    repositorio: unogroup-app
(git remote propio)                             (git remote propio)

orquestador-app/                                 unogroup-app/
├── pom.xml            (standalone, sin parent   ├── pom.xml            (standalone, sin parent
│                        interno — ver §6.1)      │                        interno — ver §6.2)
├── src/                                          ├── src/
├── Dockerfile                                     ├── Dockerfile
└── k8s/                                           └── k8s/
    ├── deployment.yaml                                ├── deployment.yaml
    └── service.yaml                                    └── service.yaml
```

**Consecuencias directas de este cambio, que se reflejan en el resto de este documento:**
- **§6 (Dependencias Maven):** no hay `pom.xml` padre. Cada proyecto declara su propio `<parent>` apuntando directamente a `spring-boot-starter-parent`, e importa el BOM de Spring Cloud en su propio `dependencyManagement`. Ver §6.1 y §6.2.
- **§7.1 (Dockerfile):** el build no copia un `pom.xml` padre ni usa `-pl` (build de un módulo dentro de un reactor) — cada `Dockerfile` vive en la raíz de su propio repositorio y compila un proyecto Maven normal, de un solo módulo.
- **CI/CD:** cada repositorio tiene su propio pipeline (build, test, imagen de contenedor, tag) e implica versiones independientes de la imagen para `orquestador-app` y `unogroup-app` — ya no hay un único build que produzca ambos artefactos a la vez.
- **Consistencia de contrato:** la fuente de verdad entre ambos sigue siendo el contrato OpenAPI (`HUENSA-001_openapi_v2.yaml`), nunca una dependencia de build compartida.

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
│                              # el callback de unogroup-app (nuevo, esta sesión — ver §1.4.4b)
├── service/                  # idempotencia (ordenId+sku), fan-out de updates sin sku,
│                              # transición de estado interno hasta ENRIQUECIDA, y ahora
│                              # también la transición final al recibir el callback
├── enrichment/                # Paso central y obligatorio para WMS (ya no opcional/"consulta:
│                              # RMS" — cambio de arquitectura esta sesión): WmsShipmentClient
│                              # consulta GET /wms/dw/v1/shipment/get-shipment/{whseId}/
│                              # {externOrderKey} tanto en creación como en actualización;
│                              # filtra orderdetails[] por ext_udf_str10 no nulo para decidir
│                              # qué líneas generan sub-orden y con qué flujo (ASSE/ENSA) cada
│                              # una. Para Guías, sigue resolviendo los campos marcados
│                              # "consulta: RMS" en el Diseño §4.3 (item_brand/category/description)
├── client/                    # @FeignClient hacia unogroup-app — POST /internal/unogroup/solicitudes
│                              # (ahora con payload_enriquecido completo, no una referencia)
├── domain/                     # entidades JPA (SolicitudEnsamble, SolicitudHistorial,
│   │                          # BitacoraPartner) y enums de dominio (EstadoInterno,
│   │                          # TrackingStatus, FlujoEnsamble) — antes en ensambles-common,
│   │                          # movidas aquí esta sesión al eliminar ese módulo (§1.1):
│   │                          # `unogroup-app` nunca las usó desde que dejó de tener
│   │                          # acceso a base de datos, así que ya no había razón para
│   │                          # compartirlas vía un módulo Maven común.
│   └── enums/
├── repository/                 # Spring Data JPA sobre las entidades de domain/
│                              # (findByOrdenIdAndSku, findByOrdenId — fan-out). Único de los
│                              # dos módulos con acceso a base de datos (esta sesión).
├── reconciliation/            # job periódico (ver Diseño §2.11) — filas atascadas en
│                              # RECIBIDA, ENRIQUECIDA sin notificar, o notificada sin callback
└── config/
```

## 1.3 Estructura de paquetes — `unogroup-app`

> **Cambio de esta sesión:** desaparece el paquete `repository/` por completo — `unogroup-app` ya no tiene acceso a base de datos (ver Diseño §2.3/§2.4). Aparece `callback/` para reportar el resultado de vuelta al Orquestador. **Y se aplana `adapter/solutionone/`** — con UnoGroup ya sin ninguna otra responsabilidad de negocio (todo su código existe únicamente para hablarle a Solution One), anidar un paquete "adapter" dentro de un microservicio que en sí mismo es el adapter no protege nada; es capa sobre capa sin beneficio. Si mañana se reemplaza UnoGroup, no se cambia un adapter interno — se construye otro microservicio (decisión ya tomada esta sesión).

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
│                              # solicitudes/resultado, con el resultado final y el detalle de
│                              # cada intento (incluyendo AUTH_TOKEN) — ver Diseño §2.4
└── config/
```

## 1.4 Responsabilidad de cada capa

### 1.4.1 `orquestador-app` — `messaging`

- Recibe el `POST /internal/eventos` (push de Pub/Sub — ver `HUENSA-001_openapi_v2.yaml`).
- Decodifica `message.data` (base64) y lee `message.attributes.origen`/`tipo_evento` **antes** de deserializar el contenido — evita tener que "adivinar" el schema inspeccionando el JSON.
- Rutea al mapper correspondiente según `origen`+`tipo_evento`. **Cambio esta sesión:** para `origen=wms`, `flujo` ya no se lee ni se persiste como atributo de transporte — no aplica (confirmado, Diseño §2.5/§9.1 A1/§9.6 F11): ASSE/ENSA se determinan por línea, dentro del `enrichment`, después de consultar el shipment, nunca al momento de publicar. Para `origen=guias`, `flujo` (`CARM`/`TARM`/`DARM`) sigue siendo obligatorio y sí se persiste directo.
- ✅ **Resuelto (Diseño §9.6 F12/F17):** el ruteo lee directamente `message.attributes.tipo_evento` (`CREAR`/`UP05`/`UP06` para WMS; `creacion`/`actualizacion` para Guías) — no hace falta inferir la transición comparando contra el estado actual de la sub-orden.
- **`WmsEventoMapper` (cambio de arquitectura esta sesión):** para `origen=wms`, el mapper ya no construye la entidad directamente desde el payload — el payload (`EventoWmsCrear` en creación; `WmsUP05Payload`/`WmsUP06Payload` en actualización) es solo un disparador. El mapper extrae únicamente `whseId`+`externOrderKey`:
  - Creación y UP05: un solo `Head`, una sola extracción.
  - UP06: `Head` es un **arreglo de órdenes** — el mapper itera `Head[]` y produce una extracción de `whseId`+`externOrderKey` por cada orden del lote (fan-out de primer nivel). Cada una se procesa de forma independiente en los pasos siguientes.
  - Cada extracción se delega a `enrichment` (§1.4.3), que consulta el shipment y devuelve las líneas calificadas (`ext_udf_str10` no nulo) — de ahí sale el fan-out de segundo nivel (por línea/SKU), tanto para creación (nuevas entidades `RECIBIDA`) como para actualización (aplicado sobre sub-órdenes ya existentes, vía el fan-out del `service` descrito en §1.4.2).
- **Fan-out de creación para origen `guias` (sin cambios este ciclo):** el evento unificado `EventoGuias` (CARM/TARM/DARM) trae **un solo `ordenId` con `items[]`** — el aplicativo de Guías ya no publica un evento por SKU. `EventoGuiasMapper` itera `items[]` y produce una entidad `SolicitudEnsamble` (`estado_interno = RECIBIDA`) por cada elemento, todas bajo el mismo `ordenId`, antes de pasar cada una al `service` para enriquecimiento y notificación individual. Esto es un fan-out distinto al de WMS: para Guías ocurre en el `mapper`, leyendo directamente `items[]` del propio evento (sin consulta externa); para WMS ocurre en el `enrichment`, después de una consulta HTTP.

### 1.4.2 `orquestador-app` — `service`

- **Idempotencia de creación:** constraint única `(orden_id, sku)` en `ensamble_solicitud` — captura `DataIntegrityViolationException` y la traduce a resultado idempotente (no error, no notifica a `unogroup-app`), sin excepción — ver Diseño §2.6. **Corrección (2026-07-15):** este párrafo antes decía que la propia verificación de duplicado determinaba `accion=update`; eso contradecía el §2.6 y nunca estuvo implementado así. El valor real de `accion` viaja explícito en `SolicitudNotificacionRequest.accion` (`create`/`update`, contrato OpenAPI) — `create` desde `crear()` en el primer insert, `update` desde `aplicarActualizacion()` (ver línea siguiente). Con el fan-out de creación de Guías (§1.4.1) y el fan-out por línea de WMS (vía `enrichment`, §1.4.3), esta constraint también protege contra reintentos de entrega de Pub/Sub del mismo evento: cada `(ordenId, sku)` sigue siendo único aunque el evento completo se reprocese.
- **Fan-out de actualización:** si el evento no trae `sku` (o, para WMS, si el `enrichment` devuelve varias líneas calificadas para el mismo `ordenId`), busca todas las sub-órdenes bajo ese `ordenId` que califiquen y aplica el cambio a cada una — y notifica a `unogroup-app` con `accion=update` por cada sub-orden afectada (antes un gap: `aplicarActualizacion` no notificaba; corregido 2026-07-15). **Confirmado (esta sesión):** no hay despachos parciales, así que no hace falta cruzar contra `detail[]`/`pickDetail[]` del evento crudo — todas las sub-órdenes calificadas de la orden se actualizan juntas. **No confundir con el fan-out de creación de `EventoGuiasMapper` (§1.4.1)** — este actúa sobre sub-órdenes ya persistidas, aquel sobre `items[]` de un evento entrante.
- **Manejo de update huérfano:** si no existe ninguna sub-orden para el `ordenId`, no asume error inmediato — aplica ventana de tolerancia corta (condición de carrera con la creación, que puede no haberse procesado aún).
- Transiciona `estado_interno`: `RECIBIDA` → (enriquecimiento) → `ENRIQUECIDA`. Al completar el enriquecimiento, escribe el resultado en `payload_enriquecido` (columna separada de `payload_origen`, que se mantiene inmutable como crudo de auditoría — ver §2 y §3). Al llegar a `ENRIQUECIDA`, invoca `client` para notificar al UnoGroup.
- Registra en `SolicitudHistorial` cada transición de negocio — **no** el detalle de llamadas HTTP (eso vive exclusivamente en `ensamble_bitacora_partner`, poblada por este mismo servicio a partir del callback que reporta `unogroup-app`, ver §1.4.4b).

### 1.4.3 `orquestador-app` — `enrichment`

- **Cambio de arquitectura esta sesión — paso central y obligatorio para WMS, ya no opcional:** `WmsShipmentClient` consulta `GET /wms/dw/v1/shipment/get-shipment/{whseId}/{externOrderKey}` (`WmsShipmentDetail`, ver openapi) **siempre** que `WmsEventoMapper` (§1.4.1) entrega una extracción — tanto en creación como en actualización, sin excepción. Antes se asumía que el evento crudo ya traía (o casi traía) los datos de negocio, y esta consulta era el fallback para los pocos campos faltantes; ahora es la única fuente real.
- **Filtrado y determinación de flujo:** de la respuesta, filtra `orderdetails[]` por `ext_udf_str10` no nulo. Cada línea calificada determina su propio `FlujoEnsamble` (`ASSE`→`service_location=casa`, `ENSA`→`service_location=centro_distribucion`, vía la regla derivada en Diseño §4.1) — este es el momento exacto en que se resuelve A1/F11: el flujo nunca llega como atributo de transporte, se calcula aquí, por línea.
- **En creación:** cada línea calificada se convierte en una nueva entidad `SolicitudEnsamble` (`estado_interno = RECIBIDA` → `ENRIQUECIDA` tras completar el mapeo de campos).
- **En actualización:** las líneas calificadas identifican qué sub-órdenes ya persistidas (por `ordenId`+`sku`) deben transicionar `tracking_status` — delega al fan-out de actualización del `service` (§1.4.2).
- Para los campos que ni siquiera `WmsShipmentDetail` trae (`item_brand`, `item_category`, `item_description`, `customer_vip`, `latitud`/`longitud` — ver Diseño §4.1/§4.3, A5), este paquete sigue siendo responsable de la consulta a RMS como fallback adicional.
- Para Guías, sin cambios: resuelve los campos marcados `consulta: RMS` en el Diseño §4.3.
- Responsabilidad exclusiva de este paquete: **nunca** debe vivir lógica de enriquecimiento dentro de `messaging` ni de `service`, para mantener la responsabilidad de "completar datos" separada de "recibir evento" y de "orquestar transición de estado".
- ⚠ **Pendientes (nuevo, esta sesión, dejados abiertos a propósito):** fuente exacta de `tracking_order_time` (`adddate` vs. `orderdate` de `WmsShipmentDetail`) y de `tracking_dispatched_time`/`tracking_delivered_time` (campo `fecha` del payload crudo vs. hora de recepción del evento) — ver Diseño §9.1 A6/A7.

### 1.4.4 `orquestador-app` — `client`

- `@FeignClient` hacia `unogroup-app`, endpoint `POST /internal/unogroup/solicitudes` (ver `HUENSA-001_openapi_v2.yaml`).
- Payload: **el `payload_enriquecido` completo, más el campo `accion` explícito** (`create`/`update`, ver §1.4.2) — no una referencia. UnoGroup ya no tiene dónde ir a buscar el contenido, y no tiene que inferir si es creación o actualización a partir de la forma del payload (ver Diseño §2.4).
- Reintento corto en el momento (2-3 intentos, backoff de segundos) ante fallas transitorias — ver Diseño §2.11. El job de `reconciliation` es la red de seguridad para el caso donde incluso este reintento corto falle del todo.
- No espera el resultado final en la respuesta — UnoGroup responde `202` de inmediato; el resultado llega después por el callback (§1.4.4b).

### 1.4.4b `orquestador-app` — `controller` (endpoint de callback)

> **Nuevo, esta sesión** — no existía en la versión anterior de este documento.

- Expone `POST /internal/orquestador/solicitudes/resultado` (nombre tentativo, contrato exacto pendiente — Diseño §9.6 F8), que recibe el callback de `unogroup-app` con el resultado final y el detalle de cada intento (`AUTH_TOKEN` incluido).
- Delega a `service`, que inserta cada intento como una fila de `ensamble_bitacora_partner` y transiciona `estado_interno` según `resultadoFinal` — este controller es la única vía por la que `ensamble_bitacora_partner` recibe datos (ver §1.4.2 y §2, ownership actualizado).

### 1.4.4c `orquestador-app` — `client/wms` (nuevo, esta sesión)

- `WmsShipmentClient` — `@FeignClient` hacia la API de WMS, `GET /wms/dw/v1/shipment/get-shipment/{whseId}/{externOrderKey}`. Vive junto a (o dentro de) `enrichment`, no dentro de `client/` — ese paquete es exclusivamente para la comunicación hacia `unogroup-app`, un contrato interno distinto con su propio ciclo de vida.
- Sin autenticación definida todavía — mismo pendiente estructural que el resto de credenciales externas (ver §7, placeholders `TBD_*`).
- Sin política de reintento definida todavía para esta llamada específica — a diferencia del cliente hacia `unogroup-app` (§1.4.4), que sí tiene reintento corto documentado.

### 1.4.5 `orquestador-app` — `reconciliation`

- Job periódico (orden de 15-30 min, valor exacto ⚠ TBD — Diseño §9.6 F7) que revisa filas atascadas en **tres** zonas (Diseño §2.11 — se agregó una tercera esta sesión):
  - `RECIBIDA` por más de N minutos sin pasar a `ENRIQUECIDA`.
  - `ENRIQUECIDA` por más de N minutos sin recibir el callback de UnoGroup.
  - ⚠ **TBD (Diseño §9.6 F16):** cómo distinguir "UnoGroup nunca recibió la notificación" de "UnoGroup sí procesó pero el callback se perdió" — sin acceso de UnoGroup a base de datos, esta distinción no es trivial. Puede requerir que `orquestador-app` registre el intento de notificación (no solo el resultado) para poder diferenciar los dos casos.
- Vive en `orquestador-app` porque el Orquestador es quien tiene visibilidad completa del ciclo de vida (crea la fila, notifica, y recibe el callback); UnoGroup no tiene ningún estado persistente propio para consultar.

### 1.4.6 `unogroup-app` — `controller` / `service`

> **Reescrito esta sesión** — UnoGroup ya no lee de base de datos; procesa lo que recibe en el body y reporta por callback.

- `controller` recibe la notificación con el `payload_enriquecido` completo y el campo `accion` (`create`/`update`) en el body. Responde `202` de inmediato (siempre antes de procesar — comunicación asíncrona, ver Diseño §2.4) y delega el procesamiento a `service`, que corre después de responder.
- `service` lee `accion` directamente del body — **nunca infiere** creación vs. actualización a partir de qué campos trae `payloadEnriquecido` (ver Diseño §2.4: sin este campo explícito, esa inferencia sería frágil e implícita, el mismo problema ya resuelto para `tipo_evento`). Traduce el payload recibido (no lee nada de base de datos — no tiene), invoca `client`/`mapper` (antes agrupados bajo `adapter.solutionone` — aplanado esta sesión, ver §1.4), aplica la política de reintentos síncrona de la tabla en Diseño §2.9 (backoff exponencial para 500, una vez para 401, sin reintento para 400/403/413), acumulando en memoria el detalle de cada intento (incluyendo `AUTH_TOKEN`).
- Al terminar (éxito o fallo definitivo), invoca `callback` con el resultado final y la lista completa de intentos — este es el único momento en que UnoGroup "entrega" lo que hizo; no queda ningún registro de la ejecución dentro de `unogroup-app` una vez que el callback se envía.

### 1.4.7 `unogroup-app` — `client` / `dto` / `mapper` (antes `adapter.solutionone`)

**Construcción del `path` (query param) — antes solo descrito como "convención con inconsistencias sin resolver"; algoritmo revisado el 2026-07-15 (Diseño §2.9, §6.4.3):**

```
{ruta-base}/{accion}/{fecha_envio}/{accion}_{timestamp_orden}_{external_reference}_{sku}.json
```

| Componente | Valor | Fuente | Confianza |
|---|---|---|---|
| `ruta-base` | `siman` | ⚠️ **Reabierto 2026-07-15** — una petición real exitosa había confirmado `assembly` como carpeta raíz única, pero en producción esa misma ruta (mismas credenciales) empezó a responder `403 permission denied`. Se confirmó que la raíz correcta se divide por tipo de subida: `siman/create/` y `siman/update/` (la opción que se había descartado antes por error — ver Diseño §6.4.3). Ver §5 `application.yml`, `SOLUTIONONE_RUTA_BASE` | ⚠️ Revisado, pendiente de confirmación directa de UnoGroup sobre por qué `assembly/` funcionó en la prueba anterior |
| `fecha_envio` | Fecha **actual** al momento de subir (no una fecha de negocio), formato `yyyyMMdd` | Ejemplo real: `20260713` — no coincide con ninguna fecha del contenido del JSON (`tracking_order_time` era `2026-04-10`), así que es la fecha de envío, no una fecha del pedido | ✅ Confirmado por descarte |
| `accion` | `create` para creación, `update` para actualización | ✅ **Recibido explícitamente** en el campo `accion` de la notificación del Orquestador (§1.4.2/§1.4.4) — ya no se infiere ni se decide en `unogroup-app`. El Orquestador lo determina por su propia idempotencia (Diseño §9.6 F19) | ⚠ El uso del valor como segmento de carpeta (`/{accion}/`) y como prefijo de archivo es una decisión de diseño; el valor `update` en particular sigue sin un ejemplo real que lo confirme |
| `timestamp_orden` | El valor de `tracking_order_time` **del pedido**, formateado `yyyyMMddHHmmss` — **no** la hora actual de envío | El ejemplo real (`20260410050000`) coincide exactamente con el `tracking_order_time` (`2026-04-10T05:00:00.000Z`) del JSON de creación correspondiente a esa misma orden | ✅ Confirmado por coincidencia exacta — vale la pena una validación explícita adicional antes de darlo por cerrado, ya que se infirió por coincidencia de valores, no por una aclaración directa de UnoGroup |
| `external_reference` | `ordenId` | Directo | ✅ Confirmado |
| `sku` | `sku` de la sub-orden. **Siempre incluido, en creación y en actualización** — aunque el body de actualización no lleva `item_sku` (§4.4), el nombre de archivo sí, y es lo que le permite a UnoGroup identificar la sub-orden exacta | ✅ **Decidido** (Diseño §9.6 F20) | ⚠ Decisión de diseño, no confirmación de UnoGroup |

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
        // sku siempre incluido — en creación Y en actualización (Diseño §9.6 F20),
        // aunque el body de actualización no lo lleve como campo (§4.4).
        String nombreArchivo = String.format("%s_%s_%s_%s.json", accion, timestamp, externalReference, sku);
        return String.format("%s/%s/%s/%s", rutaBase, accion, fecha, nombreArchivo);
    }
}
```

**El resultado se persiste en `nombre_archivo`** (columna de `ensamble_solicitud`, ver §2/§3) — el Orquestador lo recibe de vuelta en el callback (`ResultadoSolicitud`, aunque hoy ese schema no incluye explícitamente el nombre de archivo generado — ⚠ pendiente agregarlo si se necesita para trazabilidad exacta de qué archivo corresponde a cada intento).



- Traduce el `payload_enriquecido` recibido (lenguaje Siman) al formato binario que espera Solution One, vía `mapper`. El resultado de esa transformación se incluye en el callback (campo `payloadPartner`) para que el Orquestador lo persista — UnoGroup ya no lo guarda él mismo en ningún lado, solo lo reporta.

**Mapeo confirmado esta sesión** (`Campo API` → `Campo Solution One`, ver Diseño §4.4 para los JSON de ejemplo completos):

| Campo API (`payload_enriquecido`) | Campo Solution One (`payload_partner`) | Solo creación |
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
- El valor de `service_type` para el flujo DARM no está confirmado (solo se vio `"armado"` en el ejemplo — ver Diseño §9.6 F18).
- Construye el query param `path` — algoritmo confirmado, ver §1.4.7 — y siempre incluye `mkdir_parents=true` (ver Diseño §6.4.5 — evita que la ausencia de este parámetro se confunda con un error de permisos, `403`).
- Gestiona el ciclo de JWT (`GET /api/v2/user/token`, renovación automática).
- ✅ **Confirmado contra el ambiente de prueba real:** el body de la carga es binario (no serializado automáticamente por Feign) — sí requiere un `Encoder` de Feign personalizado, y `Content-Type: application/json` funciona para ese body binario (Diseño §9.6 F9, resuelto).

### 1.4.8 `unogroup-app` — `callback`

- `@FeignClient` hacia `orquestador-app`, endpoint `POST /internal/orquestador/solicitudes/resultado` (§1.4.4b).
- Se invoca al terminar de procesar — éxito o fallo definitivo hacia Solution One, nunca a mitad de un reintento hacia Solution One (esa es una política distinta, ver §1.4.7).
- ✅ **Resuelto (esta sesión) — política de reintentos:** dado que `unogroup-app` no persiste nada, perder esta llamada significa perder el resultado del procesamiento sin dejar ningún estado consultable salvo logs — merece más resiliencia que la notificación entrante (2-3 intentos, ver Diseño §2.11), pero sin llegar al backoff largo de Solution One (que existe para tolerar un tercero externo inestable, no un blip de red interna del clúster):

| Parámetro | Valor |
|---|---|
| Máximo de intentos | 5 |
| Backoff inicial | 500 ms |
| Multiplicador | 2 (500ms → 1s → 2s → 4s → 8s ≈ 15.5s en total) |
| Si se agotan los 5 intentos | Se registra en logs a nivel `ERROR`, en formato estructurado (incluyendo `ordenId`, `sku`, `resultadoFinal` y el detalle completo de `intentos[]`) — no se descarta el resultado, queda recuperable manualmente desde logs aunque no exista un estado consultable en base de datos. |

- La reconciliación (zona 3, Diseño §2.11/§9.6 F16) sigue siendo necesaria como red de seguridad final — este reintento reduce drásticamente la probabilidad de llegar a esa zona (cubre blips transitorios, que son la inmensa mayoría de los casos), pero no la elimina: si `orquestador-app` está caído más de ~15.5s seguidos, el callback se agota igual y la reconciliación es lo único que queda.

---

# 2. DDL MySQL

**Módulo:** `orquestador-app` — el DDL en sí no vive en un módulo Maven (es SQL, no Java), pero se versiona junto al código de `orquestador-app` porque describe exactamente las entidades que ese módulo expone. Único módulo con acceso a esta base de datos (esta sesión — ver §1.1).

```sql
-- ============================================================
-- Sistema de Ensambles — DDL MySQL 8.0+
-- Base de datos compartida entre orquestador-app y unogroup-app
-- (decisión consciente, ver Diseño §2.6)
-- ============================================================
-- Modelo: cada sub-orden (orden_id + sku) es la unidad atómica.
-- Un orden_id puede tener N sub-órdenes (pedido multi-ítem).
-- Nomenclatura en lenguaje Siman desde el inicio (orden_id, no
-- externorderkey) — a diferencia de la v1 de este documento, que
-- arrastraba el nombre crudo de WMS y quedó como hallazgo pendiente
-- de corregir. En v2 se define correctamente desde el DDL.
-- ============================================================

SET time_zone = '+00:00';

-- ----------------------------------------------------------
-- Tabla principal: sub-órdenes.
-- Escritura: exclusiva de orquestador-app (creación, enriquecimiento,
-- y transición final al recibir el callback de unogroup-app) — ver
-- Diseño §2.6.1, tabla de dueño de escritura. unogroup-app no tiene
-- acceso a esta base de datos (cambio de esta sesión).
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
    -- Resultado ya transformado a formato Solution One — lo que efectivamente
    -- se serializó y subió. Lo escribe unogroup-app, auditoría de qué se envió.
    payload_partner     JSON,
    nombre_archivo      VARCHAR(120),
    fecha_creacion      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    fecha_actualizacion TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    fecha_orden         TIMESTAMP(6),
    fecha_despacho_plan TIMESTAMP(6),
    fecha_entrega_plan  TIMESTAMP(6),
    fecha_despacho_real TIMESTAMP(6),
    fecha_entrega_real  TIMESTAMP(6),

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
-- Bitácora HTTP hacia Solution One. Escritura exclusiva de
-- orquestador-app, poblada a partir del callback que reporta
-- unogroup-app (esta sesión: unogroup-app ya no tiene acceso a
-- base de datos — reporta, no escribe).
-- Sin columna `ambiente` (eliminada en v2 — ver Diseño §2.6,
-- el perfil stub ya no existe).
-- Sin solicitud_reintento (eliminada en v2 — reintentos
-- síncronos en el mismo hilo, ver Diseño §2.11).
-- ----------------------------------------------------------
CREATE TABLE ensamble_bitacora_partner (
    id                  BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    solicitud_id        BIGINT,
    orden_id            VARCHAR(32),
    sku                 VARCHAR(50),

    tipo_peticion       VARCHAR(20)     NOT NULL,   -- 'AUTH_TOKEN' | 'UPLOAD_CREATE' | 'UPLOAD_UPDATE'
    nombre_archivo      VARCHAR(120),

    url                 VARCHAR(500)    NOT NULL,
    metodo_http         VARCHAR(10)     NOT NULL,

    codigo_http         SMALLINT,
    duracion_ms         INT,
    respuesta_body      VARCHAR(1000),
    error_mensaje       VARCHAR(500),

    intento_num         TINYINT         NOT NULL DEFAULT 1,
    es_reintento        CHAR(1)         NOT NULL DEFAULT 'N',
    exitoso             CHAR(1)         NOT NULL DEFAULT 'N',

    fecha_peticion      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_ensamble_bitacora_solicitud
        FOREIGN KEY (solicitud_id) REFERENCES ensamble_solicitud (id),

    CONSTRAINT ck_ensamble_bitacora_tipo
        CHECK (tipo_peticion IN ('AUTH_TOKEN', 'UPLOAD_CREATE', 'UPLOAD_UPDATE')),

    CONSTRAINT ck_ensamble_bitacora_metodo
        CHECK (metodo_http IN ('GET', 'POST')),

    CONSTRAINT ck_ensamble_bitacora_exitoso
        CHECK (exitoso IN ('S', 'N')),

    CONSTRAINT ck_ensamble_bitacora_reintento
        CHECK (es_reintento IN ('S', 'N'))

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Bitácora de peticiones HTTP hacia Solution One. Escritura exclusiva de orquestador-app, poblada a partir del callback de unogroup-app.';

CREATE INDEX ix_ensamble_bitacora_solicitud     ON ensamble_bitacora_partner (solicitud_id);
CREATE INDEX ix_ensamble_bitacora_orden_sku     ON ensamble_bitacora_partner (orden_id, sku);
CREATE INDEX ix_ensamble_bitacora_exitoso_fecha ON ensamble_bitacora_partner (exitoso, fecha_peticion);

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
-- 2. payload_origen / payload_partner usan JSON nativo de MySQL 8.0+,
--    que valida el formato automáticamente en INSERT/UPDATE.
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
--    no definida aún como requerimiento).
-- ============================================================
```

---

# 3. Entidades JPA

**Módulo:** `orquestador-app` (`domain/`) — único módulo que las usa; movidas aquí esta sesión al eliminar `ensambles-common` (§1.1).

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

    @Lob
    @Column(name = "payload_partner", columnDefinition = "json")
    private String payloadPartner;

    @Column(name = "nombre_archivo", length = 120)
    private String nombreArchivo;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private Instant fechaCreacion;

    @Column(name = "fecha_actualizacion", nullable = false)
    private Instant fechaActualizacion;

    @Column(name = "fecha_orden")
    private Instant fechaOrden;
    @Column(name = "fecha_despacho_plan")
    private Instant fechaDespachoPlan;
    @Column(name = "fecha_entrega_plan")
    private Instant fechaEntregaPlan;
    @Column(name = "fecha_despacho_real")
    private Instant fechaDespachoReal;
    @Column(name = "fecha_entrega_real")
    private Instant fechaEntregaReal;

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

@Entity
@Table(name = "ensamble_bitacora_partner")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BitacoraPartner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Referencia opcional — puede ser null en peticiones de autenticación
    // (token) que no corresponden a una orden específica.
    @Column(name = "solicitud_id")
    private Long solicitudId;

    @Column(name = "orden_id", length = 32)
    private String ordenId;

    @Column(name = "sku", length = 50)
    private String sku;

    @Column(name = "tipo_peticion", nullable = false, length = 20)
    private String tipoPeticion;         // AUTH_TOKEN | UPLOAD_CREATE | UPLOAD_UPDATE

    @Column(name = "nombre_archivo", length = 120)
    private String nombreArchivo;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    @Column(name = "metodo_http", nullable = false, length = 10)
    private String metodoHttp;

    @Column(name = "codigo_http")
    private Short codigoHttp;

    @Column(name = "duracion_ms")
    private Integer duracionMs;

    @Column(name = "respuesta_body", length = 1000)
    private String respuestaBody;

    @Column(name = "error_mensaje", length = 500)
    private String errorMensaje;

    @Column(name = "intento_num", nullable = false)
    private Integer intentoNum;

    @Column(name = "es_reintento", nullable = false, length = 1)
    private String esReintento;          // 'S' | 'N'

    @Column(name = "exitoso", nullable = false, length = 1)
    private String exitoso;              // 'S' | 'N'

    @Column(name = "fecha_peticion", nullable = false, updatable = false)
    private Instant fechaPeticion;
}
```

**Sobre `@Data` y relaciones lazy (heredado de v1, sigue aplicando):** se evita `@Data` en entidades JPA porque genera `equals()`/`hashCode()`/`toString()` sobre todos los campos, incluyendo asociaciones lazy — fuerza su inicialización fuera de sesión y puede disparar `LazyInitializationException`. Se usa `@Getter`/`@Setter`/`@Builder` explícitos.

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

> ⚠ **Riesgo aceptado conscientemente:** al duplicar estos enums en vez de compartirlos vía un módulo Maven, existe la posibilidad de que ambas copias se desincronicen si alguien cambia un valor en una app y olvida replicarlo en la otra. Se acepta este riesgo a cambio de independencia total de build entre los dos microservicios — la fuente de verdad real es el contrato OpenAPI (`HUENSA-001_openapi_v2.yaml`), donde estos mismos valores están declarados como `enum:` en los schemas correspondientes; conviene validar contra ese archivo, no asumir que el código Java de una app es la referencia para la otra.

---

# 4. `application.yml` — `orquestador-app`

**Nota importante antes del archivo:** la v1 de este documento tenía configuración de **consumer pull** de Pub/Sub (`spring.cloud.gcp.pubsub.subscriber.parallel-pull-count`, etc.). Eso ya no aplica — el diagrama de infraestructura confirmó que el mecanismo es **push vía Ingress** (ver Diseño §2.1), no un listener que jala mensajes. En modo push, `orquestador-app` no necesita configuración de *subscriber* de Pub/Sub en absoluto — es, desde el punto de vista de Spring Boot, simplemente un controller REST más. Lo que **sí** necesita, y que la v1 no tenía porque no aplicaba al patrón pull, es **validación del JWT que Pub/Sub adjunta a cada push** (para confirmar que la petición realmente viene de Pub/Sub y no de cualquiera que descubra la URL del Ingress).

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

  # Validación del push de Pub/Sub — reemplaza la config de
  # "subscriber" que tenía la v1 de este documento (pull).
  pubsub-push:
    # Pub/Sub firma cada request push con un JWT de identidad de
    # la cuenta de servicio configurada al crear la suscripción.
    # Se valida audience + issuer antes de procesar el mensaje.
    audiencia-esperada: ${PUBSUB_PUSH_AUDIENCE:https://ensambles.siman.com/internal/eventos}
    cuenta-servicio-esperada: ${PUBSUB_PUSH_SERVICE_ACCOUNT:}   # obligatorio, sin default — Secret/ConfigMap de GKE

  # Cliente hacia unogroup-app — notificación con payload_enriquecido
  # completo (ya no un claim-check, esta sesión) — vía Service de
  # Kubernetes, DNS interno del clúster.
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
  # sin notificar al UnoGroup, notificada sin callback recibido —
  # esta tercera zona es nueva esta sesión, ver Diseño §9.6 F16).
  # Intervalo exacto aún sin cerrar (Diseño §9.6 F7) — el default
  # de abajo es un punto de partida, no una decisión final.
  reconciliation:
    habilitado: ${RECONCILIATION_HABILITADO:true}
    intervalo-cron: ${RECONCILIATION_CRON:0 */15 * * * *}   # cada 15 min — ⚠ TBD, valor final sin confirmar
    umbral-recibida-minutos: ${RECONCILIATION_UMBRAL_RECIBIDA_MIN:10}
    umbral-enriquecida-minutos: ${RECONCILIATION_UMBRAL_ENRIQUECIDA_MIN:10}
    umbral-callback-minutos: ${RECONCILIATION_UMBRAL_CALLBACK_MIN:10}   # ⚠ TBD F16 — mecanismo de detección aún sin definir

  # Endpoint que recibe el callback de unogroup-app con el resultado
  # (nuevo, esta sesión — ver §1.4.4b). Mismo mecanismo de auth
  # pendiente que la notificación en sentido contrario (Diseño §9.6 F8).
  callback-receiver:
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
    # Nunca DEBUG en producción sin enmascarado — mismo principio de
    # logging/PII heredado de v1 (nombre, teléfono, correo, dirección
    # nunca en texto plano incluso en debug).
```

**Puntos que quedan explícitamente abiertos con este archivo, no resueltos por defaults razonables:**
- `PUBSUB_PUSH_SERVICE_ACCOUNT` no tiene default — es una credencial de configuración, no un secreto en sí, pero se deja sin valor por defecto para forzar su definición explícita antes de aceptar tráfico real (mismo principio que ya usaban en v1 para credenciales de Solution One).
- Las URLs de `rms`/`wms-api` están vacías a propósito — todavía no existe una fuente confirmada para varios de los campos que el Orquestador necesita completar (Diseño §9.6, preguntas A5 y equivalentes de Guías Manuales).
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
# callback de orquestador-app (nuevo, esta sesión)
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
      # ⚠️ Reabierto 2026-07-15 (Diseño §2.9/§6.4.3, C3): "assembly" se había
      # confirmado como carpeta raíz con una petición real exitosa, pero
      # producción empezó a responder 403/permission denied en esa misma
      # ruta con las mismas credenciales. La raíz real se divide por tipo
      # de subida — SolutionOneFileNaming agrega /create o /update, así
      # que esta propiedad ahora es solo el prefijo común ("siman").
      ruta-base: ${SOLUTIONONE_RUTA_BASE:siman}
      # Confirmado en Diseño §6.4.5 — debe ser true en producción; false
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
  # (payload_enriquecido completo, ya no un claim-check).
  # ⚠ TBD (Diseño §9.6 F8): mecanismo de autenticación entre
  # servicios dentro del clúster — hoy no hay ninguno definido más
  # allá de que el Service es ClusterIP (no alcanzable desde fuera
  # del clúster). Si se decide agregar algo (ej. token compartido,
  # mTLS interno), la configuración iría aquí.
  notificacion:
    autenticacion-habilitada: ${NOTIFICACION_AUTH_HABILITADA:false}   # ⚠ TBD — ver Diseño F8

  # Cliente de callback hacia orquestador-app — ver Diseño §2.4, §1.4.8
  # de este documento. Se invoca al terminar de procesar, con el
  # resultado final y el detalle de cada intento (incluyendo AUTH_TOKEN).
  callback:
    orquestador-url: ${ORQUESTADOR_APP_URL:http://svc-orquestador.ensambles.svc.cluster.local}
    # ✅ Resuelto (esta sesión, §1.4.8): más intentos que el reintento
    # corto de la notificación entrante (blips de red interna), sin
    # llegar al backoff largo de Solution One (tercero externo inestable,
    # no comparable con un blip dentro del clúster). Si se agotan los 5
    # intentos, el resultado se registra en logs a nivel ERROR (formato
    # estructurado, recuperable manualmente) y queda en manos de la
    # reconciliación (zona 3, Diseño §2.11/§9.6 F16) como red de seguridad final.
    reintentos:
      max-intentos: ${CALLBACK_MAX_INTENTOS:5}
      backoff-inicial-ms: ${CALLBACK_BACKOFF_MS:500}
      backoff-multiplicador: 2

logging:
  level:
    root: INFO
    com.siman.ensambles.unogroup: ${LOG_LEVEL_APP:INFO}
    # Mismo principio de v1: nunca loguear payload completo con PII en
    # INFO o superior. A nivel DEBUG, enmascarado (nunca en texto plano).
```

**Diferencias notables frente al `application.yml` de `orquestador-app` (§4), y por qué:**
- **`unogroup-app` no tiene sección `spring.datasource`/`spring.jpa` en absoluto** — cambio de esta sesión: ya no accede a MySQL de ninguna forma. Confirma en configuración lo que ya establecimos en el Diseño (§2.3): es un servicio sin estado.
- **Solo `unogroup-app` tiene la sección `adapter.solutionone`** con credenciales — confirma lo que el diagrama de infraestructura ya mostraba: solo este pod accede a Secret Manager para las credenciales de UnoGroup.
- **No hay sección de Pub/Sub** en absoluto — `unogroup-app` nunca consume eventos, solo recibe la notificación interna del Orquestador.
- **`mkdir-parents` es configurable vía variable de entorno** (`SOLUTIONONE_MKDIR_PARENTS`, default `true`) — a pedido explícito. El default sigue siendo `true` para no reproducir por accidente el escenario de 403 documentado en el Diseño §6.4.5, pero queda como interruptor disponible si el equipo lo necesita.
- **`notificacion.autenticacion-habilitada` queda en `false` por defecto** — no porque sea la decisión final, sino porque F8 (Diseño §9.6) sigue sin resolver y no quiero inventar un mecanismo de autenticación no acordado; lo dejo como interruptor explícito para cuando se decida.
- **Aparece `ensambles.callback`** — nuevo esta sesión, la config del cliente Feign que reporta el resultado de vuelta al Orquestador. Su política de reintentos queda deliberadamente sin decidir (ver comentario en el YAML).

---

# 6. Dependencias Maven

**Nota respecto a v1:** desaparecen dos dependencias que la v1 de este documento sí tenía, por dos razones distintas:
- **`com.h2database:h2`** — existía "para perfil `local-stub`". El stub ya no existe (decisión de esta sesión, ambiente de prueba real disponible) — no hay ningún perfil que necesite una base de datos en memoria.
- **`spring-cloud-gcp-starter-pubsub`** — existía para el patrón *pull* de v1. Con el mecanismo confirmado como *push vía Ingress* (Diseño §2.1), `orquestador-app` no consume la API de suscriptor de GCP en absoluto — Pub/Sub le habla por HTTP como a cualquier otro cliente REST. En su lugar, aparece una dependencia nueva para **validar el JWT que Pub/Sub adjunta a cada push** (ver §4).

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

**Confirmado contra el ambiente de prueba real (ya no es una nota pendiente, ver Diseño §9.6 F9 — resuelto):** `spring-cloud-starter-openfeign` trae por defecto un encoder basado en Jackson (JSON), pero la carga hacia Solution One es un **body binario** (Diseño §2.9/§6.1) — ninguna de estas dependencias resuelve eso automáticamente, hace falta un `Encoder` de Feign personalizado dentro de `unogroup-app/client` (antes `unogroup-app/adapter/solutionone`, aplanado esta sesión — ver §1.4). Se probó contra el ambiente de prueba real: **`Content-Type: application/json` funciona** para ese body binario — no hace falta `application/octet-stream` ni una librería adicional (`feign-okhttp` u otra) para esto. Ejemplo del encoder:

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

```
repositorio: orquestador-app             repositorio: unogroup-app
(raíz del repo)                          (raíz del repo)

pom.xml                                   pom.xml
src/                                      src/
Dockerfile                                Dockerfile
k8s/                                      k8s/
├── deployment.yaml                       ├── deployment.yaml
└── service.yaml                          └── service.yaml
```

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

```yaml
# unogroup-app/k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: unogroup-app
  namespace: ensambles
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
          image: TBD_REGISTRY/unogroup-app:TBD_TAG
          ports:
            - containerPort: 8080
          envFrom:
            - configMapRef:
                name: unogroup-app-config          # SOLUTIONONE_BASE_URL, ORQUESTADOR_APP_URL, timeouts, etc.
            - secretRef:
                name: unogroup-app-solutionone-secret  # SOLUTIONONE_USUARIO, SOLUTIONONE_PASSWORD
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            initialDelaySeconds: 10
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            initialDelaySeconds: 20
        # Sin sidecar cloud-sql-auth-proxy — cambio de esta sesión: unogroup-app
        # ya no accede a MySQL (Diseño §2.3/§2.4). Su Deployment queda con un
        # solo contenedor.
---
# unogroup-app/k8s/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: svc-unogroup
  namespace: ensambles
spec:
  type: ClusterIP   # solo alcanzable dentro del clúster — nunca expuesto
                     # fuera de GKE (Diseño §2.3)
  selector:
    app: unogroup-app
  ports:
    - port: 80
      targetPort: 8080
```

**Diferencia deliberada entre ambos Deployments, visible en los manifiestos:** solo `unogroup-app` monta `unogroup-app-solutionone-secret` — confirma en Kubernetes lo que ya establecimos en el Diseño (§1.1) y en el diagrama de infraestructura: el Orquestador nunca tiene acceso a las credenciales de UnoGroup, ni siquiera a nivel de `ServiceAccount`/`Secret` montado. **Y, cambio de esta sesión, solo `orquestador-app` tiene el sidecar `cloud-sql-auth-proxy`** — `unogroup-app` quedó con un único contenedor, sin acceso a base de datos en absoluto.

**Placeholders `TBD_*` en ambos manifiestos** — intencionales, no un descuido: registro de imágenes, tag, proyecto/región/instancia de Cloud SQL (solo en el manifiesto del Orquestador ahora) son valores que dependen de decisiones de infraestructura (§8) o de CI/CD que todavía no se han tomado en esta conversación.

---

# 8. Infraestructura Terraform — nota de alcance

**Este documento no cubre la infraestructura gestionada por Terraform** — se documentará por separado. Acotación de alcance, confirmada en esta sesión:

- **El clúster de GKE y la instancia de Cloud SQL (MySQL) ya existen** — no son recursos que este proyecto deba crear. Lo que sí falta a nivel de infraestructura para este proyecto específico: base de datos y usuario nuevos dentro de la instancia existente, namespace de Kubernetes, los `ServiceAccount`/Workload Identity referenciados en §7.2/§7.3, tópico y suscripción de Pub/Sub, y las entradas de Secret Manager para credenciales de UnoGroup y de base de datos (sincronizadas hacia los `Secret` de Kubernetes que consumen los manifiestos de §7).

---

# 9. Pendientes que afectan la implementación

| # | Pendiente | Afecta | Estado |
|---|---|---|---|
| ~~1~~ | ~~Distinción `UP05`/`UP06` cuando el payload de WMS no lo indica~~ | `orquestador-app/messaging` | **Resuelto** — nuevo atributo `tipo_evento` en el mensaje de Pub/Sub (ver Diseño §9.6 F12) |
| ~~2~~ | ~~`WMS-Order Provider` — ¿conoce el flujo (ASSE/ENSA) al momento de crear la orden?~~ | `orquestador-app/messaging` | **Resuelto** — no lo conoce, y estructuralmente no puede: el flujo se determina por línea (`orderdetails[].ext_udf_str10`), después de consultar el shipment. `flujo` deja de ser atributo requerido para `origen=wms` (ver Diseño §9.6 F11, §2.5) |
| 3 | Schema del mensaje de Tracking/Beetrack (actualización de entrega) | `orquestador-app/messaging/mapper` | Abierto — ver Diseño §9.6 F13 |
| ~~4~~ | ~~¿El `202` de `unogroup-app` se responde antes o después del ciclo de reintentos hacia Solution One?~~ | `unogroup-app/controller` | **Resuelto** — comunicación asíncrona: siempre antes, con resultado reportado después por callback (ver §1.4.6, Diseño §2.4) |
| ~~5~~ | ~~Body binario hacia Solution One — ¿requiere `Encoder` de Feign personalizado?~~ | `unogroup-app/client` | **Resuelto** — sí, y `Content-Type: application/json` funciona (probado contra ambiente de prueba real, ver §6) |
| 6 | Intervalo exacto del job de reconciliación | `orquestador-app/reconciliation` | Abierto — ver Diseño §9.6 F7 |
| 7 | Estructura real de carpetas / nombre de campo / longitud de `external_reference` | `unogroup-app/client`, `unogroup-app/mapper` | **Parcialmente reabierto (2026-07-15)** — carpeta: `assembly/{fecha}/...` funcionó en una prueba real pero producción respondió `403 permission denied` en la misma ruta; vigente ahora `/siman/create|update/{fecha}/...` (ver Diseño §6.4.3, C3). `customer_*` y longitud 32 de `external_reference` siguen resueltos sin cambios — ver Diseño §6.7, §9.6 C3/C4/C5 |
| 8 | Autenticación de los endpoints internos de notificación y callback (hoy deshabilitada en ambos sentidos) | `unogroup-app/controller`, `orquestador-app/controller` | Abierto — ver Diseño §9.6 F8 (ampliado esta sesión, ahora cubre ambos endpoints) |
| 9 | Valores reales de imagen/registro, proyecto/región/instancia Cloud SQL, nombres de Secret/ConfigMap | `k8s/deployment.yaml` (ambas apps) | Nuevo — placeholders `TBD_*` en §7, depende de infraestructura (§8) |
| 10 | Cómo detecta la reconciliación la zona 3 (UnoGroup procesó pero el callback se perdió) — UnoGroup ya no tiene base de datos donde dejar rastro | `orquestador-app/reconciliation` | Nuevo, esta sesión — ver Diseño §9.6 F16 |
| ~~11~~ | ~~¿El cliente de callback (`unogroup-app/callback`) reintenta si la llamada al Orquestador falla, o se deja todo en manos de la reconciliación (ítem 10)?~~ | `unogroup-app/callback` | **Resuelto** — 5 intentos, backoff exponencial desde 500ms (×2, ≈15.5s total); agotados los intentos, se registra en logs ERROR y queda en manos de la reconciliación (ver §1.4.8) |
| ~~12~~ | ~~`ensambles-common` como dependencia de `unogroup-app` solo para reutilizar enums — evaluar si conviene separarlos en un módulo aún más pequeño~~ | `unogroup-app/pom.xml` | **Resuelto** — se eliminó `ensambles-common` por completo (esta sesión); cada app define sus propios enums locales, ver §1.1/§3 |
| ~~13~~ | ~~Valores de `tipo_evento` para eventos de Guías Manuales (CARM/TARM/DARM) — solo se confirmó para WMS~~ | `orquestador-app/messaging` | **Resuelto** — `creacion` / `actualizacion` (ver Diseño §4.2.1) |
| ~~14~~ | ~~El nombre de archivo para actualizaciones — ¿usa prefijo `update`?~~ | `unogroup-app/client` (`SolutionOneFileNaming`) | **Resuelto (decisión de diseño)** — sí, por simetría con `create`. Sin ejemplo real de actualización todavía — ver Diseño §9.6 F19 |
| 15 | **Nuevo:** `EventoGuiasMapper` — comportamiento exacto si `items[]` viene vacío o con SKUs duplicados dentro del mismo evento (¿error 400, o se procesa lo válido y se reporta lo demás?) | `orquestador-app/messaging/mapper` | Nuevo, esta sesión — sin precedente en el diseño de WMS (ASSE/ENSA no tienen este caso, siempre 1 sku por evento) |
| ~~15~~ | ~~El nombre de archivo para actualizaciones — ¿incluye `sku`?~~ | `unogroup-app/client` (`SolutionOneFileNaming`) | **Resuelto (decisión de diseño)** — sí, siempre. Ver Diseño §9.6 F20 |
| 16 | **Nuevo, esta sesión:** autenticación y política de reintento de `WmsShipmentClient` hacia `GET /wms/dw/v1/shipment/get-shipment/{whseId}/{externOrderKey}` — sin definir | `orquestador-app/client/wms` | Nuevo — ver §1.4.4c |
| 17 | **Nuevo, esta sesión:** fuente exacta de `tracking_order_time` — `adddate` vs. `orderdate` de `WmsShipmentDetail`, ambos presentes en el ejemplo real con valores distintos | `orquestador-app/enrichment` | Nuevo, dejado abierto a propósito — ver Diseño §9.1 A6 |
| 18 | **Nuevo, esta sesión:** fuente exacta de `tracking_dispatched_time`/`tracking_delivered_time` — campo `fecha` del payload crudo (UP05/UP06, formato no ISO 8601) vs. hora de recepción del evento en el Orquestador | `orquestador-app/enrichment` | Nuevo, dejado abierto a propósito — ver Diseño §9.1 A7 |
| 19 | **Nuevo, esta sesión:** ¿`bcompany` o `ccompany` para `customer_name`? Ambos presentes en `WmsShipmentDetail`, mismo valor en el ejemplo real — sin confirmar si siempre coinciden | `orquestador-app/enrichment` | Nuevo — ver Diseño §4.1 |
| ~~20~~ | ~~¿`orquestador-app` y `unogroup-app` viven en un solo proyecto Maven multi-módulo o en repositorios separados?~~ | Toda la sección §1, §6, §7.1 | **Resuelto (reconfirmado en esta reconciliación)** — dos repositorios Git independientes, sin `pom.xml` padre ni módulo común (ver §1.1) |
| 21 | Pipelines de CI/CD — con dos repositorios separados, hace falta un pipeline por repositorio (build, test, imagen, tag) en vez de uno que compile ambos módulos a la vez | `orquestador-app/Dockerfile`, `unogroup-app/Dockerfile`, §7 | Abierto — no se ha definido la herramienta ni la configuración de CI/CD todavía |
