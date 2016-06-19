package org.robolectric;

import android.os.Build;

import org.robolectric.annotation.Config;
import org.robolectric.internal.bytecode.InstrumentationConfiguration;
import org.robolectric.internal.dependency.DependencyResolver;
import org.robolectric.internal.dependency.LocalDependencyResolver;
import org.robolectric.manifest.AndroidManifest;
import org.robolectric.res.Fs;
import org.robolectric.res.FsFile;
import org.robolectric.util.Logger;

import java.io.File;

/**
 * Logic to build an App Manifest while using the Bazel build tool.
 *
 * @see <a href="http://www.bazel.io/">Bazel Homepage</a>
 */
/* package */ class BazelManifestFactory extends ManifestFactory {
  protected BazelManifestFactory(Config config) {
    super(config);
  }

  @Override
  public void setProperties() {
    // Output Android logs to be visible in test outputs.
    // See org.robolectric.shadows.ShadowLog
    System.setProperty("robolectric.logging", "stdout");
  }

  @Override
  public AndroidManifest createAppManifest() {
    FsFile manifestFile = getBaseDir().join(config.manifest().equals(Config.DEFAULT_MANIFEST)
        ? DEFAULT_MANIFEST_NAME : config.manifest().replaceAll("^(\\./)+", ""));
    FsFile baseDir = manifestFile.getParent();
    FsFile resDir = baseDir.join(config.resourceDir());
    FsFile assetsDir = baseDir.join(config.assetDir());

    AndroidManifest manifest = createAppManifest(
        manifestFile, resDir, assetsDir, config.packageName(), new String[0] /*TestArgs.get()*/);
    if (manifest == null) {
      return null;
    }
    ResourceIdReconciler reconciler = new ResourceIdReconciler(manifest);
    reconciler.reconcile();
    return manifest;
  }

  /**
   * Manages the creation of the AndroidManifest based on the test arguments.
   *
   * <p>If the test arguments contain information about the android_libraries
   * (see {@link CompositeLibraryAndroidManifestLocator}) create a CompositeAndroidManifest to rely
   * on the external data, rather than the built in project.properties method.
   */
  private AndroidManifest createAppManifest(FsFile manifestFile, FsFile resDir,
      FsFile assetsDir, String packageName, String[] testArgs) {
    // TODO(vnayar): Determine if testArgs is needed, if not, inline this code into
    //     createAppManifest above.
    FsFile robolectricDir = getBaseDir();
    final CompositeLibraryAndroidManifestLocator libraryLocator =
        CompositeLibraryAndroidManifestLocator.createFromArgs(
            testArgs,
            robolectricDir,
            robolectricDir.join(getWorkspaceName()));
    CompositeAndroidManifest manifest =
          libraryLocator.createManifest(manifestFile);
    // The manifest can be null if there are no manifests
    if (manifest == null) {
      return createBasicAppManifest(manifestFile, resDir, assetsDir, packageName);
    }
    packageName = System.getProperty("android.package");
    manifest.setPackageName(packageName);
    return manifest;
  }

  private static AndroidManifest createBasicAppManifest(FsFile manifestFile, FsFile resDir, FsFile assetDir, String packageName) {
    if (!manifestFile.exists()) {
      System.out.print("WARNING: No manifest file found at " + manifestFile.getPath() + ".");
      System.out.println("Falling back to the Android OS resources only.");
      System.out.println("To remove this warning, annotate your test class with @Config(manifest=Config.NONE).");
      return null;
    }

    Logger.debug("Robolectric assets directory: " + assetDir.getPath());
    Logger.debug("   Robolectric res directory: " + resDir.getPath());
    Logger.debug("   Robolectric manifest path: " + manifestFile.getPath());
    Logger.debug("    Robolectric package name: " + packageName);
    return new AndroidManifest(manifestFile, resDir, assetDir, packageName);
  }

  private static String readPropertyOrEnvironmentValue(String name) {
    String propVal = (String) System.getProperty(name);
    if (propVal != null && !propVal.isEmpty()) {
      return propVal;
    }

    String envVal = (String) System.getenv().get(name);
    if (envVal != null && !envVal.isEmpty()) {
      return envVal;
    }

    return null;
  }

  private static String getWorkspaceName() {
    return readPropertyOrEnvironmentValue("TEST_WORKSPACE");
  }

  /**
   * In Bazel, the test runner needs a manifest of runfiles in $TEST_SRCDIR.
   * @see {@link http://bazel.io/docs/test-encyclopedia.html}
   */
  private FsFile getBaseDir() {
    String testSrcDir = readPropertyOrEnvironmentValue("TEST_SRCDIR");
    if (testSrcDir != null) {
      return Fs.fileFromPath(readPropertyOrEnvironmentValue("TEST_SRCDIR"));
    } else {
      throw new RuntimeException("Unable to find test runfiles path in $TEST_SRCDIR.");
    }
  }


  @Override
  public DependencyResolver getJarResolver() {
    if (dependencyResolver == null) {
      File dependencyDir;
      if (System.getProperty("robolectric.dependency.dir") != null) {
        dependencyDir = new File(System.getProperty("robolectric.dependency.dir"));
      } else {
        // TODO(vnayar): Determine if "/google3/third_party/java/robolectric/v3_1_SNAPSHOT/android_jars"
        //   equivalent is needed.
        throw new IllegalStateException("robolectric.dependency.dir is not specified!");
      }
      dependencyResolver = new LocalDependencyResolver(dependencyDir);
    }
    return dependencyResolver;
  }

  @Override
  public int pickSdkVersion(AndroidManifest appManifest) {
    // Pulling from the manifest is dangerous
    // google apps frequently target versions of android that robolectric does not yet support.
    return config.sdk().length == 0
        ? Build.VERSION_CODES.KITKAT
        : super.pickSdkVersion(appManifest);
  }

  @Override
  public InstrumentationConfiguration createClassLoaderConfig() {
    return InstrumentationConfiguration.newBuilder()
        .withConfig(config)
        .doNotAcquirePackage("org.jacoco")
        .build();
  }
}
