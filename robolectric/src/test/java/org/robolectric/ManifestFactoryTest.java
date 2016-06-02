package org.robolectric;

import static org.junit.Assert.assertEquals;
import static org.robolectric.util.TestUtil.resourceFile;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.robolectric.annotation.Config;
import org.robolectric.manifest.AndroidManifest;

import java.util.List;

@RunWith(JUnit4.class)
public class ManifestFactoryTest {
  @Test
  public void shouldLoadLibraryManifests() throws Exception {
    Config config = new Config.Implementation(
        Config.DEFAULT_SDK,
        "TestAndroidManifest.xml",
        Config.DEFAULT_QUALIFIERS,
        Config.DEFAULT_PACKAGE_NAME,
        Config.DEFAULT_ABI_SPLIT,
        Config.DEFAULT_RES_FOLDER,
        Config.DEFAULT_ASSET_FOLDER,
        Config.DEFAULT_BUILD_FOLDER,
        Config.DEFAULT_SHADOWS,
        Config.DEFAULT_INSTRUMENTED_PACKAGES,
        Config.DEFAULT_APPLICATION,
        new String[]{ resourceFile("lib1").toString() },
        Config.DEFAULT_CONSTANTS);
    ManifestFactory.MavenManifestFactory mavenManifestFactory =
        new ManifestFactory.MavenManifestFactory(config);
    AndroidManifest manifest = mavenManifestFactory.create();

    List<AndroidManifest> libraryManifests = manifest.getLibraryManifests();
    assertEquals(1, libraryManifests.size());
    assertEquals("org.robolectric.lib1", libraryManifests.get(0).getPackageName());
  }
}
