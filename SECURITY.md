# Security policy

Karyo is deployed inside warehouses, where an inventory or access defect has physical
consequences. Security reports are treated as a priority over feature work.

## Reporting a vulnerability

Report a suspected vulnerability privately by email to the project captain,
[yash@karyowms.com](mailto:yash@karyowms.com). Do not open a public issue, and do not describe
the problem in a public discussion before it is fixed.

A report is most actionable when it states the affected version, how the stack was deployed, the
privilege the attacker starts from (unauthenticated, an authenticated `VIEWER`, an operator on
the floor device, a goods owner in a multi-owner instance), the steps that reproduce the
behaviour, and the impact you believe follows from it. Remove real credentials, licence tokens,
and customer data from anything you attach.

You will receive an acknowledgement that the report was read, an assessment of whether it is
reproducible, and notice when a fix is published. We will credit you in the release notes unless
you ask us not to.

## Scope

In scope: the Karyo application and its API, the desktop and floor interfaces, the deployment
material in this repository, and the supplied Keycloak realm and reverse-proxy configuration.

Out of scope: findings that require access already equivalent to the one being demonstrated,
issues in third-party dependencies with no exploitable path through Karyo (report those
upstream), and findings against a deployment operated by someone other than you without their
permission. There is no bug bounty.

## Supported versions

Security fixes are published against the most recent public release. Older releases are not
patched; upgrading is the supported remediation.

## Deploying Karyo safely

The deployment path in [DEPLOY.md](DEPLOY.md) is the supported one, and the checks it describes
are load-bearing rather than advisory. The production Keycloak realm ships with no human users
and no fixed credentials; the first administrator is provisioned from externally supplied
one-time bootstrap credentials that must then be retired. A deployment must set
`KARYO_PUBLIC_ORIGIN` to its real, credential-free origin, must not relax the realm's exact
callback URLs into wildcards, and must terminate TLS in front of the stack.
