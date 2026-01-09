package com.example.jtorrent.model;

public enum TorrentStatus {
    PENDING,        // Added but not started
    DOWNLOADING,    // Currently downloading
    PAUSED,         // Download paused by user
    COMPLETED,      // Download finished
    SEEDING,        // Completed and seeding
    STOPPED,        // Stopped by user
    ERROR,          // Error occurred
    CHECKING        // Checking existing files
}
