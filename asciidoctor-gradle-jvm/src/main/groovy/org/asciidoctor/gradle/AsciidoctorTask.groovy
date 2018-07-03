/*
 * Copyright 2013-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.asciidoctor.gradle

import org.asciidoctor.gradle.compat.AsciidoctorBackend
import org.asciidoctor.gradle.compat.AsciidoctorProxy
import org.asciidoctor.gradle.compat.AsciidoctorProxyCacheKey
import org.asciidoctor.gradle.compat.AsciidoctorProxyImpl
import org.asciidoctor.gradle.compat.ResourceCopyProxy
import org.asciidoctor.gradle.compat.ResourceCopyProxyImpl
import org.asciidoctor.gradle.internal.AsciidoctorUtils
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.InvalidUserDataException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.CopySpec
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTree
import org.gradle.api.internal.file.copy.CopySpecInternal
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectories
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.util.PatternSet
import org.gradle.internal.FileUtils
import org.gradle.util.CollectionUtils

import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

/**
 * @author Noam Tenne
 * @author Andres Almiray
 * @author Tom Bujok
 * @author Lukasz Pielak
 * @author Dmitri Vyazelenko
 * @author Benjamin Muschko
 * @author Dan Allen
 * @author Rob Winch
 * @author Stefan Schlott
 * @author Stephan Classen
 * @author Marcus Fihlon
 * @author Schalk W. Cronjé
 * @author Robert Panzer
 */
@Deprecated
@SuppressWarnings(['MethodCount', 'Instanceof'])
class AsciidoctorTask extends DefaultTask {
    private static final boolean IS_WINDOWS = System.getProperty('os.name').contains('Windows')
    private static final String PATH_SEPARATOR = System.getProperty('path.separator')
    private static final String DOUBLE_BACKLASH = '\\\\'
    private static final String BACKLASH = '\\'
    private static final String SAFE_MODE_CLASSNAME = 'org.asciidoctor.gradle.base.SafeMode'
    private static
    final String MIGRATE_GEMS_MSG = 'When upgrading GEMs, \'requires\' will need to be set via the asciidoctorj project and task extensions. Use  setGemPaths method in extension(s) to set GEM paths.'

    private static final String DEFAULT_BACKEND = AsciidoctorBackend.HTML5.id
    public static final String ASCIIDOCTOR_FACTORY_CLASSNAME = 'org.asciidoctor.Asciidoctor$Factory'

    private boolean baseDirSetToNull
    private Object outDir
    private Object srcDir
    private final List<Object> gemPaths = []
    private Set<String> backends
    private Set<String> requires
    private Map opts = [:]
    private Map attrs = [:]
    private PatternSet sourceDocumentPattern
    private CopySpec resourceCopy
    private static ClassLoader cl
    private boolean separateOutputDirs = true

    private final static Map<AsciidoctorProxyCacheKey, AsciidoctorProxy> ASCIIDOCTORS = [:]
    private final static Lock EXEC_LOCK = new ReentrantLock()

    private final Set<String> migrationMessages = []

    /** If set to true each backend will be output to a separate subfolder below {@code outputDir}
     * @since 1.5.1
     */
    @Input
    boolean getSeparateOutputDirs() {
        migrationMessage('seperateOutputDirs', 'Separate output directories are now configured via outputOptions.separateOutputDirs')
        this.separateOutputDirs
    }

    void setSeparateOutputDirs(boolean v) {
        this.separateOutputDirs = v
    }

    @Optional
    @InputDirectory
    File baseDir

    /** Logs documents as they are converted
     *
     */
    @Optional
    @Internal
    boolean logDocuments = false

    /**
     * Stores the extensionRegistry defined in the runConfiguration phase
     * to register them in the execution phase.
     */
    @Internal
    List<Object> asciidoctorExtensions = []

    @Internal
    AsciidoctorProxy asciidoctor

    @Internal
    ResourceCopyProxy resourceCopyProxy

    @Optional
    @InputFiles
    Configuration classpath

    AsciidoctorTask() {
        srcDir = project.file('src/docs/asciidoc')
    }

    /** Returns all of the Asciidoctor options
     *
     */
    @Optional
    @Input
    Map getOptions() { this.opts }

    /** Apply a new set of Asciidoctor options, clearing any options previously set.
     *
     * For backwards compatibility it is still possible to replace attributes via this call. However the
     * use of {@link #setAttributes(java.util.Map)} and {@link #attributes(java.util.Map)} are the now
     * correct way of working with attributes
     *
     * @param m Map with new options
     */
    @SuppressWarnings('DuplicateStringLiteral')
    void setOptions(Map m) {
        if (!m) return // null check
        if (m.containsKey('attributes')) {
            migrationMessage('setOptions', """pay attention to these attributes found in options. Currently attributes will be replaced due to assignment. Please use one of the following instead instead as current behaviour will no longer be available when upgrading:
   - 'attributes'
   - 'project.asciidoctorj.attributes'
   - '${name}.asciidoctorj.attributes'
""")
            attrs = coerceLegacyAttributeFormats(m.attributes)
            m.remove('attributes')
        }
        this.opts = m
    }

    /** Appends a new set of Asciidoctor options.
     *
     * For backwards compatibility it is still possible to append attributes via this call. However the
     * use of {@link #setAttributes(java.util.Map)} and {@link #attributes(java.util.Map)} are the now
     * correct way of working with attributes
     *
     * @param m Map with new options
     * @since 1.5.1
     */
    @SuppressWarnings('DuplicateStringLiteral')
    void options(Map m) {
        if (!m) return // null check
        if (m.containsKey('attributes')) {
            migrationMessage('setOptions', """pay attention to these attributes found in options. Currently these attributes added to exustign attributes. Please use one of the following instead instead as current behaviour will no longer be available when upgrading:
   - 'attributes'
   - 'project.asciidoctorj.attributes'
   - '${name}.asciidoctorj.attributes'
""")
            attributes coerceLegacyAttributeFormats(m.attributes)
            m.remove('attributes')
        }
        this.opts += m
    }

    /** Returns the current set of Asciidoctor attributes
     *
     * @since 1.5.1
     */
    @Optional
    @Input
    Map getAttributes() { this.attrs }

    /** Applies a new set of Asciidoctor attributes, clearing any previously set
     *
     * @param m New map of attributes
     * @since 1.5.1
     */
    void setAttributes(Map m) {
        migrationMessage('setAttributes', 'the behaviour of setAttributes will change when upgrading. You may decide to use the asciidoctorj.setAttributes that exists as a project or task extension instead')
        if (m) {
            this.attrs = m
        } else {
            this.attrs.clear()
        }
    }

    /** Appends a set of Asciidoctor attributes.
     *
     * @param o a Map, List or a literal (String) definition
     * @since 1.5.1
     */
    void attributes(Object... o) {
        if (!o) {
            this.attrs.clear()
            return
        }
        for (input in o) {
            this.attrs += coerceLegacyAttributeFormats(input)
        }
    }

    /** Returns the set of  Ruby modules to be included.
     *
     * @since 1.5.0
     */
    @Optional
    @Input
    Set<String> getRequires() { this.requires }

    /** Applies a new set of Ruby modules to be included, clearing any previous set.
     *
     * @param b One or more ruby modules to be included
     * @since 1.5.0
     */
    @Deprecated
    void setRequires(Object... b) {
        migrationMessage('setRequires', MIGRATE_GEMS_MSG)
        this.requires?.clear()
        requires(b)
    }

    /** Appends new set of Ruby modules to be included.
     *
     * @param b One or more ruby modules to be included
     * @since 1.5.1
     */
    @SuppressWarnings('ConfusingMethodName')
    @Deprecated
    void requires(Object... b) {
        migrationMessage('requires', MIGRATE_GEMS_MSG)
        if (this.requires == null) {
            this.requires = []
        }
        if (!b) return // null check
        this.requires.addAll(CollectionUtils.stringize(b as List))
    }

    /** Returns the current set of Asciidoctor backends that will be used for document generation
     *
     * @since 0.7.1
     * @deprecated
     */
    @Optional
    @Input
    Set<String> getBackends() {
        migrationMessage('getBackends', 'Use outputOptions.getBackends')
        this.backends
    }

    /** Applies a new set of Asciidoctor backends that will be used for document generation clearing any
     * previous backends
     *
     * @param b List of backends. Each item must be convertible to string.
     *
     * @since 0.7.1
     */
    void setBackends(Object... b) {
        migrationMessage('setBackends', 'Use outputOoptons.setBackends')
        this.backends?.clear()
        backends(b)
    }

    /** Appends additional Asciidoctor backends that will be used for document generation.
     *
     * @param b List of backends. Each item must be convertible to string.
     *
     * @since 1.5.1
     */
    @SuppressWarnings('ConfusingMethodName')
    void backends(Object... b) {
        migrationMessage('backends', 'Use outputOoptons.setbackends')
        if (this.backends == null) {
            this.backends = []
        }
        if (!b) return // null check
        this.backends.addAll(CollectionUtils.stringize(b as List))
    }

    /** Defines extensionRegistry. The given parameters should
     * either contain Asciidoctor Groovy DSL closures or files
     * with content conforming to the Asciidoctor Groovy DSL.
     */
    @Deprecated
    void extensions(Object... exts) {
        migrationMessage('extensions', 'Extensions will need to be set via the asciidoctorj project and task extensions')
        if (!exts) return // null check
        asciidoctorExtensions.addAll(exts as List)
    }

    /** Sets a new gemPath to be used
     *
     * @param f A path object can be be converted with {@code project.file}.
     * @since 1.5.1
     */
    @SuppressWarnings('ConfusingMethodName')
    @Deprecated
    void gemPath(Object... f) {
        migrationMessage('gemPath', 'GEM paths will need to be set via the asciidoctorj project and task extensions using the gemPaths method')
        if (!f) return // null check
        this.gemPaths.addAll(f as List)
    }

    /** Sets a new list of GEM paths to be used.
     *
     * @param f A {@code File} object pointing to list of installed GEMs
     * @since 1.5.0
     */
    @Deprecated
    void setGemPath(Object... f) {
        migrationMessage('setGemPath(Object...)', MIGRATE_GEMS_MSG)
        this.gemPaths.clear()
        if (!f) return // null check
        this.gemPaths.addAll(f as List)
    }

    /** Assigns a single string to a GEM path, scanning it for concatenated GEM Paths, separated by the platform
     * separator. This utility is only for backwards compatibility
     *
     * @param s
     */
    @SuppressWarnings('UnnecessarySetter')
    void setGemPath(Object path) {
        migrationMessage('setGemPath(Object)', MIGRATE_GEMS_MSG)
        this.gemPaths.clear()
        if (path instanceof CharSequence) {
            this.gemPaths.addAll(setGemPath(path.split(PATH_SEPARATOR)))
        } else if (path) {
            this.gemPaths.addAll(path)
        }
    }

    /** Returns the list of paths to be used for {@code GEM_HOME}
     *
     * @since 1.5.0
     */
    @Optional
    @InputFiles
    FileCollection getGemPath() {
        project.files(this.gemPaths)
    }

    /** Returns the list of paths to be used for GEM installations in a format that is suitable for assignment to {@code GEM_HOME}
     *
     * Calling this will cause gemPath to be resolved immediately.
     * @since 1.5.1
     */
    @Optional
    @InputDirectory
    String asGemPath() {
        gemPath.files*.toString().join(PATH_SEPARATOR)
    }

    /** Sets the new Asciidoctor parent source directory.
     *
     * @param f An object convertible via {@code project.file}
     * @since 1.5.1
     */
    void sourceDir(Object f) {
        this.srcDir = f
    }

    /** Sets the new Asciidoctor parent source directory.
     *
     * @param f A {@code File} object pointing to the parent source directory
     */
    void setSourceDir(File f) {
        this.srcDir = f
    }

    /** Returns the parent directory for Asciidoctor source. Default is {@code src/asciidoc}.
     */
    @Optional
    @InputDirectory
    File getSourceDir() {
        project.file(srcDir)
    }

    /** Sets the new Asciidoctor parent output directory.
     *
     * @param f An object convertible via {@code project.file}
     * @since 1.5.1
     */
    void outputDir(Object f) {
        this.outDir = f
    }

    /** Sets the new Asciidoctor parent output directory.
     *
     * @param f A {@code File} object pointing to the parent output directory
     */
    void setOutputDir(File f) {
        this.outDir = f
    }

    /** Returns the current toplevel output directory
     *
     */
    @OutputDirectory
    File getOutputDir() {
        if (this.outDir == null) {
            this.outDir = new File(project.buildDir, 'asciidoc')
        }
        project.file(this.outDir)
    }

    void setBaseDir(File baseDir) {
        this.baseDir = baseDir
        baseDirSetToNull = baseDir == null
    }

    /** Returns a list of all output directories.
     * @since 1.5.1
     */
    @OutputDirectories
    Set<File> getOutputDirectories() {
        if (separateOutputDirs) {
            backends.collect { new File(outputDir, it) } as Set
        } else {
            [outputDir] as Set
        }
    }

    /** Returns a FileTree containing all of the source documents
     *
     * @return If {@code sources} was never called then all asciidoc source files below {@code sourceDir} will
     * be included
     * @since 1.5.1
     */
    @InputFiles
    @SkipWhenEmpty
    FileTree getSourceFileTree() {
        project.fileTree(sourceDir).
            matching(this.sourceDocumentPattern ?: defaultSourceDocumentPattern)
    }

    /** Add patterns for source files or source files via a closure
     *
     * @param cfg PatternSet runConfiguration closure
     * @since 1.5.1
     */
    void sources(Closure cfg) {
        if (sourceDocumentPattern == null) {
            sourceDocumentPattern = new PatternSet()
        }
        def configuration = cfg.clone()
        configuration.delegate = sourceDocumentPattern
        configuration()
    }

    /** Add to the CopySpec for extra files. The destination of these files will always have a parent directory
     * of {@code outputDir} or {@code outputDir + backend}
     *
     * @param cfg CopySpec runConfiguration closure
     * @since 1.5.1
     */
    void resources(Closure cfg) {
        if (this.resourceCopy == null) {
            this.resourceCopy = project.copySpec(cfg)
        } else {
            def configuration = cfg.clone()
            configuration.delegate = this.resourceCopy
            configuration()
        }
    }

    /** The default PatternSet that will be used if {@code sources} was never called
     *
     * By default all *.adoc,*.ad,*.asc,*.asciidoc is included. Files beginning with underscore are excluded
     *
     * @since 1.5.1
     */
    @Internal
    PatternSet getDefaultSourceDocumentPattern() {
        PatternSet ps = new PatternSet()
        ps.include '**/*.adoc'
        ps.include '**/*.ad'
        ps.include '**/*.asc'
        ps.include '**/*.asciidoc'
        ps.exclude '**/_*'
    }

    /** The default CopySpec that will be used if {@code resources} was never called
     *
     * By default anything below {@code $sourceDir/images} will be included.
     *
     * @return A {@code CopySpec}, never null
     * @since 1.5.1
     */
    @Internal
    CopySpec getDefaultResourceCopySpec() {
        project.copySpec {
            from(sourceDir) {
                include 'images/**'
            }
        }
    }

    /** Gets the CopySpec for additional resources
     * If {@code resources} was never called, it will return a default CopySpec otherwise it will return the
     * one built up via successive calls to {@code resources}
     *
     * @return A {@code CopySpec}, never null
     * @since 1.5.1
     */
    @Internal
    CopySpec getResourceCopySpec() {
        this.resourceCopy ?: defaultResourceCopySpec
    }

    /** Gets the additional resources as a FileCollection.
     * If {@code resources} was never called, it will return the file collections as per default CopySpec otherwise it
     * will return the collections as built up via successive calls to {@code resources}
     *
     * @return A {@code FileCollection}, never null
     * @since 1.5.2
     */
    @InputFiles
    @SkipWhenEmpty
    @Optional
    FileCollection getResourceFileCollection() {
        (resourceCopySpec as CopySpecInternal).buildRootResolver().allSource
    }

    @TaskAction
    void processAsciidocSources() {
        if (sourceFileTree.files.size() == 0) {
            logger.lifecycle 'Asciidoc source file tree is empty. Nothing will be processed.'
            return
        }

        if (classpath == null) {
            classpath = project.configurations.getByName(AsciidoctorCompatibilityPlugin.ASCIIDOCTOR)
        }

        setupClassLoader()

        withAsciidoctor {
            asciidoctor = it

            if (!asciidoctorExtensions?.empty) {
                asciidoctor.registerExtensions(asciidoctorExtensions)

            }

            if (resourceCopyProxy == null) {
                resourceCopyProxy = new ResourceCopyProxyImpl(project)
            }

            if (requires) {
                for (require in requires) {
                    asciidoctor.requireLibrary(require)
                }
            }

            for (activeBackend in activeBackends()) {
                if (!AsciidoctorBackend.isBuiltIn(activeBackend)) {
                    logger.lifecycle("Passing through unknown backend: $activeBackend")
                }
                processDocumentsAndResources(activeBackend)
            }
        }
    }

    @groovy.transform.PackageScope
    File outputDirFor(final File source, final String basePath, final File outputDir, final String backend) {
        String filePath = source.directory ? source.absolutePath : source.parentFile.absolutePath
        String relativeFilePath = normalizePath(filePath) - normalizePath(basePath)
        File baseOutputDir = outputBackendDir(outputDir, backend)
        File destinationParentDir = new File(baseOutputDir, relativeFilePath)
        if (!destinationParentDir.exists()) {
            destinationParentDir.mkdirs()
        }
        destinationParentDir
    }

    // Helper method to be able to produce migration messages
    @Internal
    Set<String> getMigrationMessages() {
        this.migrationMessages
    }

    private File outputBackendDir(final File outputDir, final String backend) {
        separateOutputDirs ? new File(outputDir, FileUtils.toSafeFileName(backend)) : outputDir
    }

    private static String normalizePath(String path) {
        if (IS_WINDOWS) {
            path = path.replace(DOUBLE_BACKLASH, BACKLASH)
            path = path.replace(BACKLASH, DOUBLE_BACKLASH)
        }
        path
    }

    protected Set<String> activeBackends() {
        this.backends ?: [DEFAULT_BACKEND]
    }

    @SuppressWarnings('CatchException')
    @SuppressWarnings('DuplicateStringLiteral')
    protected void processDocumentsAndResources(final String backend) {
        try {
            resourceCopyProxy.copy(outputBackendDir(outputDir, backend), resourceCopySpec)

            sourceFileTree.files.each { File file ->
                if (file.name.startsWith('_')) {
                    throw new InvalidUserDataException('Source documents may not start with an underscore')
                }
                File destinationParentDir = owner.outputDirFor(file, sourceDir.absolutePath, outputDir, backend)
                processSingleFile(backend, destinationParentDir, file)
            }

        } catch (Exception e) {
            throw new GradleException('Error running Asciidoctor', e)
        }
    }

    protected void processSingleFile(String backend, File destinationParentDir, File file) {
        if (logDocuments) {
            logger.lifecycle("Converting $file")
        }
        asciidoctor.renderFile(file, mergedOptions(file,
            [
                project   : project,
                options   : options,
                attributes: attrs,
                baseDir   : !baseDir && !baseDirSetToNull ? file.parentFile : baseDir,
                projectDir: project.projectDir,
                rootDir   : project.rootDir,
                outputDir : destinationParentDir,
                backend   : backend]))
    }

    @SuppressWarnings('AbcMetric')
    private static Map<String, Object> mergedOptions(File file, Map params) {
        Map<String, Object> mergedOptions = [:]
        mergedOptions.putAll(params.options)
        mergedOptions.backend = params.backend
        mergedOptions.in_place = false
        mergedOptions.safe = resolveSafeModeLevel(mergedOptions.safe, 0i)
        mergedOptions.to_dir = params.outputDir
        if (params.baseDir) {
            mergedOptions.base_dir = params.baseDir
        }

        if (mergedOptions.to_file) {
            File toFile = new File(mergedOptions.to_file)
            mergedOptions.to_file = new File(mergedOptions.remove('to_dir'), toFile.name)
        }

        Map attributes = [:]
        processMapAttributes(attributes, params.attributes)

        // Note: Directories passed as relative to work around issue #83
        // Asciidoctor cannot handle absolute paths in Windows properly
        attributes.projectdir = AsciidoctorUtils.getRelativePath(params.projectDir, file.parentFile)
        attributes.rootdir = AsciidoctorUtils.getRelativePath(params.rootDir, file.parentFile)

        // resolve these properties here as we want to catch both Map and String definitions parsed above
        attributes.'project-name' = attributes.'project-name' ?: params.project.name
        attributes.'project-group' = attributes.'project-group' ?: (params.project.group ?: '')
        attributes.'project-version' = attributes.'project-version' ?: (params.project.version ?: '')
        mergedOptions.attributes = attributes

        // Issue #14 force GString -> String as jruby will fail
        // to find an exact match when invoking Asciidoctor
        for (entry in mergedOptions) {
            if (entry.value instanceof CharSequence) {
                mergedOptions[entry.key] = entry.value.toString()
            } else if (entry.value instanceof List) {
                mergedOptions[entry.key] = stringifyList(entry.value)
            } else if (entry.value instanceof Map) {
                mergedOptions[entry.key] = stringifyMap(entry.value)
            } else if (entry.value instanceof File) {
                mergedOptions[entry.key] = entry.value.absolutePath
            }
        }

        mergedOptions
    }

    private static List stringifyList(List input) {
        input.collect { element ->
            if (element instanceof CharSequence) {
                element.toString()
            } else if (element instanceof List) {
                stringifyList(element)
            } else if (element instanceof Map) {
                stringifyMap(element)
            } else if (element instanceof File) {
                element.absolutePath
            } else {
                element
            }
        }
    }

    private static Map stringifyMap(Map input) {
        Map output = [:]
        input.each { key, value ->
            if (value instanceof CharSequence) {
                output[key] = value.toString()
            } else if (value instanceof List) {
                output[key] = stringifyList(value)
            } else if (value instanceof Map) {
                output[key] = stringifyMap(value)
            } else if (value instanceof File) {
                output[key] = value.absolutePath
            } else {
                output[key] = value
            }
        }
        output
    }

    protected static void processMapAttributes(Map attributes, Map rawAttributes) {
        // copy all attributes in order to prevent changes down
        // the Asciidoctor chain that could cause serialization
        // problems with Gradle -> all inputs/outputs get serialized
        // for caching purposes; Ruby objects are non-serializable
        // Issue #14 force GString -> String as jruby will fail
        // to find an exact match when invoking Asciidoctor
        for (entry in rawAttributes) {
            if (entry.value == null || entry.value instanceof Boolean) {
                attributes[entry.key] = entry.value
            } else {
                attributes[entry.key] = entry.value.toString()
            }
        }
    }

    protected static void processCollectionAttributes(Map attributes, rawAttributes) {
        for (attr in rawAttributes) {
            if (attr instanceof CharSequence) {
                def (k, v) = attr.toString().split('=', 2) as List
                attributes.put(k, v != null ? v : '')
            } else {
                // QUESTION should we just coerce it to a String?
                throw new InvalidUserDataException("Unsupported type for attribute ${attr}: ${attr.getClass()}")
            }
        }
    }

    private void migrationMessage(final String currentMethod, final String upgradeInstructions) {
        this.migrationMessages.add("You have used '${currentMethod}'. When upgrading you will need to: ${upgradeInstructions}")
    }

    @SuppressWarnings('DuplicateStringLiteral')
    @SuppressWarnings('DuplicateNumberLiteral')
    private static Map coerceLegacyAttributeFormats(Object attributes) {
        Map transformedMap = [:]
        switch (attributes) {
            case Map:
                transformedMap = attributes
                break
            case CharSequence:
                attributes.replaceAll('([^\\\\]) ', '$1\0').replaceAll('\\\\ ', ' ').split('\0').collect {
                    def split = it.split('=')
                    if (split.size() < 2) {
                        throw new InvalidUserDataException("Unsupported format for attributes: ${attributes}")
                    }
                    transformedMap[split[0]] = split.drop(1).join('=')
                }
                break
            case Collection:
                processCollectionAttributes(transformedMap, attributes)
                break
            default:
                if (attributes.class.isArray()) {
                    processCollectionAttributes(transformedMap, attributes)
                } else {
                    throw new InvalidUserDataException("Unsupported type for attributes: ${attributes.class}")
                }
        }

        transformedMap
    }

    private static int resolveSafeModeLevel(Object safe, int defaultLevel) {
        if (safe == null) {
            defaultLevel
        } else if (safe.class.name == SAFE_MODE_CLASSNAME) {
            safe.level
        } else if (safe instanceof CharSequence) {
            try {
                Enum.valueOf(loadClass(SAFE_MODE_CLASSNAME), safe.toString().toUpperCase()).level
            } catch (IllegalArgumentException e) {
                defaultLevel
            }
        } else {
            safe.toString().toInteger()
        }
    }

    static Class loadClass(String className) {
        cl.loadClass(className)
    }

    @SuppressWarnings('AssignmentToStaticFieldFromInstanceMethod')
    private void setupClassLoader() {
        if (classpath?.files) {
            def urls = classpath.files.collect { it.toURI().toURL() }
            cl = new URLClassLoader(urls as URL[], Thread.currentThread().contextClassLoader)
            Thread.currentThread().contextClassLoader = cl
        } else {
            cl = Thread.currentThread().contextClassLoader
        }
    }

    @SuppressWarnings('CatchException')
    private withAsciidoctor(Closure<?> cl) {
        EXEC_LOCK.lock()
        try {
            def key = new AsciidoctorProxyCacheKey(
                classpath?.files?.toList(),
                asGemPath()
            )
            def asciidoctor = ASCIIDOCTORS.computeIfAbsent(key) { k ->
                def clazz = loadClass(ASCIIDOCTOR_FACTORY_CLASSNAME)
                Class asciidoctorExtensionsDslRegistry = loadClass('org.asciidoctor.groovydsl.AsciidoctorExtensions')

                if (k.gemPath) {
                    new AsciidoctorProxyImpl(delegate: clazz.create(k.gemPath), extensionRegistry: asciidoctorExtensionsDslRegistry.newInstance())
                } else {
                    try {
                        new AsciidoctorProxyImpl(delegate: clazz.create(null as String), extensionRegistry: asciidoctorExtensionsDslRegistry.newInstance())
                    } catch (Exception e) {
                        // Asciidoctor < 1.5.1 can't handle a null gemPath, so fallback to default create() method
                        new AsciidoctorProxyImpl(delegate: clazz.create(), extensionRegistry: asciidoctorExtensionsDslRegistry.newInstance())
                    }
                }
            }
            cl.call(asciidoctor)
        } finally {
            if (asciidoctor) {
                asciidoctor.unregisterAllExtensions()
            }
            EXEC_LOCK.unlock()
        }
    }

}
