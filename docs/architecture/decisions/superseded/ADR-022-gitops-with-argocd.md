# ADR-022: GitOps with ArgoCD

> **SUPERSEDED.** Superseded in practice by [ADR-037](../ADR-037-modular-monolith.md) — Karyo deploys as a
4-container Compose stack (`karyo-app`, `postgresql`, `keycloak`, `nginx`). Kubernetes
remains a long-term option for multi-site or edge deployments, not current reality.
>
> Retained as decision history: it records why the boundaries in the current modular
> monolith are drawn where they are. Do not treat anything below as current.

## Status
Superseded

## Context
Karyo WMS targets multiple deployment environments (dev, staging, prod) across multiple deployment models (full cloud Kubernetes, on-premise K3s, hybrid edge+cloud). The deployment process must be:

- **Reproducible**: The same configuration produces the same deployment state every time
- **Auditable**: Every deployment change is traceable to a specific Git commit with an author
- **Rollback-capable**: Any deployment can be reverted to a previous known-good state
- **Multi-environment**: Configurations differ between dev, staging, and production (resource limits, replicas, feature flags, database URLs)
- **Declarative**: The desired state of the system is described, not the steps to get there
- **Secure**: Deployment credentials are not exposed to CI/CD pipelines or developers

The CI pipeline (GitHub Actions) builds container images and runs tests. The CD pipeline must take these images and deploy them to the appropriate Kubernetes clusters. Push-based CD (CI pipeline applies manifests directly to clusters) requires granting CI pipelines cluster credentials, creating a security risk and coupling the build process to the deployment process.

## Decision
We will use **ArgoCD** as the GitOps continuous delivery tool for all Kubernetes deployments.

**Repository Structure:**

```
karyo-gitops/                              # Separate repo from application code
├── base/                                  # Base manifests (shared across environments)
│   ├── inventory-service/
│   │   ├── deployment.yaml
│   │   ├── service.yaml
│   │   ├── hpa.yaml
│   │   ├── configmap.yaml
│   │   └── kustomization.yaml
│   ├── order-service/
│   │   └── ...
│   ├── auth-service/
│   │   └── ...
│   ├── infrastructure/
│   │   ├── kafka/
│   │   ├── postgresql/
│   │   ├── redis/
│   │   ├── keycloak/
│   │   └── monitoring/
│   └── kustomization.yaml
├── overlays/                              # Environment-specific overrides
│   ├── dev/
│   │   ├── inventory-service/
│   │   │   └── kustomization.yaml         # 1 replica, dev DB URL, debug logging
│   │   ├── kustomization.yaml
│   │   └── namespace.yaml
│   ├── staging/
│   │   ├── inventory-service/
│   │   │   └── kustomization.yaml         # 2 replicas, staging DB, info logging
│   │   ├── kustomization.yaml
│   │   └── namespace.yaml
│   └── prod/
│       ├── inventory-service/
│       │   └── kustomization.yaml         # 3+ replicas, prod DB, warn logging, HPA
│       ├── kustomization.yaml
│       └── namespace.yaml
└── argocd/                                # ArgoCD application definitions
    ├── dev-apps.yaml
    ├── staging-apps.yaml
    └── prod-apps.yaml
```

**Kustomize for Environment Overlays:**

```yaml
# overlays/prod/inventory-service/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../../../base/inventory-service
patchesStrategicMerge:
  - deployment-patch.yaml
configMapGenerator:
  - name: inventory-config
    behavior: merge
    literals:
      - QUARKUS_PROFILE=prod
      - QUARKUS_LOG_LEVEL=WARN
      - QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://prod-db:5432/karyo_inventory
images:
  - name: karyo/inventory-service
    newTag: v1.2.3  # Updated by CI pipeline or image updater
```

**Deployment Flow:**

```
Developer pushes code to application repo
    │
    ▼
GitHub Actions CI pipeline:
  1. Build & test
  2. Build container image (Quarkus Jib)
  3. Push image to container registry (tagged with git SHA + semver)
  4. Update image tag in karyo-gitops repo (automated PR or direct commit to dev)
    │
    ▼
ArgoCD detects change in karyo-gitops repo
    │
    ▼
ArgoCD syncs manifests to Kubernetes cluster
    │
    ▼
Kubernetes rolls out the new deployment
    │
    ▼
ArgoCD verifies health checks pass
```

**Promotion Strategy:**
- **dev**: Auto-sync (ArgoCD automatically applies changes when karyo-gitops/overlays/dev changes)
- **staging**: Auto-sync with manual image tag promotion (CI updates dev image tag; human promotes to staging via PR)
- **prod**: Manual sync with approval gates (PR to prod overlay requires 1+ approvals, ArgoCD sync is manual or approval-gated)

**ArgoCD Application Definition:**

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: karyo-inventory-prod
  namespace: argocd
spec:
  project: karyo-prod
  source:
    repoURL: https://github.com/karyo-wms/karyo-gitops.git
    targetRevision: main
    path: overlays/prod/inventory-service
  destination:
    server: https://kubernetes.default.svc
    namespace: karyo-prod
  syncPolicy:
    automated:
      prune: true
      selfHeal: true       # Auto-correct manual changes (drift detection)
    syncOptions:
      - CreateNamespace=true
    retry:
      limit: 3
      backoff:
        duration: 5s
        maxDuration: 3m
  ignoreDifferences:       # Ignore fields managed by other controllers
    - group: apps
      kind: Deployment
      jsonPointers:
        - /spec/replicas   # Managed by HPA
```

**Drift Detection and Self-Healing:**
- ArgoCD continuously compares the live cluster state against the Git-declared state
- `selfHeal: true` automatically reverts manual changes made directly to the cluster (kubectl apply, manual scaling, etc.)
- Alerts are sent to Slack/PagerDuty when drift is detected and corrected

## Consequences

### Positive
- Every deployment is traceable to a Git commit — full audit trail of who changed what and when
- Rollback is a simple `git revert` on the GitOps repo — ArgoCD syncs the previous state automatically
- Separation of CI and CD — the CI pipeline does not need cluster credentials, reducing the blast radius of CI compromises
- Drift detection ensures the cluster state always matches the declared state — prevents configuration drift over time
- Environment promotion via Git PRs enables code review of infrastructure changes before they reach production
- Declarative configuration is self-documenting — the GitOps repo IS the documentation of the deployment state
- Works with any Kubernetes cluster (cloud, on-premise K3s, edge) — ArgoCD is cluster-agnostic

### Negative
- Separate GitOps repository adds coordination overhead between application code changes and deployment configuration changes
- ArgoCD itself must be deployed and maintained (HA setup requires 3 replicas, its own PostgreSQL or Redis for state)
- Learning curve for teams unfamiliar with GitOps workflows and Kustomize
- Secret management requires additional tooling (Sealed Secrets or External Secrets Operator) since secrets cannot be stored in Git
- Image tag updates in the GitOps repo create additional commits that clutter the Git history
- ArgoCD UI/API is another attack surface that must be secured (RBAC, OIDC integration)

### Neutral
- ArgoCD supports both Kustomize and Helm; we chose Kustomize for simplicity but can add Helm charts for third-party dependencies if needed
- ArgoCD Image Updater can automate image tag updates in the GitOps repo, reducing the manual step in the CI pipeline
- For edge deployments, ArgoCD can manage remote clusters from a central management plane or be deployed locally on each edge node

## Alternatives Considered

### Alternative 1: Flux
- **Pros**: CNCF graduated project, native Git-based reconciliation, lighter resource footprint than ArgoCD, Kustomize and Helm support, image automation built-in
- **Cons**: Smaller community and ecosystem than ArgoCD, less mature UI (no built-in dashboard, relies on Weave GitOps or third-party), fewer enterprise features (RBAC, SSO, multi-cluster management)
- **Why rejected**: ArgoCD has a larger community, more mature UI for operational visibility, better enterprise features (RBAC, SSO via OIDC, multi-cluster support), and broader adoption in production environments. The feature gap between ArgoCD and Flux has narrowed, but ArgoCD's operational maturity and UI make it more suitable for a WMS where non-developer ops staff need deployment visibility.

### Alternative 2: Jenkins CD (Push-Based)
- **Pros**: Mature ecosystem, flexible pipeline configuration, large plugin ecosystem, familiar to many teams
- **Cons**: Push-based model — CI pipeline needs cluster credentials (security risk). No built-in drift detection. No self-healing. No declarative state management. Jenkins itself requires significant operational overhead (master/agent architecture, plugin management, security patches).
- **Why rejected**: Push-based deployment is fundamentally less secure and less reliable than pull-based GitOps. No drift detection means manual cluster changes go undetected. Jenkins operational overhead contradicts our goal of minimal infrastructure management.

### Alternative 3: Spinnaker
- **Pros**: Purpose-built for continuous delivery, multi-cloud support, canary deployments built-in, pipeline visualization
- **Cons**: Heavy infrastructure requirements (multiple microservices: Orca, Clouddriver, Deck, etc.), complex to deploy and operate, Java-based with high memory footprint, overkill for our deployment scale, steep learning curve
- **Why rejected**: Spinnaker's infrastructure requirements and operational complexity are disproportionate to our needs. ArgoCD provides sufficient deployment capabilities (canary via Argo Rollouts if needed) with a fraction of the operational overhead.

## Implementation Notes
- Deploy ArgoCD via its official Helm chart in an `argocd` namespace on each target cluster
- Configure ArgoCD OIDC integration with Keycloak (ADR-019) for operator authentication to the ArgoCD UI
- Use ArgoCD Projects to isolate permissions: `karyo-dev`, `karyo-staging`, `karyo-prod` with appropriate RBAC
- Implement Sealed Secrets (Bitnami) for encrypting secrets in the GitOps repo; consider External Secrets Operator for integration with Vault or cloud secret managers
- Set up ArgoCD notifications to send deployment status to Slack and PagerDuty
- For edge deployments, deploy a lightweight ArgoCD instance on each edge K3s cluster, or use ArgoCD's multi-cluster management from a central cloud cluster (requires network connectivity)
- Implement a GitHub Actions workflow that updates image tags in the GitOps repo after successful CI builds: auto-commit to dev overlay, create PR for staging/prod overlays
- Use ArgoCD Application Sets for managing multiple similar services to reduce boilerplate in ArgoCD application definitions

## Related Decisions
- [ADR-019](../ADR-019-oauth2-oidc-with-keycloak.md): OAuth2/OIDC with Keycloak — ArgoCD uses Keycloak for SSO
- [ADR-021](ADR-021-mtls-for-service-communication.md): mTLS — service mesh configuration managed via GitOps
- [ADR-023](../ADR-023-prometheus-grafana-observability.md): Prometheus + Grafana — monitoring stack deployed via GitOps
- [ADR-030](../ADR-030-conventional-commits-semver.md): Conventional Commits — semver tags used for image tagging

## References
- [ArgoCD Documentation](https://argo-cd.readthedocs.io/)
- [GitOps Principles (OpenGitOps)](https://opengitops.dev/)
- [Kustomize Documentation](https://kustomize.io/)
- [ArgoCD Image Updater](https://argocd-image-updater.readthedocs.io/)
- [Sealed Secrets](https://sealed-secrets.netlify.app/)

## Revision History
- 2026-02-15: Initial version
