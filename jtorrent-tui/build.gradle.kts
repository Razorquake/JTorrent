// jtorrent-tui/build.gradle.kts
//
// Standalone TUI client for JTorrent.
// Communicates with the jtorrent-server REST API over HTTP.
//
// Build:   ./gradlew :jtorrent-tui:shadowJar
// Run:     java -jar jtorrent-tui/build/libs/jtorrent-tui-all.jar
//
// NOTE: Never run via `./gradlew run` — Gradle daemons have no real TTY.
//       Always run the fat JAR from your terminal directly.
plugins {
    id("java")
    // Shadow plugin produces a self-contained fat JAR with all dependencies bundled.
    // This is required for TamboUI apps because they must be launched from a real
    // terminal (not via the Gradle daemon which has no TTY).
    id("com.gradleup.shadow") version "9.4.0"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()

    // TamboUI 0.2.0-SNAPSHOT lives on the OSSRH snapshots repository.
    // Only snapshot artifacts are fetched from here; everything else
    // continues to resolve from Maven Central.
    maven {
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent {
            snapshotsOnly()
        }
    }
}

dependencies {
    // ── TamboUI ──────────────────────────────────────────────────────────────
    // BOM pins all tamboui-* modules to the same snapshot version so we don't
    // have to repeat "0.2.0-SNAPSHOT" on every individual dependency.
    implementation(platform("dev.tamboui:tamboui-bom:0.2.0-SNAPSHOT"))

    // Toolkit DSL — the declarative, high-level API (ToolkitApp + Toolkit.*).
    // This is the recommended API level for full-screen applications.
    implementation("dev.tamboui:tamboui-toolkit")

    // JLine 3 backend — most portable backend; works on Java 17, Linux, macOS,
    // and Windows Terminal. The Panama backend requires Java 22+ FFM APIs.
    implementation("dev.tamboui:tamboui-jline3-backend")

    // ── JSON parsing ─────────────────────────────────────────────────────────
    // Jackson for deserializing the REST API responses.
    // We use only jackson-databind (which transitively pulls in core + annotations).
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    // JavaTimeModule for LocalDateTime support in TorrentResponse
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.3")

    // ── HTTP client ───────────────────────────────────────────────────────────
    // java.net.http.HttpClient is built into Java 11+ — no extra dependency needed.

    // ── Logging ───────────────────────────────────────────────────────────────
    // SLF4J + Logback for structured logging (consistent with the server module).
    implementation("ch.qos.logback:logback-classic:1.5.13")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ── Shadow / fat-JAR configuration ───────────────────────────────────────────
tasks.shadowJar {
    archiveClassifier.set("")          // no "-all" suffix in the classifier
    archiveBaseName.set("jtorrent-tui-all")
    mergeServiceFiles()                // required for SLF4J ServiceLoader entries

    manifest {
        attributes["Main-Class"] = "com.example.jtorrent.tui.JTorrentApp"
    }
}

// Make `./gradlew :jtorrent-tui:build` also produce the fat JAR
tasks.named("build") {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}
