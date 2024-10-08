package net.runelite.client.plugins.microbot.sideloading;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.graph.Graph;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.Graphs;
import com.google.common.graph.MutableGraph;
import com.google.common.reflect.ClassPath;
import com.google.inject.Binder;
import com.google.inject.CreationException;
import com.google.inject.Injector;
import com.google.inject.Module;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.ExternalPluginsChanged;
import net.runelite.client.plugins.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.security.CodeSource;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static java.nio.file.StandardWatchEventKinds.*;

@Singleton
@Slf4j
public class MicrobotPluginManager {

    private final PluginManager pluginManager;
	private final EventBus eventBus;
    private static final File MICROBOT_PLUGINS = new File(RuneLite.RUNELITE_DIR, "microbot-plugins");
    private final ScheduledExecutorService scheduledExecutorService;
    private WatchService watchService;

    @Inject
    MicrobotPluginManager(
        PluginManager pluginManager,
		EventBus eventBus,
        ScheduledExecutorService scheduledExecutorService
    ) {
        this.pluginManager = pluginManager;
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            MICROBOT_PLUGINS.toPath().register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        } catch (IOException e) {
            e.printStackTrace();
        }
        this.scheduledExecutorService = scheduledExecutorService;
        this.eventBus = eventBus;
    }

    public static File[] createSideloadingFolder() {
        if (!Files.exists(MICROBOT_PLUGINS.toPath())) {
            try {
                Files.createDirectories(MICROBOT_PLUGINS.toPath());
                System.out.println("Directory for sideloading was created successfully.");
                return MICROBOT_PLUGINS.listFiles();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return MICROBOT_PLUGINS.listFiles();
    }

    public void loadSideLoadPlugins() {
        File[] files = createSideloadingFolder();
        if (files == null)
        {
            return;
        }

        for (File f : files)
        {
            if (f.getName().endsWith(".jar"))
            {
                System.out.println("Side-loading plugin " + f.getName());

                try
                {
                    MicrobotPluginClassLoader classLoader = new MicrobotPluginClassLoader(f, getClass().getClassLoader());

                    List<Class<?>> plugins = ClassPath.from(classLoader)
                            .getAllClasses()
                            .stream()
                            .map(ClassPath.ClassInfo::load)
                            .collect(Collectors.toList());

                    loadPlugins(plugins, null);
                }
                catch (PluginInstantiationException | IOException ex)
                {
                    System.out.println("error sideloading plugin " + ex);
                }
            }
        }
        startWatching();
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

            graph.addNode((Class<Plugin>) clazz);
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
                plugin = instantiate(pluginManager.getPlugins(), (Class<Plugin>) pluginClazz);
                log.info("Microbot plugin sideloaded " + plugin.getName());
                newPlugins.add(plugin);
                pluginManager.addPlugin(plugin);
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

    private Plugin instantiate(Collection<Plugin> scannedPlugins, Class<Plugin> clazz) throws PluginInstantiationException {
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
            Injector parent = RuneLite.getInjector();

            if (deps.size() > 1) {
                List<Module> modules = new ArrayList<>(deps.size());
                for (Plugin p : deps) {
                    // Create a module for each dependency
                    Module module = (Binder binder) ->
                    {
                        binder.bind((Class<Plugin>) p.getClass()).toInstance(p);
                        binder.install(p);
                    };
                    modules.add(module);
                }

                // Create a parent injector containing all of the dependencies
                parent = parent.createChildInjector(modules);
            } else if (!deps.isEmpty()) {
                // With only one dependency we can simply use its injector
                parent = deps.get(0).getInjector();
            }

            // Create injector for the module
            Module pluginModule = (Binder binder) ->
            {
                // Since the plugin itself is a module, it won't bind itself, so we'll bind it here
                binder.bind(clazz).toInstance(plugin);
                binder.install(plugin);
            };
            Injector pluginInjector = parent.createChildInjector(pluginModule);
            plugin.setInjector(pluginInjector);
        } catch (CreationException ex) {
            throw new PluginInstantiationException(ex);
        }

        log.debug("Loaded plugin {}", clazz.getSimpleName());
        return plugin;
    }

    public void startWatching() {
        scheduledExecutorService.scheduleAtFixedRate(() -> {
            try {
                WatchKey key;
                boolean changeOccurred = false;
                while ((key = watchService.poll()) != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        Path fileName = (Path) event.context();
                        if (fileName.toString().endsWith(".jar")) {
                            File pluginFile = MICROBOT_PLUGINS.toPath().resolve(fileName).toFile();

                            if (kind == ENTRY_CREATE || kind == ENTRY_MODIFY) {
                                // Stop the plugin if it's running, then reload it
                                changeOccurred = true;
                                reloadPlugins(pluginFile);
                            } else if (kind == ENTRY_DELETE) {
                                // Unload the plugin if it's deleted
                                changeOccurred = true;
                                unloadPlugins(pluginFile);
                            }
                        }
                    }
                    key.reset();
                }
                if (changeOccurred)
                    eventBus.post(new ExternalPluginsChanged());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 10, 5, TimeUnit.SECONDS);
    }
    /**
     * Reload a single plugin by stopping and starting it again.
     *
     * @param pluginFile The plugin file to reload.
     */
    private void reloadPlugins(File pluginFile) {
        System.out.println("Detected changes on file "+pluginFile.getName());
        SwingUtilities.invokeLater(() -> {
            List<Plugin> plugins = findPluginByFile(pluginFile);
            List<String> enabledPlugins = new ArrayList<>();
            for (var plugin : plugins) {
                if (pluginManager.isPluginEnabled(plugin)) enabledPlugins.add(plugin.getName());
                stopAndRemovePlugin(plugin);
            }

            try {
                List<Plugin> newPlugins = loadPluginsFromFile(pluginFile);
                for (var p : newPlugins) {
                    if (enabledPlugins.contains(p.getName())) {
                        pluginManager.setPluginEnabled(p, true);
                        pluginManager.startPlugin(p);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * Unload a single plugin by stopping and removing it.
     *
     * @param pluginFile The plugin file to unload.
     */
    private void unloadPlugins(File pluginFile) {
        SwingUtilities.invokeLater(() -> {
            List<Plugin> plugins = findPluginByFile(pluginFile);
            for (var plugin : plugins) stopAndRemovePlugin(plugin);
        });
    }

    /**
     * Find a loaded plugin by matching its corresponding jar file.
     *
     * @param pluginFile The jar file of the plugin.
     * @return The loaded plugin, or null if not found.
     */
    private List<Plugin> findPluginByFile(File pluginFile) {
        return pluginManager.getPlugins().stream()
            .filter(p -> getJarFile(p).equals(pluginFile))
            .collect(Collectors.toList());
    }

    private static File getJarFile(Plugin plugin) {
        CodeSource codeSource = plugin.getClass().getProtectionDomain().getCodeSource();

        if (codeSource != null && codeSource.getLocation() != null) {
            try {
                return new File(codeSource.getLocation().toURI());
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    /**
     * Stop and remove the given plugin from the plugin manager.
     *
     * @param plugin The plugin to stop and remove.
     */
    private void stopAndRemovePlugin(Plugin plugin) {
        try {
            System.out.println("Removing plugin "+plugin.getName());
            pluginManager.setPluginEnabled(plugin, false);
            pluginManager.stopPlugin(plugin);
        } catch (PluginInstantiationException e) {
            e.printStackTrace();
        }
        pluginManager.remove(plugin);
    }

    /**
     * Load a plugin from its jar file.
     *
     * @param pluginFile The jar file of the plugin.
     * @return A list of loaded plugins.
     * @throws IOException If the plugin file cannot be read.
     * @throws PluginInstantiationException
     */
    private List<Plugin> loadPluginsFromFile(File pluginFile) throws IOException, PluginInstantiationException {
        ClassLoader classLoader = new MicrobotPluginClassLoader(pluginFile, getClass().getClassLoader());
        List<Class<?>> pluginClasses = ClassPath.from(classLoader).getAllClasses().stream()
            .map(classInfo -> {
                try {
                    return classLoader.loadClass(classInfo.getName());
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
            })
            .filter(cls -> Plugin.class.isAssignableFrom(cls))
            .collect(Collectors.toList());


        List<Plugin> plugins = loadPlugins(pluginClasses, null);
        return plugins;
    }
}
