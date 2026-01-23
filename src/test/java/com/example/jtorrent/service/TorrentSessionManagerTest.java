package com.example.jtorrent.service;

import com.example.jtorrent.config.TorrentProperties;
import com.frostwire.jlibtorrent.SessionManager;
import com.frostwire.jlibtorrent.Sha1Hash;
import com.frostwire.jlibtorrent.TorrentHandle;
import com.frostwire.jlibtorrent.TorrentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TorrentSessionManager Unit Tests")
class TorrentSessionManagerTest {

    private static final String HASH_1 = "0123456789abcdef0123456789abcdef01234567";
    private static final String HASH_2 = "abcdef0123456789abcdef0123456789abcdef01";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("isRunning should require running flag and session manager")
    void testIsRunning() {
        TorrentSessionManager manager = new TorrentSessionManager(new TorrentProperties());
        assertThat(manager.isRunning()).isFalse();

        SessionManager session = mock(SessionManager.class);
        setField(manager, "sessionManager", session);
        setField(manager, "isRunning", true);
        assertThat(manager.isRunning()).isTrue();

        setField(manager, "sessionManager", null);
        assertThat(manager.isRunning()).isFalse();
    }

    @Test
    @DisplayName("getActiveTorrents should return a copy")
    void testGetActiveTorrents_Copy() {
        TorrentSessionManager manager = new TorrentSessionManager(new TorrentProperties());
        Map<String, TorrentHandle> internal = getField(manager, "activeTorrents", Map.class);
        TorrentHandle handle = mock(TorrentHandle.class);
        internal.put(HASH_1, handle);

        Map<String, TorrentHandle> copy = manager.getActiveTorrents();
        assertThat(copy).hasSize(1);

        copy.put(HASH_2, mock(TorrentHandle.class));
        assertThat(internal).containsOnlyKeys(HASH_1);
    }

    @Test
    @DisplayName("cleanupInvalidHandles should remove invalid and erroring handles")
    void testCleanupInvalidHandles() {
        TorrentSessionManager manager = new TorrentSessionManager(new TorrentProperties());
        Map<String, TorrentHandle> internal = getField(manager, "activeTorrents", Map.class);

        TorrentHandle valid = mock(TorrentHandle.class);
        TorrentHandle invalid = mock(TorrentHandle.class);
        TorrentHandle throwing = mock(TorrentHandle.class);

        when(valid.isValid()).thenReturn(true);
        when(invalid.isValid()).thenReturn(false);
        when(throwing.isValid()).thenThrow(new RuntimeException("boom"));

        internal.put("valid", valid);
        internal.put("invalid", invalid);
        internal.put("throwing", throwing);

        manager.cleanupInvalidHandles();

        assertThat(internal).containsOnlyKeys("valid");
    }

    @Test
    @DisplayName("findTorrent should return direct lookup when found")
    void testFindTorrent_DirectLookup() {
        TorrentSessionManager manager = new TorrentSessionManager(new TorrentProperties());
        SessionManager session = mock(SessionManager.class);
        TorrentHandle handle = mock(TorrentHandle.class);

        setField(manager, "sessionManager", session);
        releaseSessionLatch(manager);
        when(session.find(any(Sha1Hash.class))).thenReturn(handle);

        TorrentHandle result = manager.findTorrent(HASH_1);

        assertThat(result).isSameAs(handle);
        verify(session, never()).getTorrentHandles();
    }

    @Test
    @DisplayName("findTorrent should fall back to iterating handles")
    void testFindTorrent_Fallback() {
        TorrentSessionManager manager = new TorrentSessionManager(new TorrentProperties());
        SessionManager session = mock(SessionManager.class);
        TorrentHandle handle1 = mock(TorrentHandle.class);
        TorrentHandle handle2 = mock(TorrentHandle.class);
        TorrentStatus status2 = mock(TorrentStatus.class);

        setField(manager, "sessionManager", session);
        releaseSessionLatch(manager);
        when(session.find(any(Sha1Hash.class))).thenReturn(null);
        when(handle1.status()).thenThrow(new RuntimeException("boom"));
        when(handle2.status()).thenReturn(status2);
        when(status2.infoHash()).thenReturn(new Sha1Hash(HASH_2));
        when(session.getTorrentHandles()).thenReturn(new TorrentHandle[]{handle1, handle2});

        TorrentHandle result = manager.findTorrent(HASH_2);

        assertThat(result).isSameAs(handle2);
    }

    @Test
    @DisplayName("findTorrent should return null when no handle matches")
    void testFindTorrent_NotFound() {
        TorrentSessionManager manager = new TorrentSessionManager(new TorrentProperties());
        SessionManager session = mock(SessionManager.class);
        TorrentHandle handle = mock(TorrentHandle.class);
        TorrentStatus status = mock(TorrentStatus.class);

        setField(manager, "sessionManager", session);
        releaseSessionLatch(manager);
        when(session.find(any(Sha1Hash.class))).thenReturn(null);
        when(handle.status()).thenReturn(status);
        when(status.infoHash()).thenReturn(new Sha1Hash(HASH_1));
        when(session.getTorrentHandles()).thenReturn(new TorrentHandle[]{handle});

        TorrentHandle result = manager.findTorrent(HASH_2);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("getSession should return session after latch is released")
    void testGetSession() {
        TorrentSessionManager manager = new TorrentSessionManager(new TorrentProperties());
        SessionManager session = mock(SessionManager.class);

        setField(manager, "sessionManager", session);
        releaseSessionLatch(manager);

        assertThat(manager.getSession()).isSameAs(session);
    }

    @Test
    @DisplayName("shutdown should save resume data, stop session, and clear state")
    void testShutdown() {
        TorrentSessionManager manager = new TorrentSessionManager(new TorrentProperties());
        SessionManager session = mock(SessionManager.class);
        TorrentHandle valid = mock(TorrentHandle.class);
        TorrentHandle invalid = mock(TorrentHandle.class);

        setField(manager, "sessionManager", session);
        setField(manager, "isRunning", true);

        Map<String, TorrentHandle> internal = getField(manager, "activeTorrents", Map.class);
        internal.put(HASH_1, valid);
        internal.put(HASH_2, invalid);

        when(valid.isValid()).thenReturn(true);
        when(invalid.isValid()).thenReturn(false);

        manager.shutdown();

        verify(valid).saveResumeData();
        verify(invalid, never()).saveResumeData();
        verify(session).stop();
        assertThat(internal).isEmpty();
        assertThat(manager.isRunning()).isFalse();
    }

    @Test
    @DisplayName("getDownloadDirectory should create directory when missing")
    void testGetDownloadDirectory_CreatesDir() {
        TorrentProperties props = new TorrentProperties();
        Path downloadPath = tempDir.resolve("downloads");
        props.setDownloadsPath(downloadPath.toString());

        TorrentSessionManager manager = new TorrentSessionManager(props);
        File dir = manager.getDownloadDirectory();

        assertThat(dir.exists()).isTrue();
        assertThat(dir.isDirectory()).isTrue();
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void releaseSessionLatch(TorrentSessionManager manager) {
        CountDownLatch latch = getField(manager, "sessionStartedLatch", CountDownLatch.class);
        latch.countDown();
    }
}
