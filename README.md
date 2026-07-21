# unogroup-app — Sistema de Ensambles

Comandos de referencia usados durante el desarrollo. Ejecutar desde la raíz de este repositorio salvo que se indique lo contrario.

> Repositorio independiente de `orquestador-app` (sin `pom.xml` padre ni módulo común entre ambos — ver `docs/HUENSA-001_Implementacion_Modulo_Integracion_Ensamble_v2.md` §1.1). La consistencia de contrato entre los dos se garantiza vía `docs/HUENSA-001_openapi_V2.yaml`, no vía una dependencia de build compartida.

## Build

Compilar y correr los tests (no requiere Docker):

```bash
mvn clean test
```

Empaquetar el jar ejecutable (sin correr tests):

```bash
mvn package -DskipTests
```

## Correr localmente (sin Docker)

`unogroup-app` no tiene ninguna dependencia de infraestructura (sin base de datos, sin sidecar), así que corre directo con el jar:

```bash
java -jar target/unogroup-app-1.0.0-SNAPSHOT.jar --server.port=8081
```

`--server.port=8081` es opcional — solo para no chocar con el `8080` por defecto si hay otro proceso usándolo.

### Detener la app

```powershell
# Encontrar el PID
Get-Process -Name java

# Detenerlo
Stop-Process -Id <PID> -Force
```

## Correr en modo debug

**Debugger remoto** (adjuntar breakpoints desde IntelliJ — `Run > Edit Configurations > + > Remote JVM Debug`, puerto `5005`):

```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar target/unogroup-app-1.0.0-SNAPSHOT.jar --server.port=8081
```

`suspend=n` deja arrancar la app sin esperar al debugger; usar `suspend=y` para que espere antes de arrancar.

**Logs en nivel DEBUG** (sin debugger):

```bash
java -jar target/unogroup-app-1.0.0-SNAPSHOT.jar --server.port=8081 --logging.level.com.siman.ensambles.unogroup=DEBUG
```

## Configurar credenciales de Solution One

Vacías por defecto (`src/main/resources/application.yml`, claves `ensambles.adapter.solutionone.usuario`/`.password`) — sin ellas, cualquier llamada real a Solution One responde `401`.

**Local**, vía variables de entorno:

```bash
export SOLUTIONONE_USUARIO=xxx
export SOLUTIONONE_PASSWORD=yyy
java -jar target/unogroup-app-1.0.0-SNAPSHOT.jar --server.port=8081
```

o como argumento de arranque:

```bash
java -jar target/unogroup-app-1.0.0-SNAPSHOT.jar --ensambles.adapter.solutionone.usuario=xxx --ensambles.adapter.solutionone.password=yyy
```

**Producción (GKE)**: vía el `Secret` `unogroup-app-solutionone-secret` referenciado en `k8s/deployment.yaml` (su creación real es responsabilidad de Terraform/infra, fuera de alcance de este repositorio).

## Probar los endpoints con `curl`

Health checks:

```bash
curl -s http://localhost:8081/actuator/health/readiness
curl -s http://localhost:8081/actuator/health/liveness
```

Notificación de creación / actualización (ejemplos en `docs/ejemplo_solicitud_creacion.json` y `docs/ejemplo_solicitud_actualizacion.json`):

```bash
curl -X POST http://localhost:8081/internal/unogroup/solicitudes \
  -H "Content-Type: application/json" \
  -d @docs/ejemplo_solicitud_creacion.json

curl -X POST http://localhost:8081/internal/unogroup/solicitudes \
  -H "Content-Type: application/json" \
  -d @docs/ejemplo_solicitud_actualizacion.json
```

Ambas responden `202 Accepted` de inmediato; el procesamiento real (llamada a Solution One + callback a `orquestador-app`) corre después, en background, y queda en los logs de la app.
