package org.robolectric;

import org.robolectric.manifest.AndroidManifest;
import org.robolectric.res.FsFile;

import java.util.List;

/**
 * AndroidManfiest that relies on external resolution of library manifests.
 */
class CompositeAndroidManifest extends AndroidManifest {

  private final List<AndroidManifest> dependentLibraries;
  private final FsFile androidManifestFile;

  public CompositeAndroidManifest(FsFile androidManifestFile, FsFile resDirectory,
      FsFile assetsDirectory, List<AndroidManifest> dependentLibraries) {
    super(androidManifestFile, resDirectory, assetsDirectory);
    this.dependentLibraries = dependentLibraries;
    this.androidManifestFile = androidManifestFile;
  }

  @Override
  public List<AndroidManifest> getLibraryManifests() {
    return dependentLibraries;
  }

  /** Creates a new CompositeAndroidManifest with the provided dependent libraries. */
  public CompositeAndroidManifest createWithManifests(List<AndroidManifest> dependentLibraries) {
    return new CompositeAndroidManifest(androidManifestFile,
        getResDirectory(), getAssetsDirectory(), dependentLibraries);
  }

  @Override
  public String toString() {
    return "CompositeAndroidManifest [dependentLibraries=" + dependentLibraries
        + ", androidManifestFile=" + androidManifestFile + ", getResDirectory()="
        + getResDirectory() + ", getAssetsDirectory()=" + getAssetsDirectory() + "]";
  }
}
