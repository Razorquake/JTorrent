rootProject.name = "JTorrent"

// New standalone TUI client module
include("jtorrent-tui")

// The root project (:) is the Spring Boot server that already lives in rootDir.
// Only the TUI module is a child project with its own directory.
project(":jtorrent-tui").projectDir = file("jtorrent-tui")
