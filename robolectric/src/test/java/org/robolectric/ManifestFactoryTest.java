package org.robolectric;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.robolectric.util.TestUtil.joinPath;
import static org.robolectric.util.TestUtil.resourceFile;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.robolectric.annotation.Config;
import org.robolectric.manifest.AndroidManifest;
import org.robolectric.res.ResourcePath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

@RunWith(JUnit4.class)
public class ManifestFactoryTest {
  @Test
  public void shouldLoadLibraryManifests() throws Exception {
    Properties properties = new Properties();
    properties.setProperty("libraries", "lib1");
    Config config = new Config.Implementation(
        new Config.Implementation("src/test/resources/TestAndroidManifest.xml"),
        Config.Implementation.fromProperties(properties));
    ManifestFactory.MavenManifestFactory mavenManifestFactory =
        new ManifestFactory.MavenManifestFactory(config);
    AndroidManifest manifest = mavenManifestFactory.create();

    List<AndroidManifest> libraryManifests = manifest.getLibraryManifests();
    assertEquals(1, libraryManifests.size());
    assertEquals("org.robolectric.lib1", libraryManifests.get(0).getPackageName());
  }

  @Test
  public void shouldLoadAllResourcesForExistingLibraries() {
    Properties properties = new Properties();
    properties.setProperty("resourceDir", "res");
    properties.setProperty("assetDir", "assets");
    Config config = new Config.Implementation(
        new Config.Implementation("src/test/resources/TestAndroidManifest.xml"),
        Config.Implementation.fromProperties(properties));
    ManifestFactory.MavenManifestFactory mavenManifestFactory =
        new ManifestFactory.MavenManifestFactory(config);
    AndroidManifest appManifest = mavenManifestFactory.create();

    //AndroidManifest appManifest = new AndroidManifest(resourceFile("TestAndroidManifest.xml"), resourceFile("res"), resourceFile("assets"));

    // This intentionally loads from the non standard resources/project.properties
    List<String> resourcePaths = stringify(appManifest.getIncludedResourcePaths());
    assertEquals(asList(
        joinPath(".", "src", "test", "resources", "res"),
        joinPath(".", "src", "test", "resources", "lib1", "res"),
        joinPath(".", "src", "test", "resources", "lib1", "..", "lib3", "res"),
        joinPath(".", "src", "test", "resources", "lib1", "..", "lib2", "res")),
        resourcePaths);
  }

  private List<String> stringify(Collection<ResourcePath> resourcePaths) {
    List<String> resourcePathBases = new ArrayList<>();
    for (ResourcePath resourcePath : resourcePaths) {
      resourcePathBases.add(resourcePath.resourceBase.toString());
    }
    return resourcePathBases;
  }
}
