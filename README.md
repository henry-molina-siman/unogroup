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

**Producción (GKE)**: vía el `Secret` `unogroup-app-solutionone-secret` referenciado en `k8s/base/deployment.yaml` (su creación real es responsabilidad de Terraform/infra, fuera de alcance de este repositorio).

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

## Desplegar en Kubernetes con Kustomize

Manifiestos en `k8s/`: una capa `base/` (Deployment, Service, ServiceAccount, ConfigMap, Secret placeholder) y overlays por ambiente en `k8s/overlays/` (`local`, `prod`). Ver `k8s/base/kustomization.yaml` y `k8s/overlays/*/kustomization.yaml` para el detalle de qué parchea cada uno.

### Desplegar en Docker Desktop (local)

Requiere Docker Desktop con Kubernetes habilitado (Settings → Kubernetes → Enable Kubernetes) y el contexto `docker-desktop` seleccionado.

```bash
# 0. Verificar que el contexto activo es docker-desktop
kubectl config current-context
# si no lo es:
kubectl config use-context docker-desktop
```

```bash
# 1. Construir la imagen local (mismo tag que referencia k8s/overlays/local)
docker build -t unogroup-app:local .
```

```bash
# 2. Crear el namespace (una sola vez; no lo gestiona kustomize)
kubectl create namespace ensambles
```

```bash
# 3. Aplicar el overlay local
kubectl apply -k k8s/overlays/local
```

```bash
# 4. Confirmar que el rollout terminó
kubectl -n ensambles rollout status deployment/unogroup-app

kubectl -n ensambles get pods,svc,deploy
```

### Port-forward y prueba de endpoints

El `Service` (`svc-unogroup`) es `ClusterIP` — no accesible fuera del clúster, incluso en local. Para llegar a él desde la máquina host, usar `port-forward`:

```bash
# Deja este comando corriendo en su propia terminal (bloquea)
kubectl -n ensambles port-forward svc/svc-unogroup 8081:80
```

En otra terminal:

```bash
curl -s http://localhost:8081/actuator/health/readiness
curl -s http://localhost:8081/actuator/health/liveness

curl -X POST http://localhost:8081/internal/unogroup/solicitudes \
  -H "Content-Type: application/json" \
  -d @docs/ejemplo_solicitud_creacion.json
```

Alternativa sin dejar un proceso bloqueando la terminal (Windows/PowerShell): lanzarlo en background y luego detenerlo por PID.

```powershell
$pf = Start-Process kubectl -ArgumentList "-n","ensambles","port-forward","svc/svc-unogroup","8081:80" -PassThru -NoNewWindow
# ... usar curl / navegador contra localhost:8081 ...
Stop-Process -Id $pf.Id -Force
```

### Logs y troubleshooting

```bash
# Logs del Deployment (todas las réplicas)
kubectl -n ensambles logs deploy/unogroup-app --tail=100 -f

# Describe si un pod no llega a Ready (eventos, probes fallidos, etc.)
kubectl -n ensambles describe pod -l app=unogroup-app
```

Si el pod queda en `ImagePullBackOff`: falta reconstruir la imagen (`docker build -t unogroup-app:local .`) — el overlay local usa `imagePullPolicy: IfNotPresent` sobre la imagen ya cargada en el Docker de Docker Desktop, no la baja de ningún registry.

Si cambian variables del `ConfigMap`/`Secret` después de un `apply`, hay que reiniciar el Deployment para que los pods las recojan (`envFrom` no es "live-reload"):

```bash
kubectl -n ensambles rollout restart deployment/unogroup-app
```

### Redesplegar tras un cambio de código

```bash
docker build -t unogroup-app:local .
kubectl -n ensambles rollout restart deployment/unogroup-app
kubectl -n ensambles rollout status deployment/unogroup-app
```

### Ver el YAML final sin aplicar (dry-run)

Útil para revisar qué va a aplicar cualquiera de los overlays antes de correrlo contra un clúster real:

```bash
kubectl kustomize k8s/overlays/local
kubectl kustomize k8s/overlays/prod
```

### Desmontar (local)

```bash
# Borra Deployment, Service, ServiceAccount, ConfigMap y Secret del overlay
kubectl delete -k k8s/overlays/local

# El namespace no lo gestiona kustomize — borrarlo aparte si ya no se necesita
kubectl delete namespace ensambles
```

### Producción (GKE)

El overlay `k8s/overlays/prod` **no** es para uso local — antes de aplicarlo hay que completar los placeholders:

- `k8s/overlays/prod/kustomization.yaml` → `newName` con el path real de Artifact Registry (`images:`).
- `k8s/overlays/prod/serviceaccount-patch.yaml` → `TBD_PROJECT_ID` en la anotación `iam.gke.io/gcp-service-account` (Workload Identity).
- `k8s/base/secret.yaml` → reemplazar `CHANGE_ME` con las credenciales reales de Solution One por el mecanismo que use cada ambiente (Secret Manager / sealed-secrets / CI), nunca commiteadas en texto plano.

Con eso resuelto, el despliegue contra un clúster GKE real sigue el mismo patrón que en local, apuntando `kubectl` al contexto correcto:

```bash
kubectl config use-context <contexto-del-clúster-gke>
kubectl apply -k k8s/overlays/prod
kubectl -n ensambles rollout status deployment/unogroup-app
```
