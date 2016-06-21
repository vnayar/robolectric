package org.robolectric.util;

import org.junit.runners.model.InitializationError;
import org.robolectric.ManifestFactory;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.manifest.AndroidManifest;
import org.robolectric.res.Fs;
import org.robolectric.res.FsFile;

import java.io.File;

public class TestRunnerWithManifest extends RobolectricTestRunner {
  public TestRunnerWithManifest(Class<?> testClass) throws InitializationError {
    super(testClass);
  }

  public static FsFile resourceFile(String... pathParts) {
    return Fs.newFile(resourcesBaseDirFile()).join(pathParts);
  }

  private static File resourcesBaseDirFile() {
    File testDir = Util.file("src", "test", "resources");
    return hasTestManifest(testDir) ? testDir : Util.file("robolectric-fakehttp", "src", "test", "resources");
  }

  private static boolean hasTestManifest(File testDir) {
    return new File(testDir, "AndroidManifest.xml").isFile();
  }

  @Override
  protected ManifestFactory getManifestFactory(Config config) {
    return new ManifestFactory(config) {
      @Override
      public AndroidManifest createAppManifest() {
        return new AndroidManifest(
            resourceFile("AndroidManifest.xml"), resourceFile("res"), resourceFile("assets"));
      }
    };
  }
}
