# ADR-030: Conventional Commits and Semantic Versioning

## Status
Accepted

## Context
Karyo WMS consists of 9+ microservices, each with its own API contract, deployment lifecycle, and compatibility requirements. The project needs a consistent approach to:

- **Commit messages**: Understanding what changed and why from the Git history
- **Versioning**: Communicating compatibility expectations to service consumers (other Karyo services, external integrators)
- **Changelog generation**: Producing release notes without manual curation
- **Release automation**: Determining version bumps automatically from commit history
- **Code review**: Standardized PR descriptions and reviewability
- **Branching**: Clear workflow for feature development, bug fixes, and releases

The project will eventually have multiple contributors across backend, frontend, and infrastructure. Consistent conventions reduce coordination overhead and enable automation.

## Decision
We will adopt **Conventional Commits** for commit messages and **Semantic Versioning (semver)** for service releases.

**Conventional Commit Format:**

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

**Commit Types:**

| Type | Semver Impact | Usage |
|------|-------------|-------|
| `feat` | MINOR bump | New feature or capability |
| `fix` | PATCH bump | Bug fix |
| `refactor` | PATCH bump | Code restructuring without behavior change |
| `perf` | PATCH bump | Performance improvement |
| `test` | No bump | Test additions or modifications |
| `docs` | No bump | Documentation changes |
| `chore` | No bump | Build, CI, dependency updates |
| `style` | No bump | Code formatting, whitespace |
| `ci` | No bump | CI/CD pipeline changes |

**Breaking changes:** Append `!` after type or add `BREAKING CHANGE:` in footer → MAJOR bump.

**Scope Values (per service):**

| Scope | Service |
|-------|---------|
| `inventory` | inventory-service |
| `orders` | order-service |
| `tasks` | task-service |
| `layout` | warehouse-layout-service |
| `product` | product-service |
| `auth` | auth-service |
| `integration` | integration-hub |
| `reporting` | reporting-service |
| `ai` | artificial-intelligence-service |
| `common` | karyo-common library |
| `frontend` | web dashboard / mobile PWA |
| `infra` | infrastructure, Kubernetes, CI/CD |
| `api` | API contract changes (any service) |
| `deps` | Dependency updates |

**Examples:**

```bash
# Feature
feat(inventory): add batch stock reservation endpoint

# Bug fix
fix(orders): correct FIFO ordering for lot-tracked items in PickingStockFinder

# Breaking API change
feat(inventory)!: change stock reservation response format to include journal entry

BREAKING CHANGE: ReserveStockResponse now includes journalEntryId field.
Clients expecting the old format must update their deserialization.

# Refactor
refactor(orders): extract state machine logic to OrderStateService

# Infrastructure
chore(infra): update Quarkus to 3.8.2 across all services

# Documentation
docs(api): update OpenAPI spec for inventory-service v1.3 endpoints

# Test
test(tasks): add integration tests for transport order confirmation flow

# Multiple scopes (use most specific)
feat(orders,inventory): implement cross-service stock reservation saga
```

**Semantic Versioning:**

Each service follows semver independently: `MAJOR.MINOR.PATCH`

| Component | Triggers MAJOR | Triggers MINOR | Triggers PATCH |
|-----------|---------------|---------------|----------------|
| REST API | Breaking endpoint change, removed field | New endpoint, new optional field | Bug fix in existing endpoint |
| Kafka events | Changed event schema (breaking), removed event | New event type, new optional field | Bug fix in event handling |
| Internal API | Breaking change to inter-service contract | New internal endpoint | Bug fix |
| Database | Breaking migration | New table, new column | Index change, data fix |

**API Module Versioned Independently:**

The `api` module of each service (containing DTOs, event schemas, client interfaces) has stricter versioning:
- Breaking changes to the API module always trigger a MAJOR bump
- API module version is the compatibility contract between services
- Example: `karyo-inventory-api:2.0.0` means breaking change from `1.x.x`

**Git Branching Strategy:**

```
master ──────────────────────────────────────────────────►
  │         ▲           ▲                  ▲
  │         │           │                  │
  ├── develop ──────────┼──────────────────┤
  │    │    ▲           │                  │
  │    │    │           │                  │
  │    ├── feature/KRY-42-batch-reserve    │
  │    │                │                  │
  │    ├── fix/KRY-57-fifo-lot-ordering    │
  │    │                                   │
  │    └── feature/KRY-63-ai-slotting ─────┘
  │
  └── release/v1.2.0 ─── (hotfixes) ──► master
```

| Branch | Purpose | Merges To | Protection |
|--------|---------|-----------|-----------|
| `master` | Production-ready code | N/A | Protected: PR required, 1+ review, all checks pass |
| `develop` | Integration branch | `master` (via release branch) | Protected: PR required, all checks pass |
| `feature/{ticket}-{desc}` | Feature development | `develop` | None |
| `fix/{ticket}-{desc}` | Bug fixes | `develop` | None |
| `release/v{X.Y.Z}` | Release preparation | `master` + `develop` | Protected during stabilization |
| `hotfix/{ticket}-{desc}` | Production emergency fix | `master` + `develop` | None |

**PR Requirements:**

- Title follows conventional commit format: `feat(inventory): add batch reservation`
- All CI checks pass (build, test, lint)
- At least 1 code review approval
- No decrease in test coverage (enforced by CI)
- API changes documented (OpenAPI spec updated if applicable)
- Breaking changes explicitly flagged in PR description

**Changelog Automation:**

```bash
# Generated from conventional commits using standard-version or release-please
## [1.3.0] - 2026-02-15

### Features
- **inventory**: add batch stock reservation endpoint (#42)
- **orders**: support priority-based wave planning (#51)

### Bug Fixes
- **orders**: correct FIFO ordering for lot-tracked items (#57)
- **tasks**: fix transport order idempotency check (#62)

### Performance
- **inventory**: optimize stock query with covering index (#59)
```

## Consequences

### Positive
- Automated version bumping — commit types directly map to semver increments, eliminating manual version decisions
- Automated changelog generation — release notes are derived from commit history, always accurate and complete
- Reviewable Git history — standardized format makes it easy to scan commits for specific types of changes
- CI enforcement — commit message validation (via commitlint or similar) prevents non-conforming messages from reaching the main branches
- API compatibility clarity — semver communicates to service consumers whether an update is safe to adopt
- Branching strategy provides clear workflow for features, fixes, and releases without ambiguity

### Negative
- Commit message overhead — developers must learn and follow the convention, which adds friction to quick commits
- Scope management — the list of scopes must be maintained and can become inconsistent if not enforced
- Squash vs. merge — squash-merging PRs requires the PR title to follow conventional commit format (losing individual commit history)
- Breaking change detection is human-dependent — a developer must correctly identify and flag breaking changes; automation can catch some but not all cases
- Independent service versioning can lead to version number divergence that is confusing (inventory-service v3.7.2, order-service v1.4.0)

### Neutral
- GitHub's auto-generated release notes can supplement (but not replace) conventional commit changelogs
- Release-please (Google) or semantic-release can automate the full workflow (version bump, changelog, Git tag, GitHub release)
- The branching strategy may be simplified to trunk-based development in the future if CI/CD maturity permits

## Alternatives Considered

### Alternative 1: Free-Form Commit Messages
- **Pros**: No learning curve, no enforcement overhead, faster commits
- **Cons**: Inconsistent history — impossible to generate changelogs automatically, difficult to understand what a release contains by scanning commits, no automated version bumping, PR reviews harder to assess scope of changes
- **Why rejected**: The short-term convenience of free-form messages is far outweighed by the long-term cost of manual changelog curation, inconsistent commit history, and inability to automate releases. For a multi-service project with multiple contributors, conventions are essential.

### Alternative 2: Calendar Versioning (CalVer)
- **Pros**: Version communicates when the release was made (e.g., `2026.02.1`), no semantic ambiguity about what MAJOR/MINOR/PATCH means, simpler versioning scheme
- **Cons**: Does not communicate compatibility — consumers cannot tell from the version whether an update is breaking. Not suitable for libraries or APIs where backward compatibility is critical. Breaks semantic expectations of tools that parse semver (dependency managers, Helm charts).
- **Why rejected**: Karyo's services expose APIs consumed by other services and external integrators. Semantic versioning communicates whether an update requires consumer changes, which is critical for managing cross-service dependencies. CalVer does not provide this information.

## Implementation Notes
- Add `commitlint` with `@commitlint/config-conventional` to the repository; configure as a Git hook via Husky or as a GitHub Action that validates PR titles
- Configure GitHub branch protection rules: require PR for `master` and `develop`, require status checks to pass, require 1+ approval
- Set up release-please (Google) or semantic-release to automate version bumping and changelog generation on merges to `master`
- Create a `.commitlintrc.yml` with the allowed scopes matching the service list
- Configure CI to validate conventional commit format on PR titles (since squash-merge uses the PR title as the commit message)
- Add a `CONTRIBUTING.md` to the repository documenting the commit conventions, branching strategy, and PR requirements
- For the initial development phase (Phase 0-1), a simpler branching model (direct to `develop`, no release branches) may be used; introduce full branching when multiple services are in active development

## Related Decisions
- [ADR-022](superseded/ADR-022-gitops-with-argocd.md): GitOps with ArgoCD — semver tags trigger image updates in the GitOps repo
- [ADR-028](ADR-028-gradle-kotlin-dsl-build-tool.md): Gradle — version derived from Git tags in the Gradle build

## References
- [Conventional Commits Specification](https://www.conventionalcommits.org/)
- [Semantic Versioning Specification](https://semver.org/)
- [commitlint Documentation](https://commitlint.js.org/)
- [release-please (Google)](https://github.com/google-github-toolkit/release-please)
- [Angular Commit Message Guidelines](https://github.com/angular/angular/blob/main/CONTRIBUTING.md#-commit-message-format) (origin of conventional commits)

## Revision History
- 2026-02-15: Initial version
