# ADR-007: Kubernetes as Orchestration Platform

> **SUPERSEDED.** Superseded in practice by [ADR-037](../ADR-037-modular-monolith.md) — Karyo deploys as a
4-container Compose stack (`karyo-app`, `postgresql`, `keycloak`, `nginx`). Kubernetes
remains a long-term option for multi-site or edge deployments, not current reality.
>
> Retained as decision history: it records why the boundaries in the current modular
> monolith are drawn where they are. Do not treat anything below as current.

## Status
Superseded

## Context
Karyo WMS consists of 10+ microservices (see [ADR-001](ADR-001-microservices-architecture.md)) that must be deployed, scaled, and managed across three deployment scenarios:

1. **Full cloud:** Multi-tenant SaaS deployment on AWS/GCP/Azure with auto-scaling, HA, and managed services.
2. **Full on-premise:** Customer-managed infrastructure with limited cloud access, common in regulated industries (pharma, defense).
3. **Hybrid edge + cloud:** Core services run at the warehouse (edge) for low-latency operations and offline capability; AI, reporting, and integrations run in the cloud.

Key requirements:
- **Auto-scaling:** Inventory-service must scale during wave planning peaks (3-5x normal load), then scale down to save resources.
- **Rolling updates:** 24/7 warehouse operations demand zero-downtime deployments via rolling updates with health checks.
- **Service health management:** Automatic restart of failed services, readiness-based traffic routing, and graceful shutdown with Kafka consumer rebalancing.
- **Edge constraint:** Core services must run on edge hardware with < 2GB RAM total. The orchestration platform itself must be lightweight enough to not consume the RAM budget.
- **Portability:** Deploy to any cloud or on-premise without vendor lock-in.
- **Secret management:** Secure handling of database credentials, API keys, and TLS certificates.

## Decision
We will use **Kubernetes 1.28+** for cloud and on-premise production deployments, and **K3s** (lightweight Kubernetes distribution) for edge warehouse deployments.

### Cloud Deployment

- **Managed Kubernetes:** EKS (AWS), GKE (Google Cloud), or AKS (Azure) based on customer cloud preference.
- **Namespace strategy:** Per-environment isolation (`karyo-dev`, `karyo-staging`, `karyo-prod`, `karyo-monitoring`, `karyo-infra`).
- **Service deployment:** Helm charts with Kustomize overlays for environment-specific configuration.
- **Auto-scaling:** HPA (Horizontal Pod Autoscaler) based on CPU, memory, and custom metrics (Kafka consumer lag).
- **Pod anti-affinity:** Spread replicas across availability zones for HA.
- **Resource defaults:**

```yaml
resources:
  requests:
    cpu: 100m
    memory: 128Mi
  limits:
    cpu: 500m
    memory: 256Mi
autoscaling:
  minReplicas: 2
  maxReplicas: 10
  targetCPU: 70
```

### Edge Deployment (K3s)

K3s is a certified Kubernetes distribution that runs in < 512 MB RAM:
- Single-node or 2-node HA deployment
- Embedded etcd (no external etcd cluster needed)
- Embedded containerd (no Docker daemon needed)
- SQLite or PostgreSQL as backing store
- Supports same Helm charts and Kustomize overlays as full K8s

**Edge resource allocation:**

| Component | RAM | CPU |
|-----------|-----|-----|
| K3s system | 256 MB | 200m |
| Core services (6 + BFF) | 1,280 MB | 1,000m |
| PostgreSQL | 256 MB | 200m |
| Redpanda (Kafka) | 256 MB | 200m |
| Redis | 64 MB | 50m |
| **Total** | **~2,112 MB** | **~1,650m** |

With Quarkus JVM mode at 128-256 MB per service, the edge deployment fits within a standard 4GB/4-core edge server with headroom for OS and monitoring agents.

### Health Probes

```yaml
livenessProbe:
  httpGet:
    path: /q/health/live
    port: 8080
  initialDelaySeconds: 5
  periodSeconds: 10
readinessProbe:
  httpGet:
    path: /q/health/ready
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
startupProbe:
  httpGet:
    path: /q/health/started
    port: 8080
  failureThreshold: 30
  periodSeconds: 1
```

### GitOps Deployment

ArgoCD monitors a GitOps repository and syncs Kubernetes manifests:
- Dev: auto-sync on push to `develop` branch
- Staging: manual approval for sync
- Production: manual approval with progressive rollout (canary)

## Consequences

### Positive
- **Portable across clouds:** Kubernetes is supported by all major cloud providers and on-premise. No vendor lock-in — deploy to AWS today, migrate to GCP tomorrow, run on-premise for regulated customers.
- **Auto-scaling:** HPA automatically scales services based on load. Inventory-service scales up during wave planning, task-service scales up during peak picking, then both scale down during quiet periods.
- **Self-healing:** Kubernetes restarts crashed pods, reschedules pods from failed nodes, and removes unhealthy pods from service endpoints based on readiness probes.
- **Rolling updates:** Zero-downtime deployments via rolling update strategy with configurable maxUnavailable and maxSurge. Readiness probes ensure traffic only routes to healthy pods.
- **Secret management:** Kubernetes Secrets (encrypted at rest) for database credentials, API keys, and TLS certificates. Can integrate with external secret managers (Vault, AWS Secrets Manager) via External Secrets Operator.
- **Service discovery:** Kubernetes DNS provides automatic service discovery (`inventory-service.karyo-prod.svc.cluster.local`). No need for Eureka, Consul, or other service registries.
- **Network policies:** Fine-grained network isolation between services and namespaces (e.g., reporting-service cannot directly access inventory database).
- **K3s for edge:** Same Kubernetes API, same Helm charts, same monitoring — but in < 512 MB RAM. Edge deployments use the same tooling as cloud deployments.

### Negative
- **Operational complexity:** Kubernetes requires specialized knowledge (pod lifecycle, networking, storage classes, RBAC, troubleshooting). The team needs Kubernetes expertise or training.
- **Resource overhead:** K3s consumes ~256 MB for the system itself, which is significant in the 2GB edge budget. Full Kubernetes (kubeadm) would consume 500MB+, making it unsuitable for edge.
- **Complexity for small deployments:** A single-warehouse customer with one server might find Kubernetes overkill. A Docker Compose alternative may be offered for evaluation/demo purposes, but production deployment is Kubernetes-only.
- **Networking complexity:** Service mesh (Istio/Linkerd) is not adopted initially due to edge RAM constraints, but mTLS between services requires manual certificate management or a lighter solution.

### Neutral
- Quarkus generates Kubernetes manifests via the `quarkus-kubernetes` extension, reducing manual YAML writing.
- Helm charts and Kustomize overlays add a learning curve but provide environment-specific configuration management that scales well.
- Container image builds use Quarkus Jib (no Dockerfile needed), producing optimized distroless-based images.

## Alternatives Considered

### Alternative 1: Docker Swarm
- **Pros**: Simpler than Kubernetes, built into Docker, easier to learn, lower resource overhead, suitable for small deployments.
- **Cons**: No auto-scaling (HPA equivalent), limited community investment (Docker has shifted focus away from Swarm), no equivalent to K3s for edge, smaller ecosystem (no Helm, no ArgoCD), no managed cloud offerings, limited network policy support.
- **Why rejected**: Docker Swarm lacks auto-scaling, which is critical for WMS peak-load handling (wave planning spikes). The diminishing community investment and lack of managed cloud offerings make it a risky long-term choice. Docker Compose may be offered for demos and local development, but Swarm is not suitable for production 24/7 operations.

### Alternative 2: HashiCorp Nomad
- **Pros**: Simpler than Kubernetes, multi-workload support (containers, VMs, Java JARs), lower resource overhead, easier to set up on-premise.
- **Cons**: Smaller ecosystem (no equivalent to Helm charts, ArgoCD, Prometheus operator), fewer managed cloud offerings, less community adoption in the logistics/WMS domain, no K3s-equivalent for edge (Nomad is already lightweight, but lacks the Kubernetes certification and compatibility guarantees), license changes (BSL from 2023).
- **Why rejected**: Nomad's BSL license change introduces uncertainty for an open-source project. The Kubernetes ecosystem (Helm, ArgoCD, Prometheus operator, cert-manager, external-secrets-operator) provides significantly more operational tooling. K3s specifically addresses the edge deployment need with a certified Kubernetes distribution.

### Alternative 3: AWS ECS (Elastic Container Service)
- **Pros**: Simpler than Kubernetes for AWS-only deployments, deep AWS integration (IAM, VPC, CloudWatch), managed control plane, no Kubernetes expertise needed.
- **Cons**: AWS vendor lock-in (no on-premise, no multi-cloud), no edge deployment option, no portability to GCP/Azure, proprietary task definitions (not Kubernetes manifests), limited community tooling compared to Kubernetes ecosystem.
- **Why rejected**: Karyo WMS must support on-premise, multi-cloud, and edge deployments. ECS's AWS-only nature eliminates two of the three deployment scenarios. Customers requiring on-premise deployment cannot use ECS.

## Implementation Notes
- **Cloud K8s:** Use managed offerings (EKS, GKE, AKS) to offload control plane management.
- **Edge K3s:** Install via `curl -sfL https://get.k3s.io | sh -` on edge servers. Use K3s with embedded PostgreSQL (instead of etcd) to share the database instance with services.
- **Helm charts:** One Helm chart per service with value overrides per environment. Shared base chart for common patterns (health probes, resource limits, RBAC).
- **ArgoCD:** Deploy in `karyo-monitoring` namespace. App-of-apps pattern for managing all services.
- **Network policies:** Default deny-all, explicit allow rules per service communication path.
- **Monitoring:** kube-prometheus-stack (Prometheus + Grafana + Alertmanager) deployed in `karyo-monitoring` namespace.

## Related Decisions
- [ADR-001: Microservices Architecture Style](ADR-001-microservices-architecture.md)
- [ADR-002: Quarkus as Microservices Framework](../ADR-002-quarkus-framework.md)
- [ADR-009: API Gateway Pattern (Kong)](ADR-009-api-gateway-kong.md)
- [ADR-013: Redis for Distributed Caching](../ADR-013-redis-caffeine-caching.md)

## References
- Kubernetes: https://kubernetes.io/
- K3s: https://k3s.io/
- Helm: https://helm.sh/
- ArgoCD: https://argo-cd.readthedocs.io/
- Quarkus Kubernetes extension: https://quarkus.io/guides/deploying-to-kubernetes

## Revision History
- 2026-02-15: Initial version
