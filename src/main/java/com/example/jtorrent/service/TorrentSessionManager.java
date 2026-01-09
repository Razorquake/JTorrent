package com.example.jtorrent.service;

import com.example.jtorrent.config.TorrentProperties;
import com.frostwire.jlibtorrent.*;
import com.frostwire.jlibtorrent.alerts.*;
import com.frostwire.jlibtorrent.swig.settings_pack;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Core torrent session manager that wraps jlibtorrent's SessionManager.
 * This is the central component that manages all torrent operations, DHT, and peer connections.
 *
 * Key responsibilities:
 * - Initialize and manage the libtorrent session
 * - Handle alerts from the torrent engine
 * - Track active torrents and their handles
 * - Provide thread-safe access to torrent operations
 */
@Component
@Slf4j
public class TorrentSessionManager {

    private final TorrentProperties properties;
    private SessionManager sessionManager;
    private final Map<String, TorrentHandle> activeTorrents = new ConcurrentHashMap<>();
    private final CountDownLatch sessionStartedLatch = new CountDownLatch(1);
    private volatile boolean isRunning = false;

    public TorrentSessionManager(TorrentProperties properties) {
        this.properties = properties;
    }

    /**
     * Initialize the torrent session with configured settings.
     * This method is called automatically after bean construction.
     */
    @PostConstruct
    public void initialize() {
        log.info("Initializing TorrentSessionManager...");

        try {
            // Create session manager instance
            sessionManager = new SessionManager();

            // Configure session settings
            configureSession();

            // Register alert listener for handling torrent events
            registerAlertListener();

            // Start the session
            sessionManager.start();

            // Start DHT for magnet link resolution and peer discovery
            sessionManager.startDht();

            isRunning = true;
            sessionStartedLatch.countDown();

            log.info("TorrentSessionManager initialized successfully");
            log.info("Download path: {}", properties.getDownloadsPath());
            log.info("Max concurrent downloads: {}", properties.getMaxConcurrentDownloads());
            log.info("Peer connection port range: {}-{}",
                    properties.getPeerConnectionPortMin(),
                    properties.getPeerConnectionPortMax());

        } catch (Exception e) {
            log.error("Failed to initialize TorrentSessionManager", e);
            throw new RuntimeException("Failed to initialize torrent session", e);
        }
    }

    /**
     * Configure session settings including ports, limits, and performance tuning.
     */
    private void configureSession() {
        SettingsPack settingsPack = new SettingsPack();

        // Set listening port range for incoming connections
        settingsPack.setString(settings_pack.string_types.listen_interfaces.swigValue(),
                "0.0.0.0:" + properties.getPeerConnectionPortMin());

        // Set connection limits
        settingsPack.setInteger(settings_pack.int_types.connections_limit.swigValue(), 200);
        settingsPack.setInteger(settings_pack.int_types.active_downloads.swigValue(),
                properties.getMaxConcurrentDownloads());
        settingsPack.setInteger(settings_pack.int_types.active_seeds.swigValue(), 5);

        // Enable DHT, Local Peer Discovery, and UPnP
        settingsPack.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true);
        settingsPack.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true);
        settingsPack.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true);
        settingsPack.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true);

        // Set DHT bootstrap nodes (for initial peer discovery)
        settingsPack.setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(),
                "dht.libtorrent.org:25401," +
                        "router.bittorrent.com:6881," +
                        "router.utorrent.com:6881," +
                        "dht.transmissionbt.com:6881");

        // Performance tuning
        settingsPack.setInteger(settings_pack.int_types.alert_queue_size.swigValue(), 10000);
        settingsPack.setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true);
        settingsPack.setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true);

        // Apply settings
        sessionManager.applySettings(settingsPack);

        log.info("Session settings configured");
    }

    /**
     * Register an alert listener to handle events from the torrent engine.
     * Alerts include: torrent added, download progress, completion, errors, etc.
     */
    private void registerAlertListener() {
        sessionManager.addListener(new AlertListener() {
            @Override
            public int[] types() {
                // Return null to listen to all alert types
                // We could filter specific types for performance, but we want comprehensive monitoring
                return null;
            }

            @Override
            public void alert(Alert<?> alert) {
                handleAlert(alert);
            }
        });

        log.info("Alert listener registered");
    }

    /**
     * Handle alerts from the torrent engine.
     * This is where we process events and update our internal state.
     */
    private void handleAlert(Alert<?> alert) {
        AlertType type = alert.type();

        try {
            switch (type) {
                case ADD_TORRENT:
                    handleAddTorrentAlert((AddTorrentAlert) alert);
                    break;

                case TORRENT_REMOVED:
                    handleTorrentRemovedAlert((TorrentRemovedAlert) alert);
                    break;

                case TORRENT_FINISHED:
                    handleTorrentFinishedAlert((TorrentFinishedAlert) alert);
                    break;

                case TORRENT_ERROR:
                    handleTorrentErrorAlert((TorrentErrorAlert) alert);
                    break;

                case METADATA_RECEIVED:
                    handleMetadataReceivedAlert((MetadataReceivedAlert) alert);
                    break;

                case STATE_UPDATE:
                    handleStateUpdateAlert((StateUpdateAlert) alert);
                    break;

                case TORRENT_PAUSED:
                    log.debug("Torrent paused: {}", ((TorrentAlert<?>) alert).torrentName());
                    break;

                case TORRENT_RESUMED:
                    log.debug("Torrent resumed: {}", ((TorrentAlert<?>) alert).torrentName());
                    break;

                case DHT_STATS:
                    // DHT statistics - useful for debugging peer discovery
                    break;

                default:
                    // Log other alerts at trace level for debugging
                    log.trace("Alert received: {} - {}", type, alert.message());
                    break;
            }
        } catch (Exception e) {
            log.error("Error handling alert: {} - {}", type, alert.message(), e);
        }
    }

    private void handleAddTorrentAlert(AddTorrentAlert alert) {
        TorrentHandle handle = alert.handle();
        if (handle != null && handle.isValid()) {
            String infoHash = handle.infoHash().toString();
            activeTorrents.put(infoHash, handle);
            log.info("Torrent added successfully: {} ({})", alert.torrentName(), infoHash);

            // Auto-resume if not already downloading
            if (!handle.status().isFinished()) {
                handle.resume();
            }
        }
    }

    private void handleTorrentRemovedAlert(TorrentRemovedAlert alert) {
        String infoHash = alert.infoHash().toString();
        activeTorrents.remove(infoHash);
        log.info("Torrent removed: {} ({})", alert.torrentName(), infoHash);
    }

    private void handleTorrentFinishedAlert(TorrentFinishedAlert alert) {
        TorrentHandle handle = alert.handle();
        String torrentName = alert.torrentName();
        log.info("Download completed: {}", torrentName);

        // Optionally start seeding after completion
        if (handle.isValid()) {
            handle.resume(); // Continues as seed
        }
    }

    private void handleTorrentErrorAlert(TorrentErrorAlert alert) {
        log.error("Torrent error: {} - {}", alert.torrentName(), alert.error().message());
    }

    private void handleMetadataReceivedAlert(MetadataReceivedAlert alert) {
        log.info("Metadata received for: {}", alert.torrentName());
        // This is particularly important for magnet links
        // Once metadata is received, we have full torrent information
    }

    private void handleStateUpdateAlert(StateUpdateAlert alert) {
        // This alert contains status updates for all active torrents
        // Used for efficient batch status queries
        log.trace("State update received for {} torrents", alert.status().size());
    }

    /**
     * Get the underlying SessionManager instance.
     * Use with caution - direct access for advanced operations.
     */
    public SessionManager getSession() {
        waitForSessionStart();
        return sessionManager;
    }

    /**
     * Find a torrent by its info hash.
     * IMPORTANT: Always returns a fresh handle from session to avoid crashes.
     * DO NOT cache handles - they can become invalid and crash on any method call.
     */
    public TorrentHandle findTorrent(String infoHash) {
        waitForSessionStart();

        log.debug("Looking for torrent with infoHash: {}", infoHash);

        // ALWAYS get fresh handle from session, NEVER use cache
        // Caching handles is dangerous as they can become invalid
        try {
            // Method 1: Try direct lookup with Sha1Hash
            Sha1Hash sha1 = new Sha1Hash(infoHash);
            TorrentHandle handle = sessionManager.find(sha1);

            if (handle != null) {
                log.debug("Found handle using direct lookup for: {}", infoHash);
                return handle;
            }

            log.debug("Direct lookup failed, trying torrent list search");

            // Method 2: Iterate through all torrents (fallback)
            for (TorrentHandle h : sessionManager.getTorrentHandles()) {
                try {
                    TorrentStatus status = h.status();
                    // Use infoHash() - returns Sha1Hash object
                    String handleInfoHash = status.infoHash().toString();

                    log.trace("Checking torrent: {} with hash: {}", status.name(), handleInfoHash);

                    if (handleInfoHash.equalsIgnoreCase(infoHash)) {
                        log.debug("Found handle through iteration for: {}", infoHash);
                        return h;
                    }
                } catch (Exception e) {
                    log.trace("Error checking torrent in iteration: {}", e.getMessage());
                    continue;
                }
            }

            log.warn("Torrent not found in session: {}", infoHash);
            log.debug("Total torrents in session: {}", sessionManager.getTorrentHandles().length);

        } catch (Exception e) {
            log.error("Error finding torrent: {}", infoHash, e);
        }

        return null;
    }

    /**
     * Get all active torrent handles.
     */
    public Map<String, TorrentHandle> getActiveTorrents() {
        return new ConcurrentHashMap<>(activeTorrents);
    }

    /**
     * Periodically clean up invalid handles from cache.
     * This prevents crashes when accessing stale cached handles.
     */
    public void cleanupInvalidHandles() {
        activeTorrents.entrySet().removeIf(entry -> {
            try {
                TorrentHandle handle = entry.getValue();
                if (handle == null || !handle.isValid()) {
                    log.debug("Removing invalid handle from cache: {}", entry.getKey());
                    return true;
                }
                return false;
            } catch (Exception e) {
                // If checking validity causes exception, remove it
                log.warn("Exception checking handle validity for {}, removing from cache", entry.getKey());
                return true;
            }
        });
    }

    /**
     * Check if the session is running.
     */
    public boolean isRunning() {
        return isRunning && sessionManager != null;
    }

    /**
     * Wait for the session to start.
     * This is useful for operations that need the session to be fully initialized.
     */
    private void waitForSessionStart() {
        try {
            if (!sessionStartedLatch.await(30, TimeUnit.SECONDS)) {
                throw new RuntimeException("Timeout waiting for session to start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for session to start", e);
        }
    }

    /**
     * Clean shutdown of the torrent session.
     * This method is called automatically when the application shuts down.
     */
    @PreDestroy
    public void shutdown() {
        if (!isRunning) {
            return;
        }

        log.info("Shutting down TorrentSessionManager...");
        isRunning = false;

        try {
            // Save resume data for all active torrents
            saveAllResumeData();

            // Stop the session
            if (sessionManager != null) {
                sessionManager.stop();
            }

            activeTorrents.clear();
            log.info("TorrentSessionManager shut down successfully");

        } catch (Exception e) {
            log.error("Error during shutdown", e);
        }
    }

    /**
     * Save resume data for all active torrents.
     * This allows resuming downloads after restart.
     */
    private void saveAllResumeData() {
        log.info("Saving resume data for {} torrents", activeTorrents.size());

        for (TorrentHandle handle : activeTorrents.values()) {
            if (handle.isValid()) {
                try {
                    handle.saveResumeData();
                } catch (Exception e) {
                    log.error("Error saving resume data for torrent", e);
                }
            }
        }
    }

    /**
     * Get download directory, creating it if it doesn't exist.
     */
    public File getDownloadDirectory() {
        File dir = new File(properties.getDownloadsPath());
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (!created) {
                throw new RuntimeException("Failed to create download directory: " + dir.getAbsolutePath());
            }
        }
        return dir;
    }
}