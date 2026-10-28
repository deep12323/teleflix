package dev.jdtech.mpv;

import android.content.Context;
import android.view.Surface;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure Java JNI wrapper for MPV native library.
 * Compatible with all Android & Kotlin versions without metadata incompatibilities.
 */
public final class MPVLib {

    static {
        System.loadLibrary("mpv");
        System.loadLibrary("player");
    }

    private long nativeInstance;
    private final List<EventObserver> observers = new ArrayList<>();
    private final List<LogObserver> logObservers = new ArrayList<>();

    public MPVLib() {
        this(0);
    }

    public MPVLib(long nativeInstance) {
        this.nativeInstance = nativeInstance;
    }

    public static MPVLib create(Context context) {
        Context appContext = context != null ? context.getApplicationContext() : null;
        MPVLib instance = new MPVLib(0);
        long ptr = instance.nativeCreate(instance, appContext);
        if (ptr == 0) {
            return null;
        }
        instance.nativeInstance = ptr;
        return instance;
    }

    private void checkCreated() {
        if (nativeInstance == 0) {
            throw new IllegalStateException("MPVLib is not initialized or already destroyed");
        }
    }

    private native long nativeCreate(MPVLib instance, Context context);
    private native void nativeInit(long nativeInstance);
    private native void nativeDestroy(long nativeInstance);
    private native void nativeAttachSurface(long nativeInstance, Surface surface);
    private native void nativeDetachSurface(long nativeInstance);
    private native void nativeCommand(long nativeInstance, String[] params);
    private native int nativeSetOptionString(long nativeInstance, String name, String value);
    private native Integer nativeGetPropertyInt(long nativeInstance, String name);
    private native void nativeSetPropertyInt(long nativeInstance, String name, int value);
    private native Double nativeGetPropertyDouble(long nativeInstance, String name);
    private native void nativeSetPropertyDouble(long nativeInstance, String name, double value);
    private native Boolean nativeGetPropertyBoolean(long nativeInstance, String name);
    private native void nativeSetPropertyBoolean(long nativeInstance, String name, boolean value);
    private native String nativeGetPropertyString(long nativeInstance, String name);
    private native void nativeSetPropertyString(long nativeInstance, String name, String value);
    private native void nativeObserveProperty(long nativeInstance, String property, int format);

    public void init() {
        checkCreated();
        nativeInit(nativeInstance);
    }

    public void destroy() {
        checkCreated();
        nativeDestroy(nativeInstance);
        nativeInstance = 0;
    }

    public void attachSurface(Surface surface) {
        checkCreated();
        nativeAttachSurface(nativeInstance, surface);
    }

    public void detachSurface() {
        checkCreated();
        nativeDetachSurface(nativeInstance);
    }

    public void command(String[] params) {
        checkCreated();
        nativeCommand(nativeInstance, params);
    }

    public int setOptionString(String name, String value) {
        checkCreated();
        return nativeSetOptionString(nativeInstance, name, value);
    }

    public Integer getPropertyInt(String name) {
        checkCreated();
        return nativeGetPropertyInt(nativeInstance, name);
    }

    public void setPropertyInt(String name, int value) {
        checkCreated();
        nativeSetPropertyInt(nativeInstance, name, value);
    }

    public Double getPropertyDouble(String name) {
        checkCreated();
        return nativeGetPropertyDouble(nativeInstance, name);
    }

    public void setPropertyDouble(String name, double value) {
        checkCreated();
        nativeSetPropertyDouble(nativeInstance, name, value);
    }

    public Boolean getPropertyBoolean(String name) {
        checkCreated();
        return nativeGetPropertyBoolean(nativeInstance, name);
    }

    public void setPropertyBoolean(String name, boolean value) {
        checkCreated();
        nativeSetPropertyBoolean(nativeInstance, name, value);
    }

    public String getPropertyString(String name) {
        checkCreated();
        return nativeGetPropertyString(nativeInstance, name);
    }

    public void setPropertyString(String name, String value) {
        checkCreated();
        nativeSetPropertyString(nativeInstance, name, value);
    }

    public void observeProperty(String property, int format) {
        checkCreated();
        nativeObserveProperty(nativeInstance, property, format);
    }

    public void addObserver(EventObserver observer) {
        if (observer == null) return;
        synchronized (observers) {
            observers.add(observer);
        }
    }

    public void removeObserver(EventObserver observer) {
        if (observer == null) return;
        synchronized (observers) {
            observers.remove(observer);
        }
    }

    public void addLogObserver(LogObserver observer) {
        if (observer == null) return;
        synchronized (logObservers) {
            logObservers.add(observer);
        }
    }

    public void removeLogObserver(LogObserver observer) {
        if (observer == null) return;
        synchronized (logObservers) {
            logObservers.remove(observer);
        }
    }

    // Callbacks from JNI in libplayer.so
    public void eventProperty(String property) {
        List<EventObserver> copy;
        synchronized (observers) {
            copy = new ArrayList<>(observers);
        }
        for (EventObserver observer : copy) {
            observer.eventProperty(property);
        }
    }

    public void eventProperty(String property, long value) {
        List<EventObserver> copy;
        synchronized (observers) {
            copy = new ArrayList<>(observers);
        }
        for (EventObserver observer : copy) {
            observer.eventProperty(property, value);
        }
    }

    public void eventProperty(String property, double value) {
        List<EventObserver> copy;
        synchronized (observers) {
            copy = new ArrayList<>(observers);
        }
        for (EventObserver observer : copy) {
            observer.eventProperty(property, value);
        }
    }

    public void eventProperty(String property, boolean value) {
        List<EventObserver> copy;
        synchronized (observers) {
            copy = new ArrayList<>(observers);
        }
        for (EventObserver observer : copy) {
            observer.eventProperty(property, value);
        }
    }

    public void eventProperty(String property, String value) {
        List<EventObserver> copy;
        synchronized (observers) {
            copy = new ArrayList<>(observers);
        }
        for (EventObserver observer : copy) {
            observer.eventProperty(property, value);
        }
    }

    public void event(int eventId) {
        List<EventObserver> copy;
        synchronized (observers) {
            copy = new ArrayList<>(observers);
        }
        for (EventObserver observer : copy) {
            observer.event(eventId);
        }
    }

    public void logMessage(String prefix, int level, String text) {
        List<LogObserver> copy;
        synchronized (logObservers) {
            copy = new ArrayList<>(logObservers);
        }
        for (LogObserver observer : copy) {
            observer.logMessage(prefix, level, text);
        }
    }

    public interface EventObserver {
        void eventProperty(String property);
        void eventProperty(String property, long value);
        void eventProperty(String property, double value);
        void eventProperty(String property, boolean value);
        void eventProperty(String property, String value);
        void event(int eventId);
    }

    public interface LogObserver {
        void logMessage(String prefix, int level, String text);
    }

    public static final class MpvEvent {
        public static final int MPV_EVENT_NONE = 0;
        public static final int MPV_EVENT_SHUTDOWN = 1;
        public static final int MPV_EVENT_LOG_MESSAGE = 2;
        public static final int MPV_EVENT_GET_PROPERTY_REPLY = 3;
        public static final int MPV_EVENT_SET_PROPERTY_REPLY = 4;
        public static final int MPV_EVENT_COMMAND_REPLY = 5;
        public static final int MPV_EVENT_START_FILE = 6;
        public static final int MPV_EVENT_END_FILE = 7;
        public static final int MPV_EVENT_FILE_LOADED = 8;
        public static final int MPV_EVENT_IDLE = 11;
        public static final int MPV_EVENT_TICK = 14;
        public static final int MPV_EVENT_CLIENT_MESSAGE = 16;
        public static final int MPV_EVENT_VIDEO_RECONFIG = 17;
        public static final int MPV_EVENT_AUDIO_RECONFIG = 18;
        public static final int MPV_EVENT_SEEK = 20;
        public static final int MPV_EVENT_PLAYBACK_RESTART = 21;
        public static final int MPV_EVENT_PROPERTY_CHANGE = 22;
        public static final int MPV_EVENT_QUEUE_OVERFLOW = 24;
        public static final int MPV_EVENT_HOOK = 25;

        private MpvEvent() {}
    }

    public static final class MpvFormat {
        public static final int MPV_FORMAT_NONE = 0;
        public static final int MPV_FORMAT_STRING = 1;
        public static final int MPV_FORMAT_OSD_STRING = 2;
        public static final int MPV_FORMAT_FLAG = 3;
        public static final int MPV_FORMAT_INT64 = 4;
        public static final int MPV_FORMAT_DOUBLE = 5;
        public static final int MPV_FORMAT_NODE = 6;
        public static final int MPV_FORMAT_NODE_ARRAY = 7;
        public static final int MPV_FORMAT_NODE_MAP = 8;
        public static final int MPV_FORMAT_BYTE_ARRAY = 9;

        private MpvFormat() {}
    }

    public static final class MpvLogLevel {
        public static final int MPV_LOG_LEVEL_NONE = 0;
        public static final int MPV_LOG_LEVEL_FATAL = 10;
        public static final int MPV_LOG_LEVEL_ERROR = 20;
        public static final int MPV_LOG_LEVEL_WARN = 30;
        public static final int MPV_LOG_LEVEL_INFO = 40;
        public static final int MPV_LOG_LEVEL_V = 50;
        public static final int MPV_LOG_LEVEL_DEBUG = 60;
        public static final int MPV_LOG_LEVEL_TRACE = 70;

        private MpvLogLevel() {}
    }
}
