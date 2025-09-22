/*
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.graph.Graph;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.Graphs;
import com.google.common.graph.MutableGraph;
import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;
import com.google.inject.Module;
import com.google.inject.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.events.ExternalPluginsChanged;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.task.Schedule;
import net.runelite.client.task.ScheduledMethod;
import net.runelite.client.task.Scheduler;
import net.runelite.client.ui.SplashScreen;
import net.runelite.client.util.GameEventManager;
import net.runelite.client.util.ReflectUtil;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.swing.*;
import java.io.File;
import java.io.Closeable;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.security.CodeSource;
import java.lang.invoke.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

@Singleton
@Slf4j
public class PluginManager {
    /**
     * Base package where the core plugins are
     */
    private static final String PLUGIN_PACKAGE = "net.runelite.client.plugins";
    private static final File SIDELOADED_PLUGINS = new File(RuneLite.RUNELITE_DIR, "sideloaded-plugins");

    private final boolean safeMode;
    private final EventBus eventBus;
    private final Scheduler scheduler;
    private final ConfigManager configManager;
    private final Provider<GameEventManager> sceneTileManager;
    private final List<Plugin> plugins = new CopyOnWriteArrayList<>();
    @Getter
    private final List<Plugin> activePlugins = new CopyOnWriteArrayList<>();

    // Hot-reload support fields
    private WatchService watchService;
    private Thread pluginWatchThread;
    private ScheduledExecutorService hotReloadExecutor;
    private static final long HOT_RELOAD_DEBOUNCE_MS = 750L;
    private final Map<File, PluginFileEvent> pendingPluginFileEvents = new ConcurrentHashMap<>();

    public void addPlugin(Plugin plugin) {
        plugins.add(plugin);
    }

    @Inject
    @VisibleForTesting
    PluginManager(
            @Named("safeMode") final boolean safeMode,
            final EventBus eventBus,
            final Scheduler scheduler,
            final ConfigManager configManager,
            final Provider<GameEventManager> sceneTileManager) {
        this.safeMode = safeMode;
        this.eventBus = eventBus;
        this.scheduler = scheduler;
        this.configManager = configManager;
        this.sceneTileManager = sceneTileManager;
    }

    @Subscribe
    public void onProfileChanged(ProfileChanged profileChanged) {
        refreshPlugins();
    }

    private void refreshPlugins() {
        loadDefaultPluginConfiguration(null);
        SwingUtilities.invokeLater(() ->
        {
            for (Plugin plugin : getPlugins()) {
                try {
                    if (isPluginEnabled(plugin) != activePlugins.contains(plugin)) {
                        if (activePlugins.contains(plugin)) {
                            stopPlugin(plugin);
                        } else {
                            startPlugin(plugin);
                        }
                    }
                } catch (PluginInstantiationException e) {
                    log.error("Error during starting/stopping plugin {}", plugin.getClass().getSimpleName(), e);
                }
            }
        });
    }

    public Config getPluginConfigProxy(Plugin plugin) {
        try {
            final Injector injector = plugin.getInjector();

            for (Key<?> key : injector.getBindings().keySet()) {
                Class<?> type = key.getTypeLiteral().getRawType();
                if (Config.class.isAssignableFrom(type)) {
                    return (Config) injector.getInstance(key);
                }
            }
        } catch (ThreadDeath e) {
            throw e;
        } catch (Throwable e) {
            log.error("Unable to get plugin config", e);
        }
        return null;
    }

    public List<Config> getPluginConfigProxies(Collection<Plugin> plugins) {
        List<Injector> injectors = new ArrayList<>();
        if (plugins == null) {
            injectors.add(Microbot.getInjector());
            plugins = getPlugins();
        }
        plugins.forEach(pl -> injectors.add(pl.getInjector()));

        List<Config> list = new ArrayList<>();
        for (Injector injector : injectors) {
            for (Key<?> key : injector.getBindings().keySet()) {
                Class<?> type = key.getTypeLiteral().getRawType();
                if (Config.class.isAssignableFrom(type)) {
                    Config config = (Config) injector.getInstance(key);
                    list.add(config);
                }
            }
        }

        return list;
    }

    public void loadDefaultPluginConfiguration(Collection<Plugin> plugins) {
        try {
            for (Config config : getPluginConfigProxies(plugins)) {
                configManager.setDefaultConfiguration(config, false);
            }
        } catch (ThreadDeath e) {
            throw e;
        } catch (Throwable ex) {
            log.error("Unable to reset plugin configuration", ex);
        }
    }

    public void startPlugins() {
        List<Plugin> scannedPlugins = new ArrayList<>(plugins);
        scannedPlugins.sort(Comparator.comparingInt(p ->
        {
            final PluginDescriptor pluginDescriptor = p.getClass().getAnnotation(PluginDescriptor.class);
            if (pluginDescriptor == null) {
                return Integer.MAX_VALUE;
            }
            return pluginDescriptor.priority() ? 0 : 1;
        }));
        int loaded = 0;
        for (Plugin plugin : scannedPlugins) {
            try {
                Runnable runnable = () -> {
                    try {
                            startPlugin(plugin);
                        } catch (PluginInstantiationException ex) {
                            log.error("Unable to start plugin {}", plugin.getClass().getSimpleName(), ex);
                        }
                };
                if (SwingUtilities.isEventDispatchThread()) {
                    runnable.run();
                }else{
                    SwingUtilities.invokeAndWait(() ->
                    {
                    runnable.run();

                    });
                }
            } catch (InterruptedException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }

            loaded++;
            SplashScreen.stage(.80, 1, null, "Starting plugins", loaded, scannedPlugins.size(), false);
        }

        for (Plugin plugin : plugins) {
            ReflectUtil.queueInjectorAnnotationCacheInvalidation(plugin.injector);
        }
    }

    /**
     * Loads core RuneLite plugins, excluding any Microbot-related plugins.
     * This method filters out plugins from the microbot package hierarchy.
     */
    public void loadCoreRunelitePlugins() throws IOException, PluginInstantiationException {
        SplashScreen.stage(.59, null, "Loading core RuneLite plugins");
        ClassPath classPath = ClassPath.from(getClass().getClassLoader());

        List<Class<?>> plugins = classPath.getTopLevelClassesRecursive(PLUGIN_PACKAGE).stream()
                .map(ClassInfo::load)
                .filter(clazz -> !isMicrobotRelatedClass(clazz))
                .collect(Collectors.toList());

        loadPlugins(plugins, (loaded, total) ->
                SplashScreen.stage(.60, .70, null, "Loading core RuneLite plugins", loaded, total, false));
    }

    /**
     * Determines if a class is related to Microbot and should be excluded from core RuneLite plugin loading.
     *
     * @param clazz the class to check
     * @return true if the class is Microbot-related and should be filtered out
     */
    private static boolean isMicrobotRelatedClass(Class<?> clazz) {
        if (clazz == null || clazz.getPackage() == null) {
            return false;
        }

        String packageName = clazz.getPackage().getName();

        return packageName.startsWith(PLUGIN_PACKAGE + ".microbot");
    }

    public void loadCorePlugins() throws IOException, PluginInstantiationException {
        SplashScreen.stage(.59, null, "Loading plugins");
        ClassPath classPath = ClassPath.from(getClass().getClassLoader());

        List<Class<?>> plugins = classPath.getTopLevelClassesRecursive(PLUGIN_PACKAGE).stream()
                .map(ClassInfo::load)
                .collect(Collectors.toList());

        loadPlugins(plugins, (loaded, total) ->
                SplashScreen.stage(.60, .70, null, "Loading plugins", loaded, total, false));
    }

    public void loadSideLoadPlugins() {
        File[] files = SIDELOADED_PLUGINS.listFiles();
        if (files == null) {
            return;
        }

        for (File f : files) {
            if (f.getName().endsWith(".jar")) {
                log.info("Side-loading plugin {}", f);

                try {
                    ClassLoader classLoader = new PluginClassLoader(f, getClass().getClassLoader());

                    List<Class<?>> plugins = ClassPath.from(classLoader)
                            .getAllClasses()
                            .stream()
                            .map(ClassInfo::load)
                            .collect(Collectors.toList());

                    loadPlugins(plugins, null);
                } catch (PluginInstantiationException | IOException ex) {
                    log.error("error sideloading plugin", ex);
                }
            }
        }

        // After initial load, start watching for hot-reload
        startWatchingSideLoadPluginDirectories();
    }

    /**
     * Initialize a file watcher on side-loaded plugin directories to support hot reloading
     * when jar files are created, modified, or deleted.
     */
    private synchronized void startWatchingSideLoadPluginDirectories() {
        if (pluginWatchThread != null && pluginWatchThread.isAlive()) {
            return; // already running
        }
        try {
            if (!SIDELOADED_PLUGINS.exists()) {
                return; // directory absent; mimic original behavior (no creation side-effect)
            }
            watchService = FileSystems.getDefault().newWatchService();
            SIDELOADED_PLUGINS.toPath().register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        } catch (IOException e) {
            log.warn("Unable to start plugin hot-reload watcher", e);
            return;
        }

        pluginWatchThread = new Thread(() -> {
            log.info("Plugin hot-reload watcher started");
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key;
                try {
                    key = watchService.take(); // blocking
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    log.error("Watcher exception", t);
                    sleepQuietly(5_000);
                    continue;
                }
                final Path dir = (Path) key.watchable();
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) continue;
                    Path context = (Path) event.context();
                    if (context == null) continue;
                    if (!context.toString().endsWith(".jar")) continue;
                    File jarFile = dir.resolve(context).toFile();
                    recordPluginFileEvent(jarFile, kind);
                }
                boolean valid = key.reset();
                if (!valid) {
                    log.warn("Plugin watcher key no longer valid; restarting watcher");
                    // Attempt restart
                    startWatchingSideLoadPluginDirectories();
                    break;
                }
            }
            log.info("Plugin hot-reload watcher stopped");
        }, "PluginManager-HotReload-Watcher");
        pluginWatchThread.setDaemon(true);
        pluginWatchThread.start();

        if (hotReloadExecutor == null || hotReloadExecutor.isShutdown()) {
            hotReloadExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "PluginManager-HotReload-Debounce");
                t.setDaemon(true);
                return t;
            });
            hotReloadExecutor.scheduleAtFixedRate(this::processDebouncedPluginFileEvents, HOT_RELOAD_DEBOUNCE_MS, HOT_RELOAD_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }

        // Cleanup hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { if (watchService != null) watchService.close(); } catch (IOException ignored) {}
            if (hotReloadExecutor != null) hotReloadExecutor.shutdownNow();
        }, "PluginManager-HotReload-Shutdown"));
    }

    private static void sleepQuietly(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void reloadPluginsFromFile(File pluginFile) {
        log.info("Detected change for plugin jar: {}", pluginFile.getName());
        List<Plugin> existing = findPluginsByJar(pluginFile);
        if (existing.isEmpty()) {
            // new plugin jar added
            try {
                List<Plugin> newPlugins = loadPluginsFromJar(pluginFile);
                log.info("Loaded {} new plugin(s) from {}", newPlugins.size(), pluginFile.getName());
            } catch (Exception e) {
                log.error("Failed loading new plugin jar {}", pluginFile.getName(), e);
            }
            return;
        }
        // Preserve enabled state
        Set<String> enabledNames = existing.stream()
                .filter(this::isPluginEnabled)
                .map(this::pluginIdentity)
                .collect(Collectors.toSet());

        // Stop and remove synchronously on EDT
        runOnEdtSync(() -> existing.forEach(this::stopAndRemovePlugin));
        // Attempt to close classloaders used by these plugins
        closeClassLoadersForPlugins(existing);

        try {
            List<Plugin> reloaded = loadPluginsFromJar(pluginFile);
            List<Plugin> toStart = reloaded.stream()
                    .filter(p -> enabledNames.contains(pluginIdentity(p)))
                    .collect(Collectors.toList());
            // Start synchronously on EDT
            runOnEdtSync(() -> {
                for (Plugin p : toStart) {
                    try {
                        setPluginEnabled(p, true);
                        startPlugin(p);
                    } catch (Exception ex) {
                        log.error("Unable to start plugin {} after reload", p.getClass().getSimpleName(), ex);
                    }
                }
            });
            log.info("Reloaded {} plugin(s) from {}", reloaded.size(), pluginFile.getName());
        } catch (Exception e) {
            log.error("Failed reloading plugin jar {}", pluginFile.getName(), e);
        }
    }

    private void recordPluginFileEvent(File jarFile, WatchEvent.Kind<?> kind) {
        if (jarFile == null) return;
        pendingPluginFileEvents.compute(jarFile, (file, current) -> {
            long now = System.currentTimeMillis();
            EventType newType = (kind == ENTRY_DELETE) ? EventType.DELETE : EventType.RELOAD;
            if (current == null) {
                return new PluginFileEvent(newType, now);
            }
            // Always reflect the last-seen event within the debounce window
            current.type = newType;
            current.lastChange = now;
            return current;
        });
    }

    private void processDebouncedPluginFileEvents() {
        long now = System.currentTimeMillis();
        List<Map.Entry<File, PluginFileEvent>> ready = new ArrayList<>();
        for (Map.Entry<File, PluginFileEvent> e : pendingPluginFileEvents.entrySet()) {
            if (now - e.getValue().lastChange >= HOT_RELOAD_DEBOUNCE_MS) {
                ready.add(e);
            }
        }
        if (ready.isEmpty()) return;
        for (Map.Entry<File, PluginFileEvent> e : ready) {
            pendingPluginFileEvents.remove(e.getKey());
            PluginFileEvent ev = e.getValue();
            // If final event was DELETE but file currently exists, treat as RELOAD (replace sequence)
            if (ev.type == EventType.DELETE && e.getKey().exists()) {
                ev.type = EventType.RELOAD;
            }
            if (ev.type == EventType.DELETE) {
                unloadPluginsFromFile(e.getKey());
            } else {
                reloadPluginsFromFile(e.getKey());
            }
        }
        // Notify listeners after processing a batch of changes
        try {
            eventBus.post(new ExternalPluginsChanged());
        } catch (Throwable t) {
            log.debug("Failed to post ExternalPluginsChanged", t);
        }
    }

    private void unloadPluginsFromFile(File pluginFile) {
        log.info("Detected deletion for plugin jar: {}", pluginFile.getName());
        List<Plugin> existing = findPluginsByJar(pluginFile);
        runOnEdtSync(() -> existing.forEach(this::stopAndRemovePlugin));
        closeClassLoadersForPlugins(existing);
    }

    // Removed tryStartPluginOnEDT in favor of runOnEdtSync

    private List<Plugin> loadPluginsFromJar(File pluginFile) throws IOException, PluginInstantiationException {
        if (!pluginFile.exists()) return Collections.emptyList();
        ClassLoader classLoader = new PluginClassLoader(pluginFile, getClass().getClassLoader());
        List<Class<?>> pluginClasses = ClassPath.from(classLoader)
                .getAllClasses()
                .stream()
                .map(ClassInfo::load)
                .collect(Collectors.toList());
        return loadPlugins(pluginClasses, null);
    }

    private List<Plugin> findPluginsByJar(File pluginFile) {
        return plugins.stream()
                .filter(p -> {
                    File jar = getJarFile(p);
                    return jar != null && jar.equals(pluginFile);
                })
                .collect(Collectors.toList());
    }

    private static File getJarFile(Plugin plugin) {
        CodeSource codeSource = plugin.getClass().getProtectionDomain().getCodeSource();
        if (codeSource != null && codeSource.getLocation() != null) {
            try {
                return new File(codeSource.getLocation().toURI());
            } catch (URISyntaxException e) {
                return null;
            }
        }
        return null;
    }

    private void stopAndRemovePlugin(Plugin plugin) {
        try {
            setPluginEnabled(plugin, false);
            if (activePlugins.contains(plugin)) {
                stopPlugin(plugin);
            }
        } catch (PluginInstantiationException e) {
            log.error("Error stopping plugin {} during reload", plugin.getClass().getSimpleName(), e);
        }
        try {
            if (plugin.injector != null) {
                ReflectUtil.queueInjectorAnnotationCacheInvalidation(plugin.injector);
            }
        } catch (Throwable t) {
            log.debug("Injector cache invalidation failed for {}", plugin.getClass().getSimpleName(), t);
        }
        remove(plugin);
        log.info("Removed plugin {}", pluginIdentity(plugin));
    }

    private String pluginIdentity(Plugin plugin) {
        PluginDescriptor d = plugin.getClass().getAnnotation(PluginDescriptor.class);
        return d != null ? d.name() : plugin.getClass().getSimpleName();
    }

    // Attempt to close classloaders used by a set of plugins to free jar locks
    private void closeClassLoadersForPlugins(Collection<Plugin> ps) {
        Set<ClassLoader> cls = ps.stream().map(p -> p.getClass().getClassLoader()).collect(Collectors.toSet());
        for (ClassLoader cl : cls) {
            if (cl instanceof Closeable) {
                try {
                    ((Closeable) cl).close();
                } catch (IOException ioe) {
                    log.debug("Failed to close plugin classloader", ioe);
                }
            }
        }
    }

    // Helper to run code synchronously on the EDT
    private void runOnEdtSync(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(r);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new RuntimeException(cause);
            }
        }
    }

    // Debounce support types
    private enum EventType { RELOAD, DELETE }
    private static final class PluginFileEvent {
        EventType type;
        long lastChange;
        PluginFileEvent(EventType type, long lastChange) {
            this.type = type;
            this.lastChange = lastChange;
        }
    }

    public List<Plugin> loadPlugins(List<Class<?>> plugins, BiConsumer<Integer, Integer> onPluginLoaded) throws PluginInstantiationException {
        MutableGraph<Class<? extends Plugin>> graph = GraphBuilder
                .directed()
                .build();

        for (Class<?> clazz : plugins) {
            PluginDescriptor pluginDescriptor = clazz.getAnnotation(PluginDescriptor.class);

            if (pluginDescriptor == null) {
                if (clazz.getSuperclass() == Plugin.class) {
                    log.error("Class {} is a plugin, but has no plugin descriptor", clazz);
                }
                continue;
            }

            if (clazz.getSuperclass() != Plugin.class) {
                log.error("Class {} has plugin descriptor, but is not a plugin", clazz);
                continue;
            }

            if (safeMode && !pluginDescriptor.loadInSafeMode()) {
                log.debug("Disabling {} due to safe mode", clazz);
                // also disable the plugin from autostarting later
                configManager.unsetConfiguration(RuneLiteConfig.GROUP_NAME,
                        (Strings.isNullOrEmpty(pluginDescriptor.configName()) ? clazz.getSimpleName() : pluginDescriptor.configName()).toLowerCase());
                continue;
            }

            @SuppressWarnings("unchecked")
            Class<? extends Plugin> pluginClass = (Class<? extends Plugin>) clazz;
            graph.addNode(pluginClass);
        }

        // Build plugin graph
        for (Class<? extends Plugin> pluginClazz : graph.nodes()) {
            PluginDependency[] pluginDependencies = pluginClazz.getAnnotationsByType(PluginDependency.class);

            for (PluginDependency pluginDependency : pluginDependencies) {
                if (graph.nodes().contains(pluginDependency.value())) {
                    graph.putEdge(pluginDependency.value(), pluginClazz);
                }
            }
        }

        if (Graphs.hasCycle(graph)) {
            throw new PluginInstantiationException("Plugin dependency graph contains a cycle!");
        }

        List<Class<? extends Plugin>> sortedPlugins = topologicalSort(graph);

        int loaded = 0;
        List<Plugin> newPlugins = new ArrayList<>();
        for (Class<? extends Plugin> pluginClazz : sortedPlugins) {
            Plugin plugin;
            try {
                plugin = instantiate(this.plugins, pluginClazz);
                newPlugins.add(plugin);
                add(plugin);
            } catch (PluginInstantiationException ex) {
                log.error("Error instantiating plugin!", ex);
            }

            loaded++;
            if (onPluginLoaded != null) {
                onPluginLoaded.accept(loaded, sortedPlugins.size());
            }
        }

        return newPlugins;
    }

    public boolean startPlugin(Plugin plugin) throws PluginInstantiationException {
        // plugins always start in the EDT
        assert SwingUtilities.isEventDispatchThread();

        if (activePlugins.contains(plugin) || !isPluginEnabled(plugin)) {
            return false;
        }

        List<Plugin> conflicts = conflictsForPlugin(plugin);
        for (Plugin conflict : conflicts) {
            if (isPluginEnabled(conflict)) {
                setPluginEnabled(conflict, false);
            }
            if (activePlugins.contains(conflict)) {
                stopPlugin(conflict);
            }
        }

        activePlugins.add(plugin);

        try {
            plugin.startUp();

            log.debug("Plugin {} is now running", plugin.getClass().getSimpleName());
            if (sceneTileManager != null) {
                final GameEventManager gameEventManager = this.sceneTileManager.get();
                if (gameEventManager != null) {
                    gameEventManager.simulateGameEvents(plugin);
                }
            }

            eventBus.register(plugin);
            schedule(plugin);
            eventBus.post(new PluginChanged(plugin, true));
        } catch (ThreadDeath e) {
            throw e;
        } catch (Throwable ex) {
            // stop the plugin and fire the change event to update the plugin list panel
            try {
                stopPlugin(plugin);
            } catch (Throwable ex2) {
                log.error("unable to stop plugin", ex2);
            }
            throw new PluginInstantiationException(ex);
        }

        return true;
    }

    public boolean stopPlugin(Plugin plugin) throws PluginInstantiationException {
        // plugins always stop in the EDT
        assert SwingUtilities.isEventDispatchThread();

        if (!activePlugins.remove(plugin)) {
            return false;
        }

        unschedule(plugin);
        eventBus.unregister(plugin);

        try {
            plugin.shutDown();

            log.debug("Plugin {} is now stopped", plugin.getClass().getSimpleName());
            eventBus.post(new PluginChanged(plugin, false));
        } catch (Exception ex) {
            throw new PluginInstantiationException(ex);
        }

        return true;
    }

    public boolean isActive(Plugin plugin) {
        return activePlugins.contains(plugin);
    }

    public void setPluginValue(String groupName, String keyName, Object value) {
        configManager.setConfiguration(groupName, keyName, value);
    }

    public void setPluginEnabled(Plugin plugin, boolean enabled) {
        final PluginDescriptor pluginDescriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
        final String keyName = Strings.isNullOrEmpty(pluginDescriptor.configName()) ? plugin.getClass().getSimpleName() : pluginDescriptor.configName();
        configManager.setConfiguration(RuneLiteConfig.GROUP_NAME, keyName.toLowerCase(), String.valueOf(enabled));

        if (enabled) {
            List<Plugin> conflicts = conflictsForPlugin(plugin);
            for (Plugin conflict : conflicts) {
                if (isPluginEnabled(conflict)) {
                    setPluginEnabled(conflict, false);
                }
            }
        }
    }

    /**
     * Test if a plugin is enabled, which causes the client to attempt to start it on boot
     *
     * @param plugin
     * @return
     */
    public boolean isPluginEnabled(Plugin plugin) {
        final PluginDescriptor pluginDescriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
        final String keyName = Strings.isNullOrEmpty(pluginDescriptor.configName()) ? plugin.getClass().getSimpleName() : pluginDescriptor.configName();
        final String value = configManager.getConfiguration(RuneLiteConfig.GROUP_NAME, keyName.toLowerCase());

        if (pluginDescriptor.alwaysOn())
            return true;

        return value != null ? Boolean.parseBoolean(value) : pluginDescriptor.enabledByDefault();
    }

    /**
     * Test if a plugin is on, eg. enabled and also was started successfully
     *
     * @param plugin
     * @return
     */
    public boolean isPluginActive(Plugin plugin) {
        return activePlugins.contains(plugin);
    }

    private Plugin instantiate(List<Plugin> scannedPlugins, Class<? extends Plugin> clazz) throws PluginInstantiationException {
        PluginDependency[] pluginDependencies = clazz.getAnnotationsByType(PluginDependency.class);
        List<Plugin> deps = new ArrayList<>();
        for (PluginDependency pluginDependency : pluginDependencies) {
            Optional<Plugin> dependency = scannedPlugins.stream().filter(p -> p.getClass() == pluginDependency.value()).findFirst();
            if (!dependency.isPresent()) {
                throw new PluginInstantiationException("Unmet dependency for " + clazz.getSimpleName() + ": " + pluginDependency.value().getSimpleName());
            }
            deps.add(dependency.get());
        }

        Plugin plugin;
        try {
            plugin = clazz.getDeclaredConstructor().newInstance();
        } catch (ThreadDeath e) {
            throw e;
        } catch (Throwable ex) {
            throw new PluginInstantiationException(ex);
        }

        try {
            Injector parent = Microbot.getInjector();

            if (deps.size() > 1) {
                List<Module> modules = new ArrayList<>(deps.size());
                for (Plugin p : deps) {
                    Module module = (Binder binder) -> {
                        bindPluginInstance(binder, (Class<? extends Plugin>) p.getClass(), p);
                        binder.install(p);
                    };
                    modules.add(module);
                }

                // Create a parent injector containing all of the dependencies
                parent = parent.createChildInjector(modules);
            } else if (!deps.isEmpty()) {
                // With only one dependency we can simply use its injector
                parent = deps.get(0).injector;
            }

            // Create injector for the module
            Module pluginModule = (Binder binder) ->
            {
                // Since the plugin itself is a module, it won't bind itself, so we'll bind it here
                bindPluginInstance(binder, clazz, plugin);
                binder.install(plugin);
            };
            Injector pluginInjector = parent.createChildInjector(pluginModule);
            plugin.injector = pluginInjector;
        } catch (CreationException ex) {
            throw new PluginInstantiationException(ex);
        }

        log.debug("Loaded plugin {}", clazz.getSimpleName());
        return plugin;
    }

    private static <T extends Plugin> void bindPluginInstance(Binder binder, Class<T> cls, Plugin instance) {
    // Safe due to type hierarchy of plugins
    binder.bind(cls).toInstance(cls.cast(instance));
    }

    public void add(Plugin plugin) {
        plugins.add(plugin);
    }

    public void remove(Plugin plugin) {
        plugins.remove(plugin);
    }

    public Collection<Plugin> getPlugins() {
        return plugins;
    }

    private void schedule(Plugin plugin) {
        for (Method method : plugin.getClass().getMethods()) {
            Schedule schedule = method.getAnnotation(Schedule.class);

            if (schedule == null) {
                continue;
            }

            Runnable runnable = null;
            try {
                final Class<?> clazz = method.getDeclaringClass();
                final MethodHandles.Lookup caller = ReflectUtil.privateLookupIn(clazz);
                final MethodType subscription = MethodType.methodType(method.getReturnType(), method.getParameterTypes());
                final MethodHandle target = caller.findVirtual(clazz, method.getName(), subscription);
                final CallSite site = LambdaMetafactory.metafactory(
                        caller,
                        "run",
                        MethodType.methodType(Runnable.class, clazz),
                        subscription,
                        target,
                        subscription);

                final MethodHandle factory = site.getTarget();
                runnable = (Runnable) factory.bindTo(plugin).invokeExact();
            } catch (Throwable e) {
                log.warn("Unable to create lambda for method {}", method, e);
            }

            ScheduledMethod scheduledMethod = new ScheduledMethod(schedule, method, plugin, runnable);
            log.debug("Scheduled task {}", scheduledMethod);

            scheduler.addScheduledMethod(scheduledMethod);
        }
    }

    private void unschedule(Plugin plugin) {
        List<ScheduledMethod> methods = new ArrayList<>(scheduler.getScheduledMethods());

        for (ScheduledMethod method : methods) {
            if (method.getObject() != plugin) {
                continue;
            }

            log.debug("Removing scheduled task {}", method);
            scheduler.removeScheduledMethod(method);
        }
    }

    /**
     * Topologically sort a graph. Uses Kahn's algorithm.
     *
     * @param graph - A directed graph
     * @param <T>   - The type of the item contained in the nodes of the graph
     * @return - A topologically sorted list corresponding to graph.
     * <p>
     * Multiple invocations with the same arguments may return lists that are not equal.
     */
    @VisibleForTesting
    static <T> List<T> topologicalSort(Graph<T> graph) {
        MutableGraph<T> graphCopy = Graphs.copyOf(graph);
        List<T> l = new ArrayList<>();
        Set<T> s = graphCopy.nodes().stream()
                .filter(node -> graphCopy.inDegree(node) == 0)
                .collect(Collectors.toSet());
        while (!s.isEmpty()) {
            Iterator<T> it = s.iterator();
            T n = it.next();
            it.remove();

            l.add(n);

            for (T m : new HashSet<>(graphCopy.successors(n))) {
                graphCopy.removeEdge(n, m);
                if (graphCopy.inDegree(m) == 0) {
                    s.add(m);
                }
            }
        }
        if (!graphCopy.edges().isEmpty()) {
            throw new RuntimeException("Graph has at least one cycle");
        }
        return l;
    }

    public List<Plugin> conflictsForPlugin(Plugin plugin) {
        Set<String> conflicts;
        {
            PluginDescriptor desc = plugin.getClass().getAnnotation(PluginDescriptor.class);
            conflicts = new HashSet<>(Arrays.asList(desc.conflicts()));
            conflicts.add(desc.name());
        }

        return plugins.stream()
                .filter(p ->
                {
                    if (p == plugin) {
                        return false;
                    }

                    PluginDescriptor desc = p.getClass().getAnnotation(PluginDescriptor.class);
                    if (conflicts.contains(desc.name())) {
                        return true;
                    }

                    for (String conflict : desc.conflicts()) {
                        if (conflicts.contains(conflict)) {
                            return true;
                        }
                    }

                    return false;
                })
                .collect(Collectors.toList());
    }
}
