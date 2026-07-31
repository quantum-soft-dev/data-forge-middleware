# GKE deployment (forge)

forge runs in the **`forge`** namespace across three clusters, mirroring bitbi
(`bitbi-dev` / `bitbi-stage` / `bitbi-production`). Branch → cluster:
`develop` → dev, `stage` → stage, `main` → prod.

Manifests live in [`k8s/`](../../k8s): `base/` + `overlays/{dev,stage,prod}` (kustomize).
Backend (`forge-backend`, 8080) and frontend (`forge-frontend`, 80) are separate Deployments;
dev additionally runs an in-cluster `forge-redis`. **Dev is internal-only** (ClusterIP, no public
ingress) — bitbi reaches forge at `http://forge-backend.forge.svc.cluster.local:8080`.

## What kustomize applies vs. what is provisioned out-of-band

`kubectl apply -k` creates: namespace, ServiceAccount, ConfigMaps, Deployments, Services, HPA, PDB,
and (dev) Redis. It does **not** create Secrets — those are provisioned separately so secrets never
live in git. The backend pod will stay `Pending`/`CrashLoop` until both exist:

- `forge-secrets` — app credentials, synced via `sync-secrets.sh` (below);
- `forge-backend-grpc-tls` (dev) — self-signed cert for the Delta gRPC port, see
  [gRPC ingestion](#grpc-ingestion--delta-client-v2-dev).

## Prerequisites (provisioned via Terraform — see `infra/`)

- Artifact Registry repo `forge-app` in the target project.
- GCP SA `{env}-forge-app@{project}.iam.gserviceaccount.com` + Workload Identity binding to
  `serviceAccount:{project}.svc.id.goog[forge/forge-app]`.
- Cloud SQL database `dfm` + user `dfm-app` (dev: on `dev-bitbi-db`).
- GCS bucket `dfm-{env}-uploads` + HMAC keys for the S3-compatible client.
- (stage/prod) Memorystore Redis instance.

## Manual dev deploy

```bash
# 1) target the dev cluster
gcloud container clusters get-credentials dev-bitbi-cluster \
  --region us-central1 --project bitbi-dev

# 2) sync secrets (fill forge-secrets.dev.env from forge-secrets.example.env first)
./deploy/gke/sync-secrets.sh deploy/gke/forge-secrets.dev.env

# 3) set image tags and apply
cd k8s/overlays/dev
kustomize edit set image \
  us-central1-docker.pkg.dev/bitbi-dev/forge-app/backend=us-central1-docker.pkg.dev/bitbi-dev/forge-app/backend:<sha> \
  us-central1-docker.pkg.dev/bitbi-dev/forge-app/frontend=us-central1-docker.pkg.dev/bitbi-dev/forge-app/frontend:<sha>
kubectl apply -k .

# 4) watch rollout (first boot runs Flyway V1..V28 — allow several minutes)
kubectl -n forge rollout status deploy/forge-backend --timeout=600s

# 5) reach the admin UI without a public ingress
kubectl -n forge port-forward svc/forge-frontend 8081:80
```

In CI this is automated by `.github/workflows/app-deploy.yml` (Workload Identity Federation).

## External access — GKE Gateway (dev)

Dev is exposed through a **GKE Gateway** (GatewayClass `gke-l7-global-external-managed`),
the modern replacement for the retired ingress-nginx path and the equivalent of bitbi's
global external L7 Ingress. Manifests: [`k8s/overlays/dev/gateway.yaml`](../../k8s/overlays/dev/gateway.yaml)
(Gateway + HTTPRoutes + `HealthCheckPolicy`). The frontend nginx reverse-proxies `/api/*`
to `forge-backend`, so a single route to `forge-frontend` serves both the admin UI and the API.

Out-of-band GCP resources (created via gcloud; move to Terraform when promoting to stage/prod):

```bash
# reserved global static IP (Gateway references it by NAME via spec.addresses NamedAddress)
gcloud compute addresses create forge-dev-ip --global --project bitbi-dev --ip-version=IPV4
#   -> forge-dev-ip = 136.68.136.183

# Google-managed TLS cert via Certificate Manager + a cert map referenced by the
# `networking.gke.io/certmap` annotation on the Gateway
gcloud certificate-manager certificates create forge-dev-cert --domains=test.dfm.bitbi.io --project bitbi-dev
gcloud certificate-manager maps create forge-dev-certmap --project bitbi-dev
gcloud certificate-manager maps entries create forge-dev-entry \
  --map=forge-dev-certmap --certificates=forge-dev-cert --hostname=test.dfm.bitbi.io --project bitbi-dev
```

**DNS (required to finish):** create an `A` record `test.dfm.bitbi.io -> 136.68.136.183`.
The managed cert stays `PROVISIONING` and HTTPS returns the self-signed placeholder until
DNS resolves to the Gateway IP; once Google validates the domain the cert flips to `ACTIVE`.

> The HTTPS listener references a placeholder self-signed Secret `forge-dev-tls-placeholder`
> only to satisfy the Gateway API webhook (`certificateRefs` is required for `mode: Terminate`);
> the `certmap` annotation's Google-managed cert takes precedence at the load balancer.

Check status:

```bash
kubectl -n forge get gateway forge-gateway -o wide
gcloud certificate-manager certificates describe forge-dev-cert --project bitbi-dev \
  --format='value(managed.state)'
```

## gRPC ingestion — Delta Client v2 (dev)

The Delta v2 gRPC endpoint (`DeltaIngestion`, in-pod port 9090) is published through the **same**
Gateway host and port as the HTTP API: clients connect to `https://test.dfm.bitbi.io:443` with TLS
on (`grpc_insecure = false`). No separate host, DNS record, or port exists. Routing: an `HTTPRoute`
matches the gRPC path prefix `/com.bitbi.dfm.delta.v2.DeltaIngestion` (more specific than the
frontend catch-all `/`) and forwards to `forge-backend:9090`.

Google's external ALB only speaks HTTP/2 to backends **over TLS** (it does not validate the cert),
so the pod serves TLS on 9090 with a self-signed cert. Create it once, out-of-band:

```bash
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout /tmp/grpc-tls.key -out /tmp/grpc-tls.crt -subj "/CN=forge-backend-grpc"
kubectl -n forge create secret tls forge-backend-grpc-tls \
  --cert=/tmp/grpc-tls.crt --key=/tmp/grpc-tls.key
rm /tmp/grpc-tls.key /tmp/grpc-tls.crt
```

Pieces (all in git except the Secret): backend Deployment/Service expose port `grpc` 9090 (base);
dev overlay mounts the Secret and sets `DELTA_GRPC_TLS_*` (configmap patch), annotates the Service
`cloud.google.com/app-protocols: '{"grpc":"HTTP2"}'`, and adds `forge-grpc-route` + a TCP
`HealthCheckPolicy` on 9090 + a `GCPBackendPolicy` raising the LB backend timeout to 3600s so
long-lived ingest streams aren't severed (matches the server's max-connection-age).

Smoke test (expects `UNAUTHENTICATED` from `DeltaAuthInterceptor`, which proves LB → pod h2 works).
The server registers no gRPC reflection service, so grpcurl needs the proto (run from the repo root):

```bash
grpcurl -import-path src/main/proto -proto delta-ingestion.proto \
  -d '{}' test.dfm.bitbi.io:443 com.bitbi.dfm.delta.v2.DeltaIngestion/GetSyncState
```

## Metrics scraping (dev)

`/actuator/prometheus` and `/actuator/metrics/**` are served only to the CIDRs in
`METRICS_SCRAPE_ALLOWED_CIDRS` (`dfm.observability.metrics-scrape.allowed-cidrs`). The variable is
**empty by default and empty means deny**, so stage/prod stay exactly as they were; the dev overlay
sets `10.4.0.0/14,127.0.0.1/32,::1/128` — the cluster pod range the collector scrapes from, plus
loopback in both address families for `kubectl port-forward` (matching is per family, so an IPv4
entry never covers an IPv6 caller). A malformed entry is dropped with a WARN and leaves that range
denied; it does not stop the pod from booting. Everything else under `/actuator` remains denied, and
only `health,info,metrics,prometheus` are exposed by the app at all.

The check reads the socket peer, which is why `server.forward-headers-strategy` is pinned to `none`
in `application.yml` — turning it on would make the source address caller-supplied.

Collection uses the managed Prometheus that is already running in the cluster
(`gke-gmp-system/collector`); the dev overlay only adds a `PodMonitoring` (`podmonitoring.yaml`,
30 s). Verify after a deploy:

```bash
kubectl -n forge get podmonitoring forge-backend -o jsonpath='{.status.conditions[*].type}'
kubectl -n forge port-forward deploy/forge-backend 8080:8080 &
curl -s localhost:8080/actuator/prometheus | grep '^delta_'   # delta_sessions_started_total …
```

A 403 from that curl means the running pod has no matching CIDR. `forge-config` is a plain
ConfigMap consumed with `envFrom`, so applying the overlay does **not** restart anything — after a
change that only touches the ConfigMap, run `kubectl -n forge rollout restart deploy/forge-backend`.
If the variable is right and it still 403s, check the address family the connection actually used.

The `delta_*` counters are registered eagerly at startup, so a healthy pod always prints them, at
`0.0` if it has served no session. An empty grep with a **200** therefore does not mean ingestion
has been idle — it means the response came from something other than a current backend pod (the
port-forward landed on another workload, or the image predates the meters), or a meter filter is
dropping them. It does *not* indicate a missing `micrometer-registry-prometheus`: without that
dependency the endpoint is not mapped at all and the curl returns **404**, not an empty 200.

## Placeholders to fill before a real deploy

Overlay ConfigMaps contain `REPLACE_*` / `dev-dfm.us.auth0.com` placeholders for Auth0
(domain, SPA client id) and, for stage/prod, Memorystore IP and forge domain. Replace them with the
real values from the current forge AWS deployment / Auth0 tenant.
