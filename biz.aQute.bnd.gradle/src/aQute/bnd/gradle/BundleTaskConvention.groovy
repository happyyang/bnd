/**
 * BundleTaskConvention for Gradle.
 *
 * <p>
 * Adds properties to bundle builder tasks.
 *
 * <p>
 * Properties:
 * <ul>
 * <li>bndfile - This is the name of the bnd file to use to make the bundle.
 * This defaults to 'bnd.bnd' in the projectDir. The bndfile does not need
 * to exist. It supersedes any information in the jar task's manifest.</li>
 * <li>sourceSet - This is the SourceSet to use for the
 * bnd builder. It defaults to 'project.sourceSets.main'.</li>
 * <li>classpath - This is the FileCollection to use for the buildpath
 * for the bnd builder. It defaults to 'sourceSet.compileClasspath'.</li>
 * </ul>
 */

package aQute.bnd.gradle

import static aQute.bnd.gradle.BndUtils.logReport

import java.util.Properties
import java.util.jar.Manifest
import java.util.zip.ZipException
import java.util.zip.ZipFile

import aQute.bnd.osgi.Builder
import aQute.bnd.osgi.Constants
import aQute.bnd.osgi.Jar
import aQute.bnd.osgi.Processor
import aQute.bnd.version.MavenVersion
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.logging.Logger
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.SourceSet

class BundleTaskConvention {
  private final Task task
  private final Project project
  private File bndfile
  private Configuration configuration
  private ConfigurableFileCollection classpath
  private boolean classpathModified
  private SourceSet sourceSet

  /**
   * Create a BundleTaskConvention for the specified Jar task.
   *
   * <p>
   * This also sets the default values for the added properties
   * and adds the bnd file to the task inputs.
   */
  BundleTaskConvention(org.gradle.api.tasks.bundling.Jar task) {
    this.task = task
    this.project = task.project
    setBndfile('bnd.bnd')
    setSourceSet(project.sourceSets.main)
    configuration = project.configurations.findByName('compileClasspath') ?: project.configurations.compile
    classpathModified = false
  }

  /**
   * Get the bndfile property.
   */
  @InputFile
  public File getBndfile() {
    return bndfile
  }
  /**
   * Set the bndfile property.
   *
   * <p>
   * The argument will be handled using
   * Project.file().
   */
  public void setBndfile(Object file) {
    bndfile = project.file(file)
  }

  /**
   * Get the configuration property.
   */
  @Deprecated
  public Configuration getConfiguration() {
    task.logger.warn 'The configuration property is deprecated and replaced by the classpath property.'
    return configuration
  }
  /**
   * Set the configuration property.
   */
  @Deprecated
  public void setConfiguration(Configuration configuration) {
    task.logger.warn 'The configuration property is deprecated and replaced by the classpath property.'
    this.configuration = configuration
    if (!classpathModified) {
      setClasspath(configuration)
      classpathModified = false
    }
  }

  /**
   * Add files to the classpath.
   *
   * <p>
   * The arguments will be handled using
   * Project.files().
   */
  public ConfigurableFileCollection classpath(Object... paths) {
    classpathModified = true
    classpath.builtBy paths.findAll { path ->
      path instanceof Task || path instanceof Buildable
    }
    return classpath.from(paths)
  }

  /**
   * Get the classpath property.
   */
  @InputFiles
  public ConfigurableFileCollection getClasspath() {
    return classpath
  }
  /**
   * Set the classpath property.
   */
  public void setClasspath(Object path) {
    classpathModified = true
    classpath = project.files(path)
    if (path instanceof Task || path instanceof Buildable) {
      classpath.builtBy path
    }
  }

  /**
   * Get the sourceSet property.
   */
  @InputFiles
  public SourceSet getSourceSet() {
    return sourceSet
  }
  /**
   * Set the sourceSet property.
   */
  public void setSourceSet(SourceSet sourceSet) {
    this.sourceSet = sourceSet
    if (!classpathModified) {
      setClasspath(sourceSet.compileClasspath)
      classpathModified = false
    }
  }

  void buildBundle() {
    task.configure {
      // create Builder
      Properties gradleProperties = new PropertiesWrapper()
      gradleProperties.put('task', task)
      gradleProperties.put('project', project)
      new Builder(new Processor(gradleProperties, false)).withCloseable { builder ->
        // load bnd properties
        File temporaryBndFile = File.createTempFile('bnd', '.bnd', temporaryDir)
        temporaryBndFile.withWriter('UTF-8') { writer ->
          // write any task manifest entries into the tmp bnd file
          manifest.effectiveManifest.attributes.inject(new Properties()) { properties, key, value ->
            if (key != 'Manifest-Version') {
              properties.setProperty(key, value.toString())
            }
            return properties
          }.store(writer, null)

          // if the bnd file exists, add its contents to the tmp bnd file
          if (bndfile.isFile()) {
            builder.loadProperties(bndfile).store(writer, null)
          }
        }
        builder.setProperties(temporaryBndFile, project.projectDir) // this will cause project.dir property to be set
        builder.setProperty('project.output', project.buildDir.canonicalPath)

        // If no bundle to be built, we have nothing to do
        if (Builder.isTrue(builder.getProperty(Constants.NOBUNDLES))) {
          return
        }

        // Reject sub-bundle projects
        if (builder.getSubBuilders() != [builder]) {
          throw new GradleException('Sub-bundles are not supported by this task')
        }

        // Include entire contents of Jar task generated jar (except the manifest)
        project.copy {
          from archivePath
          into temporaryDir
        }
        File archiveCopyFile = new File(temporaryDir, archiveName)
        Jar bundleJar = new Jar(archiveName, archiveCopyFile)
        bundleJar.updateModified(archiveCopyFile.lastModified(), 'time of Jar task generated jar')
        bundleJar.setManifest(new Manifest())
        builder.setJar(bundleJar)

        // set builder classpath
        def buildpath = project.files(classpath.files.findAll { file ->
          if (!file.exists()) {
            return false
          }
          if (file.directory) {
            return true
          }
          try {
            new ZipFile(file).withCloseable { zip ->
              zip.entries() // make sure it is a valid zip file and not a pom
            }
          } catch (ZipException e) {
            return false
          }
          return true
        })
        builder.setProperty('project.buildpath', buildpath.asPath)
        builder.setClasspath(buildpath as File[])
        logger.debug 'builder classpath: {}', builder.getClasspath()*.getSource()

        // set builder sourcepath
        def sourcepath = project.files(sourceSet.allSource.srcDirs.findAll{it.exists()})
        builder.setProperty('project.sourcepath', sourcepath.asPath)
        builder.setSourcepath(sourcepath as File[])
        logger.debug 'builder sourcepath: {}', builder.getSourcePath()


        // set bundle symbolic name from tasks's baseName property if necessary
        String bundleSymbolicName = builder.getProperty(Constants.BUNDLE_SYMBOLICNAME)
        if (isEmpty(bundleSymbolicName)) {
          builder.setProperty(Constants.BUNDLE_SYMBOLICNAME, baseName)
        }

        // set bundle version from task's version if necessary
        String bundleVersion = builder.getProperty(Constants.BUNDLE_VERSION)
        if (isEmpty(bundleVersion)) {
          builder.setProperty(Constants.BUNDLE_VERSION, MavenVersion.parseString(version?.toString()).getOSGiVersion().toString())
        }

        logger.debug 'builder properties: {}', builder.getProperties()

        // Build bundle
        builder.build()
        if (!builder.isOk()) {
          // if we already have an error; fail now
          logReport(builder, logger)
          failBuild("Bundle ${archiveName} has errors")
        }

        // Write out the bundle
        bundleJar.write(archivePath)

        logReport(builder, logger)
        if (!builder.isOk()) {
          failBuild("Bundle ${archiveName} has errors")
        }
      }
    }
  }

  private void failBuild(String msg) {
    task.archivePath.delete()
    throw new GradleException(msg)
  }

  private boolean isEmpty(String header) {
    return (header == null) || header.trim().isEmpty() || Constants.EMPTY_HEADER.equals(header)
  }
}
