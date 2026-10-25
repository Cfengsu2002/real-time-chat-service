# Deployment

## Docker Compose

The default project is `relay-chat-v2`. It creates new PostgreSQL and Redis volumes, separate from the earlier `relay-chat` stack. Keep the old volumes when moving to this schema; its Flyway history differs from the previous version.

Generate a local environment file once in a new checkout:

```bash
umask 077
printf 'JWT_SECRET=%s\nPOSTGRES_PASSWORD=%s\n' \
  "$(openssl rand -hex 48)" "$(openssl rand -hex 24)" > .env
docker compose up -d --build --wait
```

Keep these values stable when restarting existing services. The PostgreSQL password must match the credentials used to initialize its persistent volume. `.env` is excluded from Git and the Docker build context.

| Setting | Default or purpose |
| --- | --- |
| `JWT_SECRET` | Required; at least 32 UTF-8 bytes, shared by all instances. |
| `POSTGRES_PASSWORD` | Required; password for the PostgreSQL user. |
| `POSTGRES_DB` / `POSTGRES_USER` | `relay_v2` / `relay`. |
| `APP_PORT` / `APP2_PORT` | `18080` / `18081`, bound to `127.0.0.1`. |
| `APP_ALLOWED_ORIGINS` | Exact localhost and 127.0.0.1 origins for both default ports. |
| `RELAY_IMAGE` | `relay-chat:1.0.0`, built locally from the Dockerfile. |
| `JWT_TTL_SECONDS` | `86400`. |

Changing host ports also requires updating `APP_ALLOWED_ORIGINS`. For an HTTPS site, put a reverse proxy in front of the app and set the site's exact HTTPS origin. Forward WebSocket upgrades to `/ws` and route HTTP API calls to the same application pool.

### Multiple instances

```bash
docker compose --profile scale up -d --build --wait
docker compose --profile scale ps
curl --fail http://localhost:18080/actuator/health/readiness
curl --fail http://localhost:18081/actuator/health/readiness
```

The `app2` service uses the same image and configuration as `app`. A ticket issued by one instance can be consumed by the other. No sticky session is required: PostgreSQL holds the message history, Redis holds connection tickets, and Redis Pub/Sub distributes live notifications.

`/actuator/health/liveness` checks application liveness. `/actuator/health/readiness` includes PostgreSQL and Redis availability. Compose waits for healthy dependencies before starting the app.

### Stop and restart

```bash
docker compose --profile scale down
docker compose --profile scale up -d --wait
```

These commands retain the named volumes. Database backups and schema changes should be managed independently of container replacement.

## Local PostgreSQL and Redis

```bash
export JWT_SECRET="$(openssl rand -hex 48)"
export DATABASE_URL="jdbc:postgresql://localhost:5432/relay_v2"
export DATABASE_USERNAME="relay"
export DATABASE_PASSWORD="your-database-password"
export REDIS_HOST="localhost"
export REDIS_PORT="6379"
export APP_ALLOWED_ORIGINS="http://localhost:8080,http://127.0.0.1:8080"
./mvnw spring-boot:run
```

Create the database and user before startup. Flyway applies migrations and Hibernate validates the resulting schema. Standard `SPRING_DATASOURCE_*` and `SPRING_DATA_REDIS_*` environment properties are also supported.

## Kubernetes examples

`deploy/k8s` contains a two-replica application Deployment, a ClusterIP Service, PostgreSQL and Redis StatefulSets with persistent claims, a ConfigMap, and a CPU-based HorizontalPodAutoscaler. The included HPA ranges from two to four application replicas and needs metrics-server. Persistent claims need a default StorageClass.

Build the image, then push it to a registry accessible by your cluster or load it into a local cluster. The manifests reference `relay-chat:1.0.0`; update `deploy/k8s/app.yaml` if using a registry image.

```bash
docker build -t relay-chat:1.0.0 .
kubectl apply -f deploy/k8s/namespace.yaml
cp deploy/k8s/secret.example.yaml deploy/k8s/secret.yaml
# Replace the two placeholder values in secret.yaml.
kubectl apply -f deploy/k8s/secret.yaml
kubectl apply -k deploy/k8s
kubectl -n relay-chat rollout status deployment/relay-chat
kubectl -n relay-chat port-forward service/relay-chat 8080:8080
```

The real `secret.yaml` is ignored by Git and excluded from Kustomize. Configure `APP_ALLOWED_ORIGINS` in the ConfigMap for the address used by the browser. The application runs as a non-root user with a read-only root filesystem and writable temporary storage.

The manifests are rendered and schema-validated in CI. Deployment on a live Kubernetes cluster, storage provisioning, and HPA behavior require cluster-specific verification.
