# MediBook Backend (Spring Boot 3.3.5)

The server application providing REST APIs, Spring Security authentication, Spring Data JPA persistence, and database transaction boundaries.

---

## Quick Start

1. Ensure MySQL is running on port 3306 (`MySQL80` service).
2. Create and configure `set-env.bat` (from `set-env.example.bat`).
3. Run the backend:
```bat
run-backend.bat
```
Server starts on **`http://localhost:8081`**.

---

## Health Check

```
GET http://localhost:8081/api/health
```
Returns:
```json
{
  "status": "UP",
  "application": "MediBook",
  "port": 8081,
  "timestamp": "2026-10-14T16:39:42Z",
  "database": "UP"
}
```

---

## Testing

```bat
mvn test
```
Executes all 34 automated unit and integration tests using an in-memory H2 database (MySQL is not required during tests).
