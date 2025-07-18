package net.minecraft.launchwrapper;

import java.io.*;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;

public class LaunchClassLoader extends URLClassLoader {
    public static final int BUFFER_SIZE = 1 << 12;
    private List<URL> sources;
    private ClassLoader parent = getClass().getClassLoader();

    private List<IClassTransformer> transformers = new ArrayList<>(2);
    private Map<String, Class<?>> cachedClasses = new ConcurrentHashMap<>();
    private Set<String> invalidClasses = new HashSet<>(1000);

    private Set<String> classLoaderExceptions = new HashSet<>();
    private Set<String> transformerExceptions = new HashSet<>();
    private Map<String, byte[]> resourceCache = new ConcurrentHashMap<>(1000);
    private Set<String> negativeResourceCache = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private Map<Package, Manifest> packageManifests = new ConcurrentHashMap<>(); // dummy for fastcraft
    private static final Manifest EMPTY = new Manifest(); // dummy for fastcraft

    private IClassNameTransformer renameTransformer;

    private final ThreadLocal<byte[]> loadBuffer = new ThreadLocal<>();

    private static final String[] RESERVED_NAMES = {
        "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1",
        "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    };

    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("legacy.debugClassLoading", "false"));
    private static final boolean DEBUG_FINER =
            DEBUG && Boolean.parseBoolean(System.getProperty("legacy.debugClassLoadingFiner", "false"));
    private static final boolean DEBUG_SAVE =
            DEBUG && Boolean.parseBoolean(System.getProperty("legacy.debugClassLoadingSave", "false"));
    private static final boolean DEBUG_SLIM =
            DEBUG && Boolean.parseBoolean(System.getProperty("legacy.debugClassLoadingSlim", "false"));
    private static File tempFolder = null;
    // HybridFix start - Allow child loading
    private final List<ClassLoader> children = new ArrayList<>();
    private ClassLoader from = null;
    private static final Method MD_FIND_CLASS;
    public static boolean childLoadingEnabled = false;

    static {
        Method mdFind = null;
        try {
            mdFind = ClassLoader.class.getDeclaredMethod("findClass", String.class);
            try {
                mdFind.setAccessible(true);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        MD_FIND_CLASS = mdFind;
    }

    public void addChild(ClassLoader child) {
        children.add(child);
    }
    // HybridFix end - Allow child loading

    public LaunchClassLoader(URL[] sources) {
        super(sources, null);
        this.sources = new ArrayList<>(Arrays.asList(sources));

        // classloader exclusions
        addClassLoaderExclusion("java.");
        addClassLoaderExclusion("sun.");
        addClassLoaderExclusion("org.apache.logging.");
        addClassLoaderExclusion("net.minecraft.launchwrapper.");

        // transformer exclusions
        addTransformerExclusion("javax.");
        addTransformerExclusion("argo.");
        addTransformerExclusion("org.objectweb.asm.");
        addTransformerExclusion("com.google.common.");
        addTransformerExclusion("org.bouncycastle.");
        addTransformerExclusion("net.minecraft.launchwrapper.injector.");

        if (DEBUG_SAVE) {
            int x = 1;
            tempFolder = new File(Launch.minecraftHome, "CLASSLOADER_TEMP");
            while (tempFolder.exists() && x <= 10) {
                tempFolder = new File(Launch.minecraftHome, "CLASSLOADER_TEMP" + x++);
            }

            if (tempFolder.exists()) {
                LogWrapper.info("DEBUG_SAVE enabled, but 10 temp directories already exist, clean them and try again.");
                tempFolder = null;
            } else {
                LogWrapper.warning(
                        "DEBUG_SAVE Enabled, saving all classes to \"{}\"",
                        tempFolder.getAbsolutePath().replace('\\', '/'));
                tempFolder.mkdirs();
            }
        }
    }

    public void registerTransformer(String transformerClassName) {
        try {
            IClassTransformer transformer = (IClassTransformer)
                    loadClass(transformerClassName).getConstructor().newInstance();
            transformers.add(transformer);
            if (transformer instanceof IClassNameTransformer && renameTransformer == null) {
                renameTransformer = (IClassNameTransformer) transformer;
            }
            if (DEBUG) {
                LogWrapper.info("Registered transformer {}", transformerClassName);
            }
        } catch (Exception e) {
            LogWrapper.log(
                    Level.ERROR,
                    e,
                    "A critical problem occurred registering the ASM transformer class " + transformerClassName);
        }
    }

    @Override
    public Class<?> findClass(final String name) throws ClassNotFoundException {
        if (this.equals(from)) return null; // HybridFix - Allow child loading - prevent infinite loop
        if (invalidClasses.contains(name)) {
            throw new ClassNotFoundException(name);
        }

        for (final String exception : classLoaderExceptions) {
            if (name.startsWith(exception)) {
                return parent.loadClass(name);
            }
        }

        if (cachedClasses.containsKey(name)) {
            return cachedClasses.get(name);
        }

        for (final String exception : transformerExceptions) {
            if (name.startsWith(exception)) {
                try {
                    final Class<?> clazz = super.findClass(name);
                    cachedClasses.put(name, clazz);
                    return clazz;
                } catch (ClassNotFoundException e) {
                    invalidClasses.add(name);
                    throw e;
                }
            }
        }

        try {
            final String transformedName = transformName(name);
            if (cachedClasses.containsKey(transformedName)) {
                return cachedClasses.get(transformedName);
            }

            final String untransformedName = untransformName(name);

            final int lastDot = untransformedName.lastIndexOf('.');
            final String packageName = lastDot == -1 ? "" : untransformedName.substring(0, lastDot);
            final String fileName = untransformedName.replace('.', '/').concat(".class");
            URLConnection urlConnection = findCodeSourceConnectionFor(fileName);

            CodeSigner[] signers = null;

            if (lastDot > -1 && !untransformedName.startsWith("net.minecraft.")) {
                if (urlConnection instanceof JarURLConnection) {
                    final JarURLConnection jarURLConnection = (JarURLConnection) urlConnection;
                    final JarFile jarFile = jarURLConnection.getJarFile();

                    if (jarFile != null && jarFile.getManifest() != null) {
                        final Manifest manifest = jarFile.getManifest();
                        final JarEntry entry = jarFile.getJarEntry(fileName);

                        Package pkg = getPackage(packageName);
                        getClassBytes(untransformedName);
                        signers = entry.getCodeSigners();
                        if (pkg == null) {
                            pkg = definePackage(packageName, manifest, jarURLConnection.getJarFileURL());
                        } else {
                            if (pkg.isSealed() && !pkg.isSealed(jarURLConnection.getJarFileURL())) {
                                LogWrapper.severe(
                                        "The jar file {} is trying to seal already secured path {}",
                                        jarFile.getName(),
                                        packageName);
                            } else if (isSealed(packageName, manifest)) {
                                LogWrapper.severe(
                                        "The jar file {} has a security seal for path {}, but that path is defined and not secure",
                                        jarFile.getName(),
                                        packageName);
                            }
                        }
                    }
                } else {
                    Package pkg = getPackage(packageName);
                    if (pkg == null) {
                        pkg = definePackage(packageName, null, null, null, null, null, null, null);
                    } else if (pkg.isSealed()) {
                        LogWrapper.severe(
                                "The URL {} is defining elements for sealed path {}",
                                urlConnection.getURL(),
                                packageName);
                    }
                }
            }

            final byte[] transformedClass =
                    runTransformers(untransformedName, transformedName, getClassBytes(untransformedName));

            if (transformedClass == null) {
                final String msg =
                        "Class " + untransformedName + "|" + transformedName + " is null after running transformers";
                final NullPointerException npe = new NullPointerException(msg);
                if (DEBUG) {
                    System.err.println(msg);
                    npe.printStackTrace();
                }
                throw npe;
            }

            final CodeSource codeSource =
                    urlConnection == null ? null : new CodeSource(urlConnection.getURL(), signers);
            final Class<?> clazz =
                    defineClass(transformedName, transformedClass, 0, transformedClass.length, codeSource);
            cachedClasses.put(transformedName, clazz);
            return clazz;
        } catch (Throwable e) {
            // HybridFix start - Allow child loading
            boolean hasChildren = !children.isEmpty();
            if (childLoadingEnabled && hasChildren) {
                from = this;
                for (ClassLoader child : children) {
                    final String transformedName = transformName(name);

                    try {
                        Class<?> classe = (Class<?>) MD_FIND_CLASS.invoke(child, transformedName);
                        if (classe != null) {
                            cachedClasses.put(name, classe);
                            from = null;
                            return classe;
                        }
                    } catch (Exception e1) {
                        from = null;
                    }

                }
                from = null;
            }
            if (childLoadingEnabled || !hasChildren) invalidClasses.add(name);
            // HybridFix end - Allow child loading
            if (DEBUG) {
                LogWrapper.log(Level.TRACE, e, "Exception encountered attempting classloading of " + name);
                LogManager.getLogger("LaunchWrapper")
                        .log(Level.ERROR, "Exception encountered attempting classloading of " + name, e);
            }
            throw new ClassNotFoundException(name, e);
        }
    }

    private void saveTransformedClass(final byte[] data, final String transformedName) {
        if (tempFolder == null || data == null || transformedName == null) {
            return;
        }

        final File outFile = new File(tempFolder, transformedName.replace('.', File.separatorChar) + ".class");
        final File outDir = outFile.getParentFile();

        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        if (outFile.exists()) {
            outFile.delete();
        }

        try {
            LogWrapper.fine(
                    "Saving transformed class \"{}\" to \"{}\"",
                    transformedName,
                    outFile.getAbsolutePath().replace('\\', '/'));

            try (OutputStream output = new FileOutputStream(outFile)) {
                output.write(data);
            }
        } catch (IOException ex) {
            LogWrapper.log(Level.WARN, ex, "Could not save transformed class \"" + transformedName + "\"");
        }
    }

    private String untransformName(final String name) {
        if (renameTransformer != null) {
            return renameTransformer.unmapClassName(name);
        }

        return name;
    }

    private String transformName(final String name) {
        if (renameTransformer != null) {
            return renameTransformer.remapClassName(name);
        }

        return name;
    }

    private boolean isSealed(final String path, final Manifest manifest) {
        Attributes attributes = manifest.getAttributes(path);
        String sealed = null;
        if (attributes != null) {
            sealed = attributes.getValue(Name.SEALED);
        }

        if (sealed == null) {
            attributes = manifest.getMainAttributes();
            if (attributes != null) {
                sealed = attributes.getValue(Name.SEALED);
            }
        }
        return "true".equalsIgnoreCase(sealed);
    }

    private URLConnection findCodeSourceConnectionFor(final String name) {
        final URL resource = findResource(name);
        if (resource != null) {
            try {
                return resource.openConnection();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return null;
    }

    private static byte[] EMPTY_BYTE_ARRAY = new byte[0];

    private byte[] runTransformers(final String name, final String transformedName, byte[] basicClass) {
        MessageDigest hashers = null;
        if (DEBUG_SAVE) {
            try {
                hashers = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                // no-op
            }
        }
        if (DEBUG_FINER) {
            byte[] preTransformHash = EMPTY_BYTE_ARRAY;
            if (hashers != null && basicClass != null) {
                preTransformHash = hashers.digest(basicClass);
                if (!DEBUG_SLIM || (transformers != null && !transformers.isEmpty())) {
                    saveTransformedClass(basicClass, transformedName + "_000_pretransform");
                }
            }
            LogWrapper.finest(
                    "Beginning transform of [{} ({})] Start Length: {}",
                    name,
                    transformedName,
                    (basicClass == null ? 0 : basicClass.length));
            int transformerId = 1;
            for (final IClassTransformer transformer : transformers) {
                final String transName = transformer.getClass().getName();
                LogWrapper.finest(
                        "Before Transformer [{} ({})] {}: {}",
                        name,
                        transformedName,
                        transName,
                        (basicClass == null ? 0 : basicClass.length));
                basicClass = transformer.transform(name, transformedName, basicClass);
                LogWrapper.finest(
                        "After  Transformer [{} ({})] {}: {}",
                        name,
                        transformedName,
                        transName,
                        (basicClass == null ? 0 : basicClass.length));
                if (hashers != null && basicClass != null) {
                    hashers.reset();
                    byte[] postTransformHash = hashers.digest(basicClass);
                    if (!Arrays.equals(preTransformHash, postTransformHash)) {
                        preTransformHash = postTransformHash;
                        saveTransformedClass(
                                basicClass,
                                transformedName
                                        + String.format("_%03d_%s", transformerId, transName.replace('.', '_')));
                    }
                }
                transformerId++;
            }
            LogWrapper.finest(
                    "Ending transform of [{} ({})] Start Length: {}",
                    name,
                    transformedName,
                    (basicClass == null ? 0 : basicClass.length));
        } else {
            byte[] originalClass = null;
            if (DEBUG_SLIM) {
                originalClass = Arrays.copyOf(basicClass, basicClass.length);
            }
            for (final IClassTransformer transformer : transformers) {
                basicClass = transformer.transform(name, transformedName, basicClass);
            }
            if (DEBUG_SAVE) {
                if (!DEBUG_SLIM || !Arrays.equals(originalClass, basicClass)) {
                    saveTransformedClass(basicClass, transformedName);
                }
            }
        }
        return basicClass;
    }

    @Override
    public void addURL(final URL url) {
        super.addURL(url);
        sources.add(url);
    }

    public List<URL> getSources() {
        return sources;
    }

    private byte[] readFully(InputStream stream) {
        try {
            byte[] buffer = getOrCreateBuffer();

            int read;
            int totalLength = 0;
            while ((read = stream.read(buffer, totalLength, buffer.length - totalLength)) != -1) {
                totalLength += read;

                // Extend our buffer
                if (totalLength >= buffer.length - 1) {
                    byte[] newBuffer = new byte[buffer.length + BUFFER_SIZE];
                    System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
                    buffer = newBuffer;
                }
            }

            final byte[] result = new byte[totalLength];
            System.arraycopy(buffer, 0, result, 0, totalLength);
            return result;
        } catch (Throwable t) {
            LogWrapper.log(Level.WARN, t, "Problem loading class");
            return new byte[0];
        }
    }

    private byte[] getOrCreateBuffer() {
        byte[] buffer = loadBuffer.get();
        if (buffer == null) {
            loadBuffer.set(new byte[BUFFER_SIZE]);
            buffer = loadBuffer.get();
        }
        return buffer;
    }

    public List<IClassTransformer> getTransformers() {
        return Collections.unmodifiableList(transformers);
    }

    public void addClassLoaderExclusion(String toExclude) {
        classLoaderExceptions.add(toExclude);
    }

    public void addTransformerExclusion(String toExclude) {
        transformerExceptions.add(toExclude);
    }

    public byte[] getClassBytes(String name) throws IOException {
        if (negativeResourceCache.contains(name)) {
            return null;
        } else if (resourceCache.containsKey(name)) {
            return resourceCache.get(name);
        }
        if (name.indexOf('.') == -1) {
            for (final String reservedName : RESERVED_NAMES) {
                if (name.toUpperCase(Locale.ENGLISH).startsWith(reservedName)) {
                    final byte[] data = getClassBytes("_" + name);
                    if (data != null) {
                        resourceCache.put(name, data);
                        return data;
                    }
                }
            }
        }

        InputStream classStream = null;
        try {
            final String resourcePath = name.replace('.', '/').concat(".class");
            final URL classResource = findResource(resourcePath);

            if (classResource == null) {
                if (DEBUG) LogWrapper.finest("Failed to find class resource {}", resourcePath);
                negativeResourceCache.add(name);
                return null;
            }
            classStream = classResource.openStream();

            if (DEBUG) LogWrapper.finest("Loading class {} from resource {}", name, classResource.toString());
            final byte[] data = readFully(classStream);
            resourceCache.put(name, data);
            return data;
        } finally {
            closeSilently(classStream);
        }
    }

    private static void closeSilently(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    public void clearNegativeEntries(Set<String> entriesToClear) {
        negativeResourceCache.removeAll(entriesToClear);
    }
}
