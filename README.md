# OpsPulse

> Production-ready Uptime Monitoring, Alerting & Incident Management SaaS platform.

OpsPulse is an open-source, resilient uptime monitoring and incident response solution built with modern engineering standards. It provides real-time health checks, multi-tenant workspace management, automated alert routing, and incident lifecycle tracking.

---

## Tech Stack

- **Backend:** Java 21, Spring Boot 3.4+, Spring Security (Stateless JWT + Refresh Tokens), Spring Data JPA, Flyway DB Migrations, Testcontainers.
- **Frontend:** React 19, TypeScript, Vite, Tailwind CSS, Lucide Icons.
- **Data & Cache:** PostgreSQL 17, Redis 7.
- **Infrastructure:** Docker, Docker Compose (modern specification without deprecated `version` tags).

---

## Project Structure

```
opspulse/
├── backend/            # Spring Boot REST API & modular business services
│   ├── src/main/java/com/opspulse/
│   │   ├── auth/       # Authentication, JWT, and user management
│   │   ├── common/     # Global error handling and shared utilities
│   │   └── workspace/  # Multi-tenant workspace and RBAC management
│   └── src/test/       # Integration & Testcontainers test suites
├── frontend/           # React + TypeScript single-page application
│   ├── src/            # Authentication & workspace dashboard components
│   └── public/         # Static assets and icons
├── compose.yaml        # Local PostgreSQL 17 & Redis 7 services with healthchecks
├── .env.example        # Environment variable template with safe defaults
└── .gitignore          # Production Git ignore rules
```

---

## Quick Start (Local Setup)

### Prerequisites
- **Java 21** JDK installed
- **Node.js 20+** and `npm` installed
- **Docker** and **Docker Compose** installed and running

### 1. Configure Environment
Copy the example environment file and adjust secrets for production or local development:
```bash
cp .env.example .env
```

### 2. Start Infrastructure
Launch PostgreSQL and Redis containers with persistent volumes:
```bash
docker compose up -d
```
Verify container health:
```bash
docker compose ps
```

### 3. Run Backend Service
From the `backend` directory:
```bash
cd backend
./mvnw spring-boot:run
```
*(On Windows PowerShell, use `.\mvnw.cmd spring-boot:run`)*

The backend server runs on `http://localhost:8080`. Flyway automatically executes database migrations on startup.

### 4. Run Frontend Client
From the `frontend` directory in another terminal:
```bash
cd frontend
npm install
npm run dev
```
The frontend development server starts on `http://localhost:5173`.

---

## Testing & Quality Gates

Run the backend integration test suite (powered by Testcontainers):
```bash
cd backend
./mvnw clean test
```

Run frontend linting and production build verification:
```bash
cd frontend
npm run lint
npm run build
```

---

## License
Distributed under the MIT License.
