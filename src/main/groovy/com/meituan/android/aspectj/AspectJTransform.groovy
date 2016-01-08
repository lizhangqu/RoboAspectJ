package com.meituan.android.aspectj

import com.android.annotations.NonNull
import com.android.build.api.transform.*
import com.google.common.base.Joiner
import com.google.common.base.Strings
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Lists
import com.google.common.collect.Sets
import org.apache.commons.io.FileUtils
import org.aspectj.bridge.IMessage
import org.aspectj.bridge.MessageHandler
import org.aspectj.bridge.Version
import org.aspectj.tools.ajc.Main
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ExcludeRule
import org.gradle.api.logging.Logger

/**
 * <p>The main job of this {@link com.android.build.api.transform.Transform} implementation is to
 * do the AspectJ build-time binary weaving.</p>
 *
 * <p>Tranform system in android plugin offers us a good timing to manipulate byte codes. So we
 * interpose weaving in the way of class to dex. It's better to apply it before any other transform
 * processes the binary since we are based on the package info to do the excluding otherwise the
 * info may lose.</p>
 *
 * <p>Created by Xiz on 9/21, 2015.</p>
 */
public class AspectJTransform extends Transform {
    private static final Set<QualifiedContent.ContentType> CONTENT_CLASS = Sets.immutableEnumSet(QualifiedContent.DefaultContentType.CLASSES)
    private static final Set<QualifiedContent.Scope> SCOPE_FULL_PROJECT = Sets.immutableEnumSet(
            QualifiedContent.Scope.PROJECT,
            QualifiedContent.Scope.PROJECT_LOCAL_DEPS,
            QualifiedContent.Scope.SUB_PROJECTS,
            QualifiedContent.Scope.SUB_PROJECTS_LOCAL_DEPS,
            QualifiedContent.Scope.EXTERNAL_LIBRARIES)

    private Project project

    public AspectJTransform(Project project) {
        this.project = project
    }

    @Override
    void transform(Context context, Collection<TransformInput> inputs, Collection<TransformInput> referencedInputs, TransformOutputProvider outputProvider, boolean isIncremental) throws IOException, TransformException, InterruptedException {
        List<File> files = Lists.newArrayList()
        List<File> classpathFiles = Lists.newArrayList()
        Logger logger = project.getLogger()
        File output = null;

        // clean
        outputProvider.deleteAll()

        // in case it's executed when disabled
        if (!project.aspectj.enabled) {
            logger.quiet 'AspectJ Weaving is disabled.'
            return
        }

        logger.quiet "AspectJ Compiler, version " + Version.text

        // fetch java runtime classpath
        String javaRtPath = null
        if (project.aspectj.javartNeeded) {
            project.android.applicationVariants.all {
                String javaRt = Joiner.on(File.separator).join(['jre', 'lib', 'rt.jar'])
                for (String classpath : javaCompiler.classpath.asPath.split(File.pathSeparator)) {
                    if (classpath.contains(javaRt)) {
                        javaRtPath = classpath
                    }
                }
            }
            if (Strings.isNullOrEmpty(javaRtPath)) {
                logger.error 'Can not extract java runtime classpath from android plugin.'
            }
        }

        // categorize bytecode files and excluded files for other transforms' usage later
        logger.quiet 'Excluding dependencies from AspectJ Compiler inpath ...'
        logger.quiet 'Note: The excluded will not be eliminated from the compilation.' +
                        ' They\'re just being used as classpath instead.'

        for (TransformInput input : referencedInputs) {
            input.directoryInputs.each {
                classpathFiles.add(it.file)
            }

            input.jarInputs.each {
                classpathFiles.add(it.file)
            }
        }

        boolean nothingExcluded = true
        for (TransformInput input : inputs) {
            for (DirectoryInput folder : input.directoryInputs) {
                if (isFileExcluded(folder.file)) {
                    logger.quiet "Folder [" + folder.file.name + "] has been excluded."
                    nothingExcluded = false
                    classpathFiles.add(folder.file)
                    String outputFileName = folder.name + '-' + folder.file.path.hashCode()
                    output = outputProvider.getContentLocation(outputFileName, outputTypes, scopes, Format.DIRECTORY)
                    FileUtils.copyDirectoryToDirectory(folder.file, output)
                } else {
                    files.add(folder.file)
                }
            }

            for (JarInput jar : input.jarInputs) {
                if (isFileExcluded(jar.file)) {
                    logger.quiet "Jar [" + jar.file.name + "] has been excluded."
                    nothingExcluded = false
                    classpathFiles.add(jar.file)
                    String outputFileName = jar.name.replace(".jar", "") + '-' + jar.file.path.hashCode()
                    output = outputProvider.getContentLocation(outputFileName, outputTypes, scopes, Format.JAR)
                    FileUtils.copyFile(jar.file, output)
                } else {
                    files.add(jar.file)
                }
            }
        }
        if (nothingExcluded) {
            logger.quiet "Nothing excluded."
        }

        //evaluate class paths
        final String inpath = Joiner.on(File.pathSeparator).join(files)
        final String classpath = Joiner.on(File.pathSeparator).join(
                                    !Strings.isNullOrEmpty(javaRtPath) ?
                                    [*classpathFiles.collect { it.absolutePath }, javaRtPath] :
                                    classpathFiles.collect { it.absolutePath })
        final String bootpath = Joiner.on(File.pathSeparator).join(project.android.bootClasspath)
        output = outputProvider.getContentLocation("main", outputTypes, scopes, Format.DIRECTORY);

        // assemble compile options
        logger.quiet "Weaving ..."
        def args = [
                "-source", project.aspectj.compileOptions.sourceCompatibility.name,
                "-target", project.aspectj.compileOptions.targetCompatibility.name,
                "-showWeaveInfo",
                "-encoding", project.aspectj.compileOptions.encoding,
                "-inpath", inpath,
                "-d", output.absolutePath,
                "-bootclasspath", bootpath]

        // append classpath argument if any
        if (!Strings.isNullOrEmpty(classpath)) {
            args << '-classpath'
            args << classpath
        }

        // run compilation
        MessageHandler handler = new MessageHandler(true)
        new Main().run(args as String[], handler)

        // log compile
        for (IMessage message : handler.getMessages(null, true)) {
            if (project.aspectj.verbose) {
                // level up weave info log for debug
                logger.quiet(message.getMessage())
            } else {
                if (IMessage.ERROR.isSameOrLessThan(message.kind)) {
                    logger.error(message.message, message.thrown)
                    throw new GradleException(message.message, message.thrown)
                } else if (IMessage.WARNING.isSameOrLessThan(message.kind)) {
                    logger.warn message.message
                } else if (IMessage.DEBUG.isSameOrLessThan(message.kind)) {
                    logger.info message.message
                } else {
                    logger.debug message.message
                }
            }
        }
    }

    @NonNull
    @Override
    public String getName() {
        "AspectJ"
    }

    @NonNull
    @Override
    public Set<QualifiedContent.ContentType> getInputTypes() {
        CONTENT_CLASS
    }

    @NonNull
    @Override
    public Set<QualifiedContent.Scope> getScopes() {
        project.aspectj.enabled ? SCOPE_FULL_PROJECT : ImmutableSet.of()
    }

    @Override
    Set<QualifiedContent.Scope> getReferencedScopes() {
        return Sets.immutableEnumSet(QualifiedContent.Scope.PROVIDED_ONLY)
    }

    @Override
    public boolean isIncremental() {
        // can't be incremental
        // because java bytecode and aspect bytecode are woven across each other
        false
    }

    protected boolean isFileExcluded(File file) {
        for (ExcludeRule rule : project.aspectj.excludeRules) {
            if (file.absolutePath.contains(Joiner.on(File.separator).join([rule.group, rule.module]))) {
                return true
            }
        }
        return false
    }

}