package org.robolectric;

import org.robolectric.annotation.Config;
import org.robolectric.internal.SdkConfig;
import org.robolectric.internal.bytecode.InstrumentationConfiguration;
import org.robolectric.internal.dependency.CachedDependencyResolver;
import org.robolectric.internal.dependency.DependencyResolver;
import org.robolectric.internal.dependency.LocalDependencyResolver;
import org.robolectric.internal.dependency.MavenDependencyResolver;
import org.robolectric.manifest.AndroidManifest;
import org.robolectric.util.Logger;

import java.io.File;

/**
 * A factory that detects what build system is in use and provides a ManifestFactory that can
 * create an AndroidManifest for that environment.
 *
 * <p>The following build systems are currently supported:
 * <ul>
 *   <li>Maven</li>
 *   <li>Gradle</li>
 *   <li>Bazel</li>
 * </ul>
 */
public abstract class ManifestFactory {
  protected static final String DEFAULT_MANIFEST_NAME = "AndroidManifest.xml";

  protected final Config config;
  protected DependencyResolver dependencyResolver;  // For cacheing.

  protected ManifestFactory(Config config) {
    this.config = config;
  }

  /**
   * Detects what build system is in use and returns the appropriate ManifestFactory implementation.
   * @param config Specification of the SDK version, manifest file, package name, etc.
   */
  public static ManifestFactory newManifestFactory(Config config) {
    if (false /* Figure out the right conditions to use Bazel. */) {
      return new BazelManifestFactory(config);
    } else if (config.constants() != null && config.constants() != Void.class) {
      return new GradleManifestFactory(config);
    } else {
      return new MavenManifestFactory(config);
    }
  }

  public void setProperties() {}

  /**
   * @return A new AndroidManifest including the location of libraries, assets, resources, etc.
   */
  public abstract AndroidManifest createAppManifest();

  /**
   * This default implementation is only valid for Gradle and Maven, but it is convenient
   * to have it here because many tests use it.
   */
  public DependencyResolver getJarResolver() {
    if (dependencyResolver == null) {
      if (Boolean.getBoolean("robolectric.offline")) {
        String dependencyDir = System.getProperty("robolectric.dependency.dir", ".");
        dependencyResolver = new LocalDependencyResolver(new File(dependencyDir));
      } else {
        File cacheDir = new File(new File(System.getProperty("java.io.tmpdir")), "robolectric");

        if (cacheDir.exists() || cacheDir.mkdir()) {
          Logger.info("Dependency cache location: %s", cacheDir.getAbsolutePath());
          dependencyResolver = new CachedDependencyResolver(new MavenDependencyResolver(), cacheDir, 60 * 60 * 24 * 1000);
        } else {
          dependencyResolver = new MavenDependencyResolver();
        }
      }
    }

    return dependencyResolver;
  }

  public int pickSdkVersion(AndroidManifest appManifest) {
    if (config != null && config.sdk().length > 1) {
      throw new IllegalArgumentException("RobolectricTestRunner does not support multiple values for @Config.sdk");
    } else if (config != null && config.sdk().length == 1) {
      return config.sdk()[0];
    } else if (appManifest != null) {
      return appManifest.getTargetSdkVersion();
    } else {
      return SdkConfig.FALLBACK_SDK_VERSION;
    }
  }

  public InstrumentationConfiguration createClassLoaderConfig() {
    return InstrumentationConfiguration.newBuilder().withConfig(config).build();
  }
}
