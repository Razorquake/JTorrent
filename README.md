# JTorrent

JTorrent is a Java-based torrent manager built around a Spring Boot backend, `jlibtorrent`, and two clients:

- a lightweight browser-based live monitor served by the backend
- a standalone terminal UI in the `jtorrent-tui` module

The server exposes REST endpoints for torrent lifecycle management, file-level prioritization, statistics, storage inspection, and real-time updates over WebSocket/STOMP.

## What It Does

- Add torrents from magnet links
- Upload and manage `.torrent` files
- Start, pause, remove, recheck, and reannounce torrents
- View torrent-level statistics, transfer totals, ETA, and share ratios
- Manage files inside a torrent: skip, resume, prioritize, deprioritize, or reset priorities
- Search and filter torrents by status, name, progress, size, and date
- Monitor disk usage and clean up orphaned files in the downloads directory
- Stream live torrent and statistics updates to connected clients
- Use a full-screen terminal UI for day-to-day operation

## Project Layout

```text
.
|-- src/main/java/...         Spring Boot torrent server
|-- src/main/resources/       App config + static web monitor
|-- jtorrent-tui/             Standalone terminal UI client
|-- downloads/                Default download directory
|-- build.gradle.kts          Root server build
`-- settings.gradle.kts       Multi-project Gradle settings
```

## Tech Stack

- Java 17
- Spring Boot 4
- Spring Web MVC, Validation, WebSocket, Security, Data JPA
- PostgreSQL for persistence
- `com.frostwire:jlibtorrent` for torrent session management
- Springdoc OpenAPI / Swagger UI
- TamboUI for the terminal client

## Requirements

Before running the project locally, make sure you have:

- Java 17 installed
- PostgreSQL running locally on port `5432`
- a database named `torrentdb`

Important notes:

- The root build currently includes `jlibtorrent-windows`, so the backend is configured for Windows development.
- `spring.jpa.hibernate.ddl-auto` is set to `create`, which recreates the schema on startup. That is convenient for local development, but it is not production-safe.
- Security is currently open for development: all HTTP requests are permitted in `SecurityConfig`.

## Configuration

Default server configuration lives in [`application.yaml`](/C:/Users/anant/IdeaProjects/JTorrent/src/main/resources/application.yaml):

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/torrentdb
    username: ${DATABASE_USER:postgres}
    password: ${DATABASE_PASSWORD:}

torrent:
  downloads-path: ./downloads
  max-concurrent-downloads: 5
  peer-connection-port-min: 6881
  peer-connection-port-max: 6889
```

You can override the database credentials with environment variables:

```powershell
$env:DATABASE_USER="postgres"
$env:DATABASE_PASSWORD="your-password"
```

## Running the Server

From the project root:

```powershell
.\gradlew.bat bootRun
```

Or on macOS/Linux:

```bash
./gradlew bootRun
```

Once the server starts:

- REST API base URL: `http://localhost:8080/api`
- WebSocket endpoint: `http://localhost:8080/ws` using SockJS/STOMP
- Web monitor: `http://localhost:8080/`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`

## Running the TUI Client

The terminal UI is packaged as a standalone fat JAR.

Run it from a real terminal. Do not launch it with `gradlew run`, because the Gradle daemon does not provide a proper TTY for the full-screen interface.

Build it:

```powershell
.\gradlew.bat :jtorrent-tui:shadowJar
```

Run it:

```powershell
java -jar jtorrent-tui\build\libs\jtorrent-tui-all.jar
```

To connect to a different server:

```powershell
java -jar jtorrent-tui\build\libs\jtorrent-tui-all.jar --server http://your-host:8080
```

Common TUI shortcuts:

- `a` add a torrent
- `Enter` open details
- `p` pause
- `r` resume
- `d` delete
- `c` recheck
- `t` reannounce
- `o` open operations/health view
- `/` filter torrents
- `?` open help
- `q` quit

## API Highlights

Core endpoints:

- `POST /api/torrents` add a torrent from a magnet link
- `POST /api/torrents/upload` upload a `.torrent` file
- `GET /api/torrents` list torrents
- `GET /api/torrents/{id}` fetch one torrent
- `POST /api/torrents/{id}/start` resume a torrent
- `POST /api/torrents/{id}/pause` pause a torrent
- `POST /api/torrents/{id}/recheck` force piece verification
- `POST /api/torrents/{id}/reannounce` reannounce to trackers
- `DELETE /api/torrents/{id}?deleteFiles=true|false` remove a torrent
- `GET /api/torrents/{torrentId}/files` list files inside a torrent
- `PUT /api/torrents/{torrentId}/files/priorities` update file priorities
- `POST /api/torrents/search` advanced filtering, sorting, and pagination
- `GET /api/stats/overall` overall torrent statistics
- `GET /api/files/storage` disk usage for the downloads directory
- `GET /api/files/orphans` scan for orphaned files
- `DELETE /api/files/orphans` clean orphaned files
- `GET /api/system/health` health check

Example: add a torrent via magnet link

```bash
curl -X POST http://localhost:8080/api/torrents \
  -H "Content-Type: application/json" \
  -d '{
    "magnetLink": "magnet:?xt=urn:btih:YOUR_INFO_HASH",
    "savePath": "./downloads",
    "startImmediately": true
  }'
```

Example: search torrents

```bash
curl -X POST http://localhost:8080/api/torrents/search \
  -H "Content-Type: application/json" \
  -d '{
    "statuses": ["DOWNLOADING", "SEEDING"],
    "name": "ubuntu",
    "sortBy": "addedDate",
    "sortDirection": "DESC",
    "page": 0,
    "size": 20
  }'
```

## Real-Time Updates

The server publishes WebSocket updates on a schedule:

- `/topic/torrents` full torrent list updates
- `/topic/torrents/{id}` focused updates for a specific torrent
- `/topic/stats` aggregated statistics
- `/topic/notifications` add/remove/completion notifications

The bundled web page at [`index.html`](/C:/Users/anant/IdeaProjects/JTorrent/src/main/resources/static/index.html) is a simple monitor that subscribes to those topics and renders live status information in the browser.

## Testing

Run the test suite from the project root:

```powershell
.\gradlew.bat test
```

Tests use H2 in PostgreSQL compatibility mode via [`application-test.yaml`](/C:/Users/anant/IdeaProjects/JTorrent/src/test/resources/application-test.yaml).

## Development Notes

- The backend manages a `SessionManager` from `jlibtorrent` and keeps active handles in memory.
- The default downloads directory is [`downloads`](/C:/Users/anant/IdeaProjects/JTorrent/downloads).
- The TUI talks to the backend over HTTP and also uses live updates for a more responsive experience.
- There are crash logs (`hs_err_pid*.log`) and sample downloaded content in the repo right now; you may want to decide later whether those should remain versioned or be ignored.

## Current Status

This repository already has good automated test coverage for controllers, services, repositories, DTOs, mapper logic, and exception handling. On this machine, `.\gradlew.bat test` completed successfully on March 31, 2026.
