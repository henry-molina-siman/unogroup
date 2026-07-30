# Bitácora de Decisiones — Implementación del Módulo de Integración de Servicios de Ensamble

**Proyecto:** HUENSA-001 — Integración de Pedidos que Requieren Ensamble
**Propósito:** registrar cómo y cuándo llegó la implementación (`orquestador-app` + `unogroup-app`) a su estado actual — hallazgos de auditoría de código, correcciones puntuales sobre versiones previas del documento, y el historial de trabajo técnico pendiente.

> **Implementación vigente:** ver `HUENSA-001_Implementacion_Modulo_Integracion_Ensamble.md`. Ese documento describe únicamente el estado actual del código (estructura, DDL, configuración, contratos internos); este documento es el registro de auditoría/proceso que lo respalda.
>
> **Relación con la Bitácora de Diseño:** las preguntas de negocio y las decisiones de arquitectura/contrato (`F1`–`F20`, `A1`–`A7`, `C1`–`C7`, etc.) viven en `HUENSA-001_Bitacora_Decisiones_Modulo_Integracion_Ensamble.md` y no se repiten aquí — esta bitácora solo referencia esos IDs cuando un hallazgo de código los afecta. Lo que sí vive aquí, y no en la de Diseño, es todo lo que solo tiene sentido al nivel de código: nombres de clase, workarounds concretos, archivos/paquetes que faltan, y el detalle de qué se auditó, cuándo y contra qué repositorio.

---

## Índice

1. [Historial de revisiones del documento de Implementación](#1-historial-de-revisiones-del-documento-de-implementación)
2. [Hallazgos de auditoría de código](#2-hallazgos-de-auditoría-de-código)
3. [Correcciones puntuales sobre versiones previas del documento](#3-correcciones-puntuales-sobre-versiones-previas-del-documento)
4. [Trabajo técnico pendiente](#4-trabajo-técnico-pendiente)

---

# 1. Historial de revisiones del documento de Implementación

| Versión | Cambio principal |
|---|---|
| v1 | Un solo módulo Maven multi-módulo (`ensambles-parent` con `orquestador-app`/`unogroup-app` como sub-módulos, más `ensambles-common` compartiendo entidades JPA y enums). Consumer *pull* de Pub/Sub. Dependencia de `com.h2database:h2` para un perfil `local-stub`. |
| v2 | Separación en dos microservicios con notificación asíncrona + callback (`unogroup-app` sin base de datos propia). Mecanismo de entrada confirmado como *push vía Ingress*, no *pull* — desaparece la configuración de *subscriber* de GCP Pub/Sub y aparece la validación del JWT que Pub/Sub adjunta a cada push. Desaparece el perfil `local-stub` y su dependencia de H2 (ambiente de prueba real ya disponible). |
| v2.1 | Nomenclatura de tablas actualizada por schema físico compartido: las tres tablas del módulo adoptan el prefijo `ensamble_` (ver Diseño §2.6). Se elimina `ensambles-common`: `orquestador-app` y `unogroup-app` pasan a vivir en repositorios Git independientes, cada uno con enums de dominio definidos localmente. |
| v2.1 + corrección | Una versión intermedia del documento había vuelto, sin nota explicativa, a un proyecto Maven multi-módulo (`ensambles-parent` con `orquestador-app`/`unogroup-app` como módulos de un mismo reactor). Se corrige: la decisión vigente sigue siendo dos repositorios Git independientes, sin `pom.xml` padre ni módulo común (§1.1 de Implementación). |
| v2.1 + reconciliación 2026-07-29 | `orquestador-app` y `unogroup-app`, al vivir en repositorios separados sin visibilidad cruzada, habían quedado cada uno con su propia copia del documento de Implementación, actualizada solo contra su propio código. Se fusionan ambas copias en un único documento — ver §2 de esta bitácora para el detalle de qué aportó cada lado. |
| v3 (esta reorganización) | El documento de Implementación se separa en dos: **Implementación** (estado actual del código, sin anotaciones de proceso) y esta **Bitácora de Implementación** — separada, a su vez, de la Bitácora de Diseño. Se aprovecha la misma pasada para revisar fidelidad línea por línea contra `HUENSA-001_Diseno_Requerimientos_Modulo_Integracion_Ensamble.md` vigente: se corrige la referencia rota al documento de diseño anterior (ya dividido en Requerimientos + Bitácora) y la terminología obsoleta `WMS-Order Provider` (el componente real es `DMS/OMS`, corrección ya reflejada en Diseño desde v3.1 — ver Bitácora de Diseño). No cambia ningún contenido técnico de la implementación — es una reorganización editorial más una corrección de referencias. |

---

# 2. Hallazgos de auditoría de código

Hallazgos de comparar el documento de Implementación contra el código real de `orquestador-app` y `unogroup-app`, durante la reconciliación del 2026-07-29 (ver fila correspondiente en §1). Los mismos hallazgos, a nivel de principio de diseño, están resumidos en la Bitácora de Diseño §2 — aquí se documenta el detalle de código (clases, archivos, workarounds) que no tiene lugar natural en ese documento.

## 2.1 Del lado `orquestador-app`

**Bug de ruteo — `EventoRouter.rutearGuias` no rutea actualizaciones (afecta Implementación §1.4.1; Diseño F17):**
Los valores de `tipo_evento` para Guías Manuales (`creacion`/`actualizacion`) están definidos, pero el ruteo real de `actualizacion` para `origen=guias` no está implementado: `EventoRouter.rutearGuias` (código real) ignora el valor de `tipoEvento` y siempre invoca el mapper de creación, logueando explícitamente `"aún no rutea actualizaciones (F17 sin confirmar) — se trata como creación"`. F17 (Diseño Bitácora) solo resolvió **qué valores** lleva el atributo, no que el ruteo de actualización ya estuviera construido — una versión previa de este documento redactaba el párrafo de forma que sugería lo segundo. Pendiente real: construir el camino de actualización para CARM/TARM/DARM.

**Paquete `reconciliation` no implementado (afecta Implementación §1.4.5; Diseño §2.11, F7, F16):**
El diseño acordado del job de reconciliación está documentado en Implementación §1.4.5, pero no existe ningún paquete `reconciliation` (ni equivalente) en `src/main/java` de `orquestador-app`, y `application.yml` no tiene ninguna clave `ensambles.reconciliation.*`. El código deja constancia explícita del hueco en comentarios: `service/SolicitudEnsambleService.java`, `client/UnogroupNotificacionPublisher.java` y `k8s/base/deployment.yaml` incluyen la nota *"sin el job de reconciliación (fuera de alcance de esta implementación)"*.

**Discrepancia de `NOT NULL` en `ensamble_bitacora_partner` (afecta Implementación §2, §3; Diseño §2.4, §2.6.1, F8):**
`url` y `metodo_http` son `NOT NULL` en el DDL y en la entidad `BitacoraPartner`, pero ni el JSON de ejemplo del callback ni el `IntentoDto` real del código llevan una URL o un método HTTP por intento — el callback nunca los provee. `SolicitudEnsambleService` lo resuelve con un workaround no documentado hasta ahora: hardcodea `url = "N/D"` y deriva `metodo_http` de `tipo_peticion` (`AUTH_TOKEN` → `GET`, cualquier otro → `POST`). Pendiente real: decidir si se agregan `url`/`metodoHttp` al contrato del callback (rompe el principio de "UnoGroup solo reporta lo mínimo") o se relaja el `NOT NULL` en el DDL.

**Tipo de columna `intento_num` (afecta Implementación §3):**
El código real usa `Byte` (no `Integer`) para `BitacoraPartner.intentoNum`, para coincidir exactamente con el tipo `TINYINT` del DDL — Hibernate exige el tipo Java exacto cuando valida el schema (`ddl-auto: validate`) contra una columna existente. Una versión previa de este documento tenía `Integer`, que habría fallado la validación de arranque contra el schema real.

**Configuración muerta en `application.yml` (afecta Implementación §4; Diseño F7, F8):**
- La sección `ensambles.reconciliation.*` no existe en el `application.yml` real del repositorio — coherente con que el paquete `reconciliation` tampoco existe (ver hallazgo anterior). Se documenta en Implementación §4 como diseño de referencia para cuando se construya.
- `callback-receiver.autenticacion-habilitada` sí existe como clave en el `application.yml` real, pero no hay ningún `@ConfigurationProperties` que la lea, y el controller del callback (`ResultadoSolicitudController`) no la consulta. `SecurityConfig` permite el endpoint sin autenticación pase lo que valga este flag — hoy es una propiedad muerta, no un interruptor real, hasta que F8 (Diseño Bitácora) se implemente de verdad.

## 2.2 Del lado `unogroup-app`

**JWT sin caché — confirmado contra `SolutionOneTokenManager` (afecta Implementación §1.4.7, §5; Diseño §2.3, §2.9):**
La gestión del JWT no usa caché entre solicitudes — cada procesamiento adquiere un token nuevo al inicio, no uno reutilizado hasta su expiración. Una versión previa de este documento describía esto como "renovación automática", frase que se prestaba a interpretarse como caché con refresco proactivo; en realidad se refiere únicamente al reintento tras `401` (tabla Diseño §2.9). El documento de Implementación ya refleja el comportamiento real, sin esta narrativa.

**`k8s` migró de manifiestos planos a Kustomize (afecta Implementación §7.3):**
Durante la implementación, `unogroup-app/k8s` migró de `deployment.yaml`/`service.yaml` planos a Kustomize (`base/` + `overlays/{local,prod}`), con `ConfigMap`, `Secret` y `ServiceAccount` como manifiestos separados en vez de solo referenciados inline, y los placeholders `TBD_*` de imagen aislados en los overlays en vez de sueltos dentro del Deployment. El documento de Implementación se actualizó para reflejar esto (§7.3). **No verificado:** si `orquestador-app/k8s` (§7.2) pasó por una migración equivalente — esa auditoría se hizo contra el repositorio de `unogroup-app` únicamente, sin visibilidad sobre el repositorio de `orquestador-app`.

**`pom.xml` real con cuatro dependencias no documentadas (afecta Implementación §6.2):**
El `pom.xml` real de `unogroup-app` tenía, al momento de esta auditoría, cuatro dependencias que la sección no listaba:
- `spring-boot-starter-actuator` — expone `/actuator/health/readiness` y `/actuator/health/liveness`, referenciados por los probes del Deployment; sin esta dependencia esos endpoints no existen.
- `feign-jackson` — `spring-cloud-starter-openfeign` no lo trae por defecto (usa los `HttpMessageConverters` de Spring); se necesita como fallback dentro de `SolutionOneBinaryEncoder` para las llamadas del mismo cliente que sí son JSON (ej. `GET /api/v2/user/token`).
- `jackson-datatype-jsr310` — Spring Boot 4.x usa internamente un `ObjectMapper` de "Jackson 3" (`tools.jackson`), incompatible con `com.fasterxml.jackson.databind`. Como `SolutionOneCreatePayload`/`UpdatePayload` usan anotaciones Jackson 2, `SolicitudProcesamientoService` construye su propio `ObjectMapper` Jackson 2 en vez de depender de la autoconfiguración de Spring; esta dependencia trae el soporte para serializar campos `Instant` con ese `ObjectMapper`.
- `wiremock-standalone` (scope `test`) — simula Solution One y el endpoint de callback de `orquestador-app` en los tests de `SolutionOneRetryPolicy` y `OrquestadorCallbackSender`.

**DTOs de entrada sin `@Size` (afecta Implementación §1.4.7; nuevo hallazgo):**
`SolicitudNotificacionRequest`/`PayloadEnriquecidoNotificacion` solo aplican `@NotBlank`/`@NotNull` — no hay `@Size` que refleje los `maxLength` del contrato OpenAPI (`ordenId` 32, `sku` 50). Un valor más largo pasa la validación de Bean Validation y solo fallaría, más tarde, contra el límite real de Solution One (si lo hay). No bloquea el camino feliz, pero el contrato documentado es más estricto que la validación implementada.

## 2.3 Trade-off aceptado: enums duplicados sin módulo común (transversal a ambas apps)

Con `ensambles-common` eliminado (§1 de esta bitácora), los enums de dominio (`EstadoInterno`, `TrackingStatus`, `FlujoEnsamble`) ya no se comparten vía un `.jar` común — cada app define su propia copia local, con los mismos valores. **Riesgo aceptado conscientemente:** existe la posibilidad de que ambas copias se desincronicen si alguien cambia un valor en una app y olvida replicarlo en la otra. Se acepta este riesgo a cambio de independencia total de build entre los dos microservicios — la fuente de verdad real es el contrato OpenAPI (`HUENSA-001_openapi_V2.yaml`), no el código Java de ninguna de las dos apps.

---

# 3. Correcciones puntuales sobre versiones previas del documento

Además de los hallazgos de auditoría (§2), estas son correcciones de redacción sobre párrafos de versiones previas del documento de Implementación que describían mal el comportamiento del código, detectadas al revisarlo:

**`accion` en la notificación Orquestador → UnoGroup (2026-07-15):** una versión previa decía que la propia verificación de duplicado (`DataIntegrityViolationException`) determinaba `accion=update`; eso contradecía Diseño §2.6 y nunca estuvo implementado así. El valor real de `accion` viaja explícito en `SolicitudNotificacionRequest.accion` (`create`/`update`), asignado por la aplicación (`create` desde `crear()`, `update` desde `aplicarActualizacion()`), no inferido de la existencia de un duplicado.

**`aplicarActualizacion` no notificaba a `unogroup-app` (2026-07-15):** gap corregido — el fan-out de actualización ahora notifica a `unogroup-app` con `accion=update` por cada sub-orden afectada; antes, una actualización aplicada vía `aplicarActualizacion` quedaba persistida en la base de datos del Orquestador pero nunca llegaba a UnoGroup ni a Solution One.

**Estructura de carpetas / `SOLUTIONONE_RUTA_BASE` (reabierto 2026-07-15):** ya documentado con el historial completo en la Bitácora de Diseño §3.3 (C3) — no se repite aquí. Resumen para contexto de código: `ruta-base` pasó de significar la carpeta raíz completa (`assembly`) a ser solo el prefijo común (`siman`), con `/create` o `/update` agregado por `SolutionOneFileNaming` según la `accion`.

**`ensamble_solicitud` tenía 5 columnas sin aprobar en el modelo de datos (corregido en esta revisión):** el DDL y la entidad `SolicitudEnsamble` incluían `fecha_orden`, `fecha_despacho_plan`, `fecha_entrega_plan`, `fecha_despacho_real`, `fecha_entrega_real` — columnas que no aparecen en el diagrama entidad-relación de Requerimientos §2.6.1, y que duplicaban datos ya presentes dentro de `payload_enriquecido` (los mismos campos, `fechaOrden`/`fechaPlanificadaDespacho`/etc., ya viajan en el JSON — ver §1.4.7 de Implementación). Requerimientos §2.6 es explícito en que el modelo usa tres columnas de payload precisamente para no tener que desnormalizar campos individuales en columnas propias. Se removieron del DDL y de la entidad; si en el futuro se necesita consultar por estas fechas sin parsear el JSON, es una decisión de modelo de datos que debe aprobarse primero en Requerimientos, no agregarse directo en el código.

**`ensamble_bitacora_partner` mantiene `respuesta_body`/`error_mensaje` como columnas sin aprobar explícitamente en el ER de Requerimientos, por decisión consciente.** A diferencia del caso anterior, se decidió dejarlas: no duplican ningún dato que ya viva en otro lado (el detalle de una llamada HTTP fallida hacia Solution One — cuerpo de la respuesta o mensaje de error — no está en ningún payload persistido), y tienen valor operativo claro para diagnosticar fallas sin tener que reproducirlas. Sigue pendiente reflejar estas dos columnas en el diagrama ER de Requerimientos §2.6.1 para que el modelo de datos documentado quede completo — ver ítem correspondiente en §4 de esta bitácora.

---

# 4. Trabajo técnico pendiente

Ítems de ingeniería que no tienen dueño en la Bitácora de Diseño porque son puramente de implementación (no preguntas para un tercero ni decisiones de arquitectura). Para el trabajo pendiente que sí depende de terceros o de decisiones de negocio/arquitectura, ver el Índice Maestro de Preguntas Abiertas en la Bitácora de Diseño (`F1`–`F20`, `A1`–`A7`, `C1`–`C7`, etc.) — varios de los ítems de abajo los referencian.

| # | Pendiente | Afecta | Relacionado |
|---|---|---|---|
| 1 | Construir el ruteo real de `actualizacion` para `origen=guias` — hoy `EventoRouter.rutearGuias` siempre trata el evento como creación | `orquestador-app/messaging` | §2.1 de esta bitácora; Diseño F17 |
| 2 | Construir el paquete `reconciliation` (hoy no existe) — no es solo el intervalo lo que falta cerrar | `orquestador-app/reconciliation` | §2.1 de esta bitácora; Diseño F7, F16 |
| 3 | Decidir si se agrega `url`/`metodoHttp` al contrato del callback, o se relaja el `NOT NULL` en `ensamble_bitacora_partner` | `orquestador-app` (DDL, entidad, contrato del callback) | §2.1 de esta bitácora; Diseño F8 |
| 4 | `EventoGuiasMapper` — comportamiento exacto si `items[]` viene vacío o con SKUs duplicados dentro del mismo evento (¿error 400, o se procesa lo válido y se reporta lo demás?) | `orquestador-app/messaging/mapper` | Sin precedente en el diseño de WMS (ASSE/ENSA no tienen este caso, siempre 1 SKU por evento) |
| 5 | Autenticación y política de reintento de `WmsShipmentClient` hacia `GET /wms/dw/v1/shipment/get-shipment/{whseId}/{externOrderKey}` — sin definir | `orquestador-app/client/wms` | — |
| 6 | Agregar validación `@Size` a `SolicitudNotificacionRequest`/`PayloadEnriquecidoNotificacion` para reflejar los `maxLength` del contrato OpenAPI | `unogroup-app/controller`, `unogroup-app/dto` | §2.2 de esta bitácora |
| 7 | Confirmar si `orquestador-app/k8s` migró a Kustomize igual que `unogroup-app/k8s`, o sigue con manifiestos planos | `orquestador-app/k8s` (sin verificar) | §2.2 de esta bitácora |
| 8 | Valores reales de imagen/registro, proyecto/región/instancia de Cloud SQL, nombres de Secret/ConfigMap — placeholders `TBD_*` en ambos repositorios, depende de infraestructura (Implementación §8) | `orquestador-app/k8s`, `unogroup-app/k8s/overlays/prod` | — |
| 9 | Pipelines de CI/CD — con dos repositorios separados, hace falta un pipeline por repositorio (build, test, imagen, tag) en vez de uno que compile ambos módulos a la vez; no se ha definido herramienta ni configuración | `orquestador-app/Dockerfile`, `unogroup-app/Dockerfile` | — |
| 10 | Reflejar `respuesta_body`/`error_mensaje` de `ensamble_bitacora_partner` en el diagrama entidad-relación de Requerimientos §2.6.1 — columnas aceptadas por su valor operativo (§3 de esta bitácora) pero el ER documentado sigue sin incluirlas | `HUENSA-001_Diseno_Requerimientos_Modulo_Integracion_Ensamble.md` §2.6.1 | §3 de esta bitácora |
