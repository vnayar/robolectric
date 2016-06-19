package org.robolectric;

import org.junit.runners.model.InitializationError;
import org.robolectric.annotation.Config;
import org.robolectric.manifest.AndroidManifest;

/**
 * Test runner customized for running unit tests either through the Gradle CLI or
 * Android Studio. The runner uses the build type and build flavor to compute the
 * resource, asset, and AndroidManifest paths.
 *
 * This test runner requires that you set the 'constants' field on the @Config
 * annotation (or the org.robolectric.Config.properties file) for your tests.
 */
@Deprecated
public class RobolectricGradleTestRunner extends RobolectricTestRunner {
  public RobolectricGradleTestRunner(final Class<?> testClass) throws InitializationError {
    super(testClass);
  }
}
