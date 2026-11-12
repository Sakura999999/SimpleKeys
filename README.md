<div align="center">

# SimpleCard · 简易发卡

**Automated Digital Goods Delivery Platform**

[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-22-orange?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-brightgreen?logo=springboot)
![Next.js](https://img.shields.io/badge/Next.js-16-black?logo=next.js)
![React](https://img.shields.io/badge/React-19-61dafb?logo=react)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18+-336791?logo=postgresql&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-5.7-3178c6?logo=typescript&logoColor=white)
![Tailwind CSS](https://img.shields.io/badge/Tailwind%20CSS-3.4-38bdf8?logo=tailwindcss&logoColor=white)
![pnpm](https://img.shields.io/badge/pnpm-9+-f69220?logo=pnpm&logoColor=white)

English | [简体中文](README.zh.md)

</div>

---

SimpleCard is a full-stack, self-hosted platform for selling digital goods (card keys, activation codes, accounts). A buyer places an order, pays via an integrated channel, and receives the keys automatically — no manual intervention. It ships with a production-grade payment pipeline (Epay aggregator + self-hosted USDT gateway with on-chain verification), a risk-control layer, and a full admin panel.

## Screenshots

| Storefront (Light) | Storefront (Dark) |
|:---:|:---:|
| ![Home Light](.github/assets/home-light.png) | ![Home Dark](.github/assets/home-dark.png) |

| Admin Panel (Light) | Admin Panel (Dark) |
|:---:|:---:|
| ![Admin Light](.github/assets/admin-light.png) | ![Admin Dark](.github/assets/admin-dark.png) |

---

## Features

|  |  |
|---|---|
| 🛒 **Auto Delivery** — Keys are issued automatically once payment confirms | 🎨 **Theming** — Light/dark mode with multiple accent colors |
| 📦 **Product Management** — Categories, specs, stock pools, bulk key import | 🔒 **Security** — Stateless JWT auth + BCrypt password hashing |
| 💳 **Multi-Payment** — Extensible channel architecture (WeChat/Alipay/USDT) | 🛡️ **Risk Control** — IP rate limiting, device fingerprinting, Turnstile, anti-fraud |
| 📊 **Admin Dashboard** — Sales metrics, orders, users, site config in one place | 🔍 **Order Tracking** — Guests and members can retrieve keys by order no. or email |
| 🛍️ **Shopping Cart** — Multi-item checkout in a single order | ⚙️ **Site Config** — Announcements, popups, maintenance mode toggles |

---

## Integrated Payment Channels

| Channel       | Integration         | Notes                                              |
|---------------|---------------------|----------------------------------------------------|
| Alipay        | Epay Aggregator     | Via third-party Epay payment platform              |
| WeChat Pay    | Epay Aggregator     | Via third-party Epay payment platform              |
| Alipay        | Native (planned)    | Requires merchant qualification                    |
| WeChat Pay    | Native (planned)    | Requires merchant qualification                    |
| USDT (TRC-20) | BEpusdt Self-hosted | On-chain auto-confirmation, no third-party custody |
| USDT (BEP-20) | BEpusdt Self-hosted | On-chain auto-confirmation, no third-party custody |

> The payment layer is extensible — channels are configured and switched in the admin panel, no code changes needed.

---

## Tech Stack

| Layer | Technologies |
|-------|-------------|
| **Frontend** | Next.js 16 · React 19 · TypeScript · Tailwind CSS 3 · shadcn/ui |
| **Backend** | Spring Boot 3.4 · Java 22 · Spring Data JPA · Spring Security |
| **Database** | PostgreSQL 18+ |
| **Auth** | JWT (jjwt) · BCrypt |
| **Build** | pnpm (frontend) · Maven (backend) |

### Monorepo Structure

> pnpm workspaces monorepo — frontend and backend managed together.

```
simplecard/
├── apps/
│   ├── web/                          # Next.js frontend
│   │   ├── app/
│   │   │   ├── (store)/              # Storefront routes (home, product, cart, order, payment…)
│   │   │   └── admin/                # Admin panel routes (dashboard, products, keys, orders…)
│   │   ├── features/                 # Business feature modules
│   │   ├── services/                 # API client layer (unified backend calls)
│   │   ├── hooks/                    # Custom React hooks
│   │   ├── components/               # Shared UI components (shadcn/ui)
│   │   ├── types/                    # TypeScript type definitions
│   │   └── next.config.mjs           # Next.js config (includes API proxy rewrites)
│   │
│   └── api/                          # Spring Boot backend
│       └── src/main/
│           ├── java/com/simplecard/
│           │   ├── controller/       # REST controllers (storefront + admin)
│           │   ├── entity/           # JPA entities (16 tables)
│           │   ├── repository/       # Data access layer
│           │   ├── service/          # Business logic layer
│           │   ├── config/           # Security, JWT, CORS config
│           │   └── model/            # DTOs / VOs
│           └── resources/
│               ├── application.yml   # App config (DB, JWT, mail, uploads, etc.)
│               └── data.sql          # Seed data (admin account, site config, currencies)
│
├── docker-compose.yml                # Docker Compose orchestration (production / local)
├── .env.example                      # Environment variable template
└── pnpm-workspace.yaml               # Monorepo workspace declaration
```

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java | 22+ | Backend runtime |
| Maven | 3.9+ | Backend build tool |
| Node.js | 20+ | Frontend runtime |
| pnpm | 9+ | Frontend package manager (`npm i -g pnpm`) |
| PostgreSQL | 18+ | Database — create a database and user before starting |

---

## Configuration

Main config file: `apps/api/src/main/resources/application.yml`

Every setting supports **environment variable override** (`${ENV_VAR:default}`). Edit the yml directly for local dev; inject env vars in production.

### Database

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/simplecard}
    username: ${DB_USERNAME:simplecard}
    password: ${DB_PASSWORD:your_password}
```

Tables are auto-created on first startup (`ddl-auto: update`). Run the seed SQL once to insert the admin account, site config, and demo data:

```bash
psql -U simplecard -d simplecard -f apps/api/src/main/resources/data.sql
```

> The SQL uses `WHERE NOT EXISTS` guards — safe to run multiple times.

### JWT Authentication

```yaml
jwt:
  secret: ${JWT_SECRET:<generate with: openssl rand -base64 48>}
  expiration: 86400000  # 24 hours
```

For production, you **must** inject a random secret via `JWT_SECRET` (at least 256 bits):

```bash
openssl rand -base64 48
```

### Password Encryption Mode

```yaml
security:
  password-plain: ${PASSWORD_PLAIN:true}  # true=plaintext (dev), false=BCrypt (production)
```

- **Local dev**: `true` (default) — plaintext passwords for easy debugging
- **Production**: set to `false` for BCrypt — **reset all user passwords before switching**

### Email

```yaml
spring:
  mail:
    host: ${MAIL_HOST:smtp.example.com}
    port: ${MAIL_PORT:465}
    username: ${MAIL_USERNAME:your@email.com}
    password: ${MAIL_PASSWORD:your_password}

mail:
  enabled: ${MAIL_ENABLED:true}       # Master switch — set false to disable all emails
  site-url: ${MAIL_SITE_URL:https://your-domain.com}
```

### File Uploads

```yaml
upload:
  path: ${UPLOAD_PATH:./uploads}                # File storage path
  url-prefix: ${UPLOAD_URL_PREFIX:/api/uploads}  # Access URL prefix
```

---

## Deployment

> This section covers the minimal startup path. A full production setup additionally involves server bootstrap, Nginx/HTTPS, CI/CD, and BEpusdt wallet configuration.

### Option A: Docker (Recommended)

The repo ships a `docker-compose.yml` orchestrating **api / web / bepusdt** containers. **PostgreSQL and Nginx are not included** — bring your own.

Images are published to GHCR; the `:latest` tag tracks the latest release and can be pulled anonymously:

- `ghcr.io/runtimepoet/simplecard-api:latest`
- `ghcr.io/runtimepoet/simplecard-web:latest`

```bash
# 1. Prepare .env (see "Configuration" above for variable meanings)
cp .env.example .env

# 2. Pull images and start
docker compose pull
docker compose up -d

# 3. Tail logs
docker compose logs -f
```

> 💡 **For production**, pin a specific version (e.g. `:v1.0.0`) by overriding `API_IMAGE` / `WEB_IMAGE` in `.env` — easier rollback and multi-host consistency.

> Uploaded files persist via the `./uploads` volume mount and survive container rebuilds. Put an Nginx reverse proxy in front for HTTPS and static assets.

### Option B: Non-Docker (Direct Run)

For local development or single-host setups. Requires Java 22 / Maven 3.9+ / Node.js 20+ / pnpm 9+ / PostgreSQL 18+.

> ⚠️ **Timezone notice**: Docker deployments inject `TZ=Asia/Shanghai` via compose. For non-Docker setups you must configure the process timezone yourself — otherwise order timestamps, expiry checks, and on-chain verification will be off by 8 hours. Pick either:
>
> ```bash
> # A: change system timezone (affects all processes)
> sudo timedatectl set-timezone Asia/Shanghai
>
> # B: inject TZ per process
> export TZ=Asia/Shanghai
> ```

```bash
# Backend (port 8083)
cd apps/api
mvn spring-boot:run

# Frontend (port 3000, in a new terminal)
cd apps/web
pnpm install
pnpm dev
```

Or start the frontend from the monorepo root:

```bash
pnpm install
pnpm dev:web
```

> **API Proxy**: `next.config.mjs` rewrites `/api/*` to `http://localhost:8083` automatically — no CORS setup needed.

### Verify

- Health check: `GET http://localhost:8083/api/health`

---

## License

[MIT](LICENSE) © 2026 runtimepoet
