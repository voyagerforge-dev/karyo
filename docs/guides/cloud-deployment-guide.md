# Cloud Deployment Guide -- Oracle Cloud Always Free Tier

Deploy Karyo WMS to an Oracle Cloud VM with HTTPS via Cloudflare Tunnel.
No ports are exposed to the internet. All traffic flows through the tunnel.

Target machine: VM.Standard.A1.Flex (4 OCPU, 24GB RAM, ARM64, Ubuntu 24.04 LTS) -- **Always Free**.

---

## 1. Prerequisites

- Oracle Cloud account (Always Free Tier -- no credit card charge, but card required for verification)
- Cloudflare account with a domain you control
- SSH key pair on your local machine (`ssh-keygen -t ed25519` if you don't have one)

---

## 2. Create Oracle Cloud VM

Go to **OCI Console > Compute > Instances > Create Instance**.

| Setting | Value |
|---------|-------|
| Name | `karyo-wms` (or your preference) |
| Compartment | Your root compartment (default) |
| Availability Domain | Pick any available (Free Tier works in all) |
| Image | **Ubuntu 24.04** (Canonical, aarch64) |
| Shape | **VM.Standard.A1.Flex** |
| OCPUs | **4** (Always Free allows up to 4 A1 OCPUs) |
| Memory | **24 GB** (Always Free allows up to 24GB across A1 instances) |
| Boot volume | 50GB (Always Free includes 200GB total block storage) |
| Networking | Create new VCN + subnet, or use existing. Assign public IPv4 |
| SSH keys | Upload your `~/.ssh/id_ed25519.pub` (or paste the contents) |

### Important Always Free notes

- The A1.Flex shape uses **ARM64 (aarch64)** processors -- all our Docker images build natively for ARM
- You get 4 OCPUs + 24GB RAM free **forever** (not a trial)
- 200GB block storage included (50GB boot volume leaves 150GB for additional volumes)
- 10TB/month outbound data transfer included
- If you see "Out of capacity" for A1 shapes, try a different Availability Domain or retry later (common during peak hours)

---

## 3. Connect to the VM

Oracle Cloud uses `opc` as the default SSH user on Ubuntu images:

```bash
ssh -i ~/.ssh/id_ed25519 ubuntu@<PUBLIC_IP>
```

Replace `<PUBLIC_IP>` with the public IP shown on your instance details page.

> **Tip:** If your instance shows `opc` as the username instead of `ubuntu`, use `opc`. Oracle's Ubuntu images typically use `ubuntu`.

---

## 4. Install Docker and Docker Compose

Run these commands on the VM (Ubuntu 24.04, ARM64):

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "${VERSION_CODENAME}") stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo usermod -aG docker $USER
newgrp docker
```

Verify:

```bash
docker --version
docker compose version
```

---

## 5. Install JDK 21, Node.js 22, and Python 3

The deploy script builds the application from source and validates the deployment's public URLs,
so JDK 21, Node.js 22, and Python 3 are all required. Ubuntu ships Python 3; install it explicitly
on a minimal image.

```bash
sudo apt-get install -y openjdk-21-jdk-headless python3
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt-get install -y nodejs
```

Verify:

```bash
java -version     # Should show 21.x
node --version    # Should show 22.x
python3 --version # Should show 3.x
```

> **ARM64 note:** Both OpenJDK 21 and Node.js 22 have native aarch64 packages. No emulation overhead.

---

## 6. Configure OCI Security List (Firewall)

Oracle Cloud blocks all inbound traffic by default. Since we use Cloudflare Tunnel (outbound-only), **no ingress rules are needed**. But verify SSH access works:

1. Go to **OCI Console > Networking > Virtual Cloud Networks > your VCN > Security Lists**
2. Confirm the default security list has an ingress rule for **TCP port 22** (SSH) -- this is created automatically
3. Do NOT add HTTP/HTTPS ingress rules -- Cloudflare Tunnel handles all web traffic

---

## 7. Install and Configure Cloudflare Tunnel

### Install cloudflared

```bash
# ARM64 binary for Oracle Cloud A1
curl -L --output cloudflared.deb https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64.deb
sudo dpkg -i cloudflared.deb
rm cloudflared.deb
```

### Authenticate with Cloudflare

```bash
cloudflared tunnel login
```

This prints a URL. Open it in your browser, select the domain you want to use, and authorize. A certificate is saved to `~/.cloudflared/cert.pem`.

### Create the tunnel

```bash
cloudflared tunnel create karyo-cloud
```

This creates a tunnel and saves credentials to `~/.cloudflared/<TUNNEL_ID>.json`. Note the tunnel ID printed in the output.

### Configure the tunnel

Create `/home/<your-user>/.cloudflared/config.yml`:

```yaml
tunnel: <TUNNEL_ID>
credentials-file: /home/<your-user>/.cloudflared/<TUNNEL_ID>.json

ingress:
  - hostname: cloud.karyo.example.com
    service: http://localhost:80
  - service: http_status:404
```

Replace:
- `<TUNNEL_ID>` with the ID from the create step
- `<your-user>` with your Linux username
- `cloud.karyo.example.com` with your actual domain

### Add DNS record

```bash
cloudflared tunnel route dns karyo-cloud cloud.karyo.example.com
```

Replace `cloud.karyo.example.com` with your actual domain. This creates a CNAME record in Cloudflare DNS pointing to your tunnel.

### Install as systemd service

```bash
sudo cloudflared --config /home/<your-user>/.cloudflared/config.yml service install
sudo systemctl start cloudflared
sudo systemctl enable cloudflared
```

Verify:

```bash
sudo systemctl status cloudflared
```

The tunnel starts on boot automatically.

---

## 8. Clone and Deploy

On the VM, use the HTTPS clone command displayed on this public repository's GitHub page and enter
the new repository directory. Confirm that you are at its root, then create the environment file:

```bash
test -x ./gradlew && test -f scripts/.env.prod.cloud-example
install -m 0600 scripts/.env.prod.cloud-example scripts/.env.prod
```

Edit `scripts/.env.prod` with your actual values:

```bash
nano scripts/.env.prod
```

Change these values:

- Keep `DB_USERNAME`, `POSTGRES_USER`, and `KC_DB_USERNAME` equal because the stack provisions one
  shared PostgreSQL role. Generate one strong database password and use it for `DB_PASSWORD`,
  `POSTGRES_PASSWORD`, and `KC_DB_PASSWORD`.
- Supply a unique one-time `KC_BOOTSTRAP_ADMIN_USERNAME` and
  `KC_BOOTSTRAP_ADMIN_PASSWORD` for this fresh database. Do not derive them from a permanent
  identity, and do not reuse them after bootstrap retirement. After PostgreSQL starts, the deploy
  script inspects the Keycloak schema and refuses to start Keycloak without both values whenever
  the `master` realm is not initialized, including after an interrupted first deployment or
  `--reset-db`. Remove both lines from `scripts/.env.prod` once the initialized database's
  bootstrap administrator is retired.
- Generate an independent `KEYCLOAK_ADMIN_CLIENT_SECRET` of at least 32 characters for Karyo's
  permanent, least-privilege `karyo-admin` user-management service identity.
- Generate an independent `OIDC_SECRET` of at least 32 characters for the separate
  `karyo-backend` authentication-audit identity.
- Set `KARYO_PUBLIC_ORIGIN` to the canonical HTTPS browser origin, for example
  `https://cloud.karyo.yourdomain.com`.
- Set `KARYO_DOMAIN` to that origin's hostname, and set both `KEYCLOAK_URL` and `KC_HOSTNAME` to
  the same origin with `/auth` appended.

Run the deploy script:

```bash
./scripts/deploy-server.sh
```

The script runs 8 stages: environment check, Gradle build, frontend build, Docker image build, compose up, health checks, status report, and summary. On a 4 OCPU ARM machine, the first build takes about 5-10 minutes.

---

## 9. Provision the First Administrator and Verify

A fresh production realm contains no predefined human application users or default application
credentials. Keycloak
creates the externally supplied bootstrap account only in its `master` realm on a fresh database.
Use it once to provision the first Karyo administrator:

1. Open `https://<your-domain>/auth/admin/` and sign in to the **master** realm with
   `KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD`.
2. Select the **karyo** realm, open **Users**, and create the first application administrator.
3. Set `client_id=0`, `principal_kind=ops`, and `tenant_code=SYS`. Set `warehouse_id`, such as
   `WH-001`, only when the deployment uses a warehouse identity.
4. Set an independently generated temporary password under **Credentials** and require an update
   at first sign-in. Deliver it through a secure channel.
5. Assign the `ADMIN` realm role under **Role mapping**.
6. In a separate browser session, visit `https://<your-domain>/` and complete the first sign-in and
   mandatory password change.
7. Return to the admin console's **master** realm and delete the temporary bootstrap user. Verify
   that its one-time credentials no longer authenticate, then delete the
   `KC_BOOTSTRAP_ADMIN_USERNAME` and `KC_BOOTSTRAP_ADMIN_PASSWORD` lines from
   `scripts/.env.prod`. Immediately refresh the rendered service environments and verify neither
   the source nor rendered Keycloak file retains either assignment:

   ```bash
   python3 scripts/render_compose_env.py scripts/.env.prod >/dev/null
   if grep -q '^KC_BOOTSTRAP_ADMIN_' scripts/.env.prod scripts/.env.prod.keycloak; then
     echo 'bootstrap credentials remain on disk' >&2
     exit 1
   fi
   ```

   Later deploys, including `--quick`, no longer require them. Until those lines are deleted, every
   successful deploy still prints the bootstrap first-login instructions; after removal, sign in
   with the existing application administrator.
8. On Karyo's **Users** page, create a test user with username, email, first and last name,
   password, realm roles, optional warehouse ID, and an active goods owner such as `ACME`. Set its
   tenant authority to **Goods owner**, sign in as that user, and verify its work is attributed to
   the selected owner. Then deactivate and reactivate it. This verifies the permanent `karyo-admin`
   service identity after bootstrap retirement.
9. Confirm that `karyo-admin` cannot read realm events, manage clients, manage the realm, or
   administer the master realm. The separate `karyo-backend` identity retains only its audit and
   user-read access.
10. Check that `/api/v1/stock-units`, `/api/v1/products`, `/api/v1/locations`, and
    `/api/v1/users` respond through nginx.

`karyo-admin` has only the minimum `manage-users` and `view-realm` roles in the `karyo` realm. It
is not a broad realm, client, or master-realm administrator. Changing bootstrap environment
variables does not reset an account in an existing Keycloak database. Every fresh or `--reset-db`
database requires newly supplied one-time bootstrap credentials, provisioning, verification, and
mandatory bootstrap retirement. Never recreate a broad bootstrap administrator from permanent
credentials. See [DEPLOY.md](../../DEPLOY.md#provision-the-first-application-administrator) for the
complete lifecycle.

### Upgrading a Deployment That Already Has a Keycloak Database

The steps above apply to a fresh database. An existing Keycloak database still carries whatever
identities and callbacks it was created with, and Keycloak skips realm import once the realm
exists, so it needs the maintenance migration in
[DEPLOY.md](../../DEPLOY.md#upgrade-an-existing-keycloak-database) instead. Three things about
that procedure matter operationally:

- **Back up first, and prove the backup restores.** The migration rotates the `karyo-backend`
  client secret and retires accounts. Taking the dump after the migration has started is too
  late; the ordering is backup, then migrate, then redeploy.
- **Use the full deployment, not `--quick`.** The application switches to the `karyo-admin`
  client-credentials flow, so the images have to be rebuilt.
- **Verify credentials after rollout.** Sign in through the browser, exercise user creation and
  password reset, and confirm login auditing still records events. A documented rollback path is
  in [DEPLOY.md](../../DEPLOY.md#roll-back-a-failed-realm-migration); keep the dump until that
  verification passes.

---

## 10. Maintenance Commands

All repository commands assume you are at the root of the public clone on the VM.

**View logs:**

```bash
docker compose -f infrastructure/docker/docker-compose.prod.yml logs -f
docker compose -f infrastructure/docker/docker-compose.prod.yml logs -f karyo-app
```

**Restart the stack:**

```bash
docker compose -f infrastructure/docker/docker-compose.prod.yml restart
```

**Deploy a newer public release:** the first release has one root commit; each later release
adds one generated commit and matching tag without rewriting earlier history. Check out the
selected release tag in a fresh clone of the public repository, copy the protected
`scripts/.env.prod` file into that clone, then run `./scripts/deploy-server.sh` there. The source
tree is regenerated at each release, so keep deployment configuration out of tracked files.

**Stop the stack (preserves data):**

```bash
docker compose -f infrastructure/docker/docker-compose.prod.yml down
```

**Stop and delete all data (destroys database):**

```bash
docker compose -f infrastructure/docker/docker-compose.prod.yml down -v
```

**Check tunnel status:**

```bash
sudo systemctl status cloudflared
```

**Restart tunnel:**

```bash
sudo systemctl restart cloudflared
```

---

## 11. Cost Estimate

| Resource | Monthly Cost |
|----------|-------------|
| VM.Standard.A1.Flex (4 OCPU, 24GB) | **Free** (Always Free Tier) |
| 50GB boot volume | **Free** (within 200GB Always Free block storage) |
| Cloudflare Tunnel | **Free** |
| 10TB/month outbound | **Free** (Always Free includes this) |
| **Total** | **$0/month** |

Oracle's Always Free Tier is permanent -- not a trial. The A1 ARM instances remain free as long as your account is active and in good standing. The only cost is your domain name (~$10-15/year).

---

## Troubleshooting

**"Out of capacity" when creating A1 instance:**
A1 Always Free instances are popular. Try: (1) different Availability Domain, (2) different region, (3) retry during off-peak hours (early morning UTC). You can also create a smaller instance first (2 OCPU/12GB) and resize later.

**Deploy script fails at health check:**
Services may need more time on first start (Keycloak imports realm, PostgreSQL initializes schemas). Re-run `./scripts/deploy-server.sh` -- it is idempotent.

**Cannot reach site through tunnel:**
Check that cloudflared is running (`sudo systemctl status cloudflared`) and that the hostname in `/home/<your-user>/.cloudflared/config.yml` matches your DNS record.

**Out of disk space:**
Docker images and build cache accumulate. Clean up with:

```bash
docker system prune -a
```

**Keycloak shows "invalid redirect URI":**
`KARYO_PUBLIC_ORIGIN` must be the canonical tunnel origin, `KARYO_DOMAIN` must be its hostname, and
`KC_HOSTNAME` must be that origin with `/auth` appended. The production realm permits only four
exact `karyo-web` callbacks beneath that origin: `/`, `/m/`, `/m/silent-check-sso.html`, and
`/silent-check-sso.html`. Keycloak imports these callbacks only when the realm is first created.
For an existing realm, use the supported migration in
[DEPLOY.md](../../DEPLOY.md#upgrade-an-existing-keycloak-database) to bind those exact callbacks and
web origin, or redeploy with `--reset-db` after backing up any data you need.

**ARM64 image compatibility:**
All standard Docker images (PostgreSQL, Keycloak, nginx, Node, OpenJDK) have ARM64 variants. If a custom image fails, check its Dockerfile uses multi-arch base images.
