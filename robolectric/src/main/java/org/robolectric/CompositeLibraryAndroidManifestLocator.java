package org.robolectric;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.robolectric.manifest.AndroidManifest;
import org.robolectric.res.Fs;
import org.robolectric.res.FsFile;
import org.robolectric.util.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Acts as a location service for {@link AndroidManifest}s.
 */
class CompositeLibraryAndroidManifestLocator {

  @VisibleForTesting static final String FORMAT_ERROR_MESSAGE =
      "%s is not in the format 'manifest:aar'.";

  @VisibleForTesting static final String ANDROID_LIBRARIES = "--android_libraries";
  @VisibleForTesting static final String STRICT_LIBRARIES = "--strict_libraries";

  private static final Pattern VALID_MANIFEST_REGEX = Pattern.compile("[^:]+:[^:]+");
  private static final Pattern AAR_LABEL_FINDER_REGEX = Pattern.compile("(.*)/(.*)\\.aar");

  /**
   * AndroindManifest isn't null safe, so we use a null object.
   */
  static final FsFile NULL_FSFILE = new FsFile() {
    private final FsFile[] emptyList = new FsFile[]{};

    @Override
    public boolean exists() {
      return false;
    }

    @Override
    public boolean isDirectory() {
      return false;
    }

    @Override
    public boolean isFile() {
      return false;
    }

    @Override
    public FsFile[] listFiles() {
      return emptyList;
    }

    @Override
    public FsFile[] listFiles(Filter filter) {
      return emptyList;
    }

    @Override
    public String[] listFileNames() {
      return new String[]{};
    }

    @Override
    public FsFile getParent() {
      return null;
    }

    @Override
    public String getName() {
      return "";
    }

    @Override
    public InputStream getInputStream() throws IOException {
      return null;
    }

    @Override
    public byte[] getBytes() throws IOException {
      return null;
    }

    @Override
    public FsFile join(String... pathParts) {
      return this;
    }

    @Override
    public String getBaseName() {
      return "";
    }

    @Override
    public String getPath() {
      return "";
    }
  };

  static class Builder {
    private FsFile robolectricDir;
    private FsFile baseDir;
    private String[] args;
    private String[] strictArgs;

    private LinkedHashMap<String, AndroidManifest> transitiveManifests;
    private LinkedHashMap<String, AndroidManifest> directManifests;
    private ImmutableMap.Builder<String, String> labelAliasMap;

    private Map<String, AndroidManifest> uniqueManifests;

    public Builder(FsFile robolectricDir, FsFile baseDir) {
      this.robolectricDir = robolectricDir;
      this.baseDir = baseDir;
      this.transitiveManifests = new LinkedHashMap<>();
      this.directManifests = new LinkedHashMap<>();
      this.labelAliasMap = ImmutableMap.builder();
      this.uniqueManifests = new HashMap<>();
    }

    public Builder withArgs(String[] args) {
      this.args = args;
      return this;
    }

    public Builder withStrictArgs(String[] strictArgs) {
      this.strictArgs = strictArgs;
      return this;
    }

    public CompositeLibraryAndroidManifestLocator build() throws IOException {
      final Stopwatch timer = Stopwatch.createStarted();
      try {
        for (String manifestString : strictArgs) {
          parseManifest(manifestString, false);
        }
        for (String manifestString : args) {
          parseManifest(manifestString, true);
        }
      } finally {
        Logger.info("%dms for resource unpacking.\n", timer.elapsed(TimeUnit.MILLISECONDS));
      }
      return new CompositeLibraryAndroidManifestLocator(robolectricDir,
          ImmutableMap.copyOf(transitiveManifests),
          ImmutableMap.copyOf(directManifests),
          labelAliasMap.build());
    }

    private void parseManifest(String manifestString, boolean transitive) throws IOException {
      if (!VALID_MANIFEST_REGEX.matcher(manifestString).matches()) {
        throw new IllegalArgumentException(String.format(FORMAT_ERROR_MESSAGE, manifestString));
      }

      String[] tokens = manifestString.split(":", -1);

      String manifestRelPath = tokens[0];
      FsFile manifest = asFsFile(manifestRelPath);
      String aarRelPath = tokens[1];
      FsFile aar = asFsFile(aarRelPath);

      Path resourceDir;
      Path assetsDir;

      /* Infer the package and label name from the aar path. This ensures the label will always be
       * of the generating android_library and never the underlying android_resources. */
      Matcher m = AAR_LABEL_FINDER_REGEX.matcher(aarRelPath);
      if (m.matches()) {
        String pakkage = m.group(1);
        String target = m.group(2);

        if (!transitive) {
          labelAliasMap.put(
              "//" + pakkage + ":" + target + "/AndroidManifest.xml", manifest.getPath());
        }
        resourceDir = createMergeDir(pakkage + "/" + target, "res");
        assetsDir = createMergeDir(pakkage + "/" + target, "assets");
      } else {
        resourceDir = createMergeDir(manifest.getParent().getPath(), "res");
        assetsDir = createMergeDir(manifest.getParent().getPath(), "assets");
      }

      AndroidManifest androidManifest = uniqueManifests.get(manifest.getPath());
      if (androidManifest == null) {
        extractResourcesFromAar(aar, resourceDir, assetsDir);
        androidManifest = new CompositeAndroidManifest(manifest,
            Fs.fileFromPath(resourceDir.toString()), Fs.fileFromPath(assetsDir.toString()),
            Collections.<AndroidManifest>emptyList());
      }

      Map<String, AndroidManifest> manifests = transitive ? transitiveManifests : directManifests;
      if (manifests.containsKey(manifest.getPath())) {
        Logger.info("Manifest '%s' is referenced by multiple android_library rules.",
            manifestRelPath);
      } else {
        manifests.put(manifest.getPath(), androidManifest);
      }
      uniqueManifests.put(manifest.getPath(), androidManifest);
    }

    private void extractResourcesFromAar(FsFile aarFile, final Path resourceDir,
        final Path assetsDir) throws IOException {

      ZipFile aar = new ZipFile(aarFile.getPath());
      Enumeration<? extends ZipEntry> entries = aar.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (entry.getName().startsWith("res/") || entry.getName().startsWith("assets/")) {
          Path file;
          if (entry.getName().startsWith("res/")) {
            file = resourceDir.resolve(entry.getName().substring("res/".length()));
          } else {
            file = assetsDir.resolve(entry.getName().substring("assets/".length()));
          }
          if (entry.isDirectory()) {
            Files.createDirectories(file);
          } else {
            Files.createDirectories(file.getParent());
            Files.copy(aar.getInputStream(entry), file);
          }
        }
      }
      aar.close();
    }

    /** Creates a readable temporary directory without exceeding the commandline limit. */
    private Path createMergeDir(String path, String prefix) throws IOException {
      // Find the java directory, if not, cut to 0.
      final int javaIndex = path.contains("java") ? path.lastIndexOf("java") : 0;
      // Then, replace / with _.
      final Path mergeDir =
          Files.createTempDirectory(path.substring(javaIndex).replace('/', '_') + "_" + prefix);
      // clean up when the jvm quits.
      mergeDir.toFile().deleteOnExit();
      return mergeDir;
    }

    private FsFile asFsFile(String path) {
      FsFile fileFromPath = baseDir.join(path);
      if (!fileFromPath.exists()) {
        throw new IllegalArgumentException(String.format("%s does not exist.", fileFromPath));
      }
      return fileFromPath;
    }
  }

  /**
   * Creates a new locator from an array of strings
   *
   * @param rawArgs The raw arguments passed to the test runner, possibly containing a
   *        "--android_libraries" flag.
   * @param robolectricDir robolectric's working directory that it resolves paths against.
   * @param baseDir The base directory (usually runfiles) to resolve the paths against.
   */
  public static CompositeLibraryAndroidManifestLocator createFromArgs(
      String[] rawArgs, FsFile robolectricDir, FsFile baseDir) {
    try {
      return new Builder(robolectricDir, baseDir)
          .withArgs(parseFlag(ANDROID_LIBRARIES, rawArgs))
          .withStrictArgs(parseFlag(STRICT_LIBRARIES, rawArgs))
          .build();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static String[] parseFlag(String flag, String[] args) {
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals(flag) && i + 1 < args.length) {
        return args[i + 1].split(",");
      } else if (args[i].startsWith(flag + "=")) {
        String rawArgsValue = args[i].substring((flag + "=").length());
        if (rawArgsValue.length() != 0) {
          return rawArgsValue.split(",");
        }
      }
    }
    return new String[] {};
  }

  /** Maps actual manifest path to AndroidManifests for transitive manifests. */
  private final ImmutableMap<String, AndroidManifest> transitiveManifests;
  /** Maps actual manifest path to AndroidManifests for direct manifests. */
  private final ImmutableMap<String, AndroidManifest> directManifests;
  /** For finding manifests with //package-name:target/AndroidManifest.xml. */
  private final ImmutableMap<String, String> labelAliasMap;

  private final FsFile robolectricDir;

  public CompositeLibraryAndroidManifestLocator(
      FsFile robolectricDir,
      ImmutableMap<String, AndroidManifest> transitiveManifests,
      ImmutableMap<String, AndroidManifest> directManifests,
      ImmutableMap<String, String> labelAliasMap) {
    this.robolectricDir = robolectricDir;
    this.transitiveManifests = transitiveManifests;
    this.directManifests = directManifests;
    this.labelAliasMap = labelAliasMap;
  }

  public boolean hasValues() {
    return !directManifests.isEmpty();
  }

  /**
   * Creates a GoogleManifest with resolved dependencies.
   */
  public CompositeAndroidManifest createManifest(FsFile possibleManifestFile) {
    if (directManifests.isEmpty()) {
      // There are no values so we let Robolectric take over.
      return null;
    }
    // Find any possible aliases
    String manifestFile = getManifestFile(possibleManifestFile);
    AndroidManifest excluded = directManifests.get(manifestFile);

    Set<String> addedManifests = new HashSet<>();
    ImmutableList.Builder<AndroidManifest> builder = ImmutableList.builder();
    addedManifests.add(excluded.getAndroidManifestFile().getPath());
    for (AndroidManifest manifest : directManifests.values()) {
      if (!excluded.equals(manifest)) {
        builder.add(manifest);
        addedManifests.add(manifest.getAndroidManifestFile().getPath());
      }
    }
    // Add the transitive manifests.
    for (AndroidManifest manifest : transitiveManifests.values()) {
      if (!addedManifests.contains(((CompositeAndroidManifest) manifest)
          .getAndroidManifestFile().getPath())) {
        builder.add(manifest);
      }
    }
    // create the primary manifest
    return new CompositeAndroidManifest(Fs.fileFromPath(manifestFile), excluded.getResDirectory(),
        excluded.getAssetsDirectory(), builder.build());
  }

  private String getManifestFile(final FsFile possibleManifestFile) {
    String manifestFile;
    // Robolectric might choose to absolutize the file path. This is bad if the path
    // was actually a label. We fix it.
    String possibleManifestLabel = possibleManifestFile.getPath();
    if (possibleManifestLabel.startsWith(robolectricDir.getPath())) {
      possibleManifestLabel = possibleManifestLabel.substring(robolectricDir.getPath().length());
    }
    possibleManifestLabel = "/" + possibleManifestLabel;

    if (!possibleManifestLabel.contains(":")) {
      // Manifests must be specified by label
      throw new IllegalArgumentException(String.format("Supplying an AndroidManifest.xml with an"
          + " absolute path is deprecated. Use a label instead: %s.",
          labelAliasMap.keySet()));
    }
    if (labelAliasMap.containsKey(possibleManifestLabel)) {
      manifestFile = labelAliasMap.get(possibleManifestLabel);
    } else {
      // No alias was found... try the given manifest instead.
      manifestFile = possibleManifestFile.getPath();
    }

    if (directManifests.get(manifestFile) == null) {
      throw new IllegalArgumentException(
          String.format("The manifest '%s' does not exist in the current test."
              + " Available manifests: %s", possibleManifestFile, labelAliasMap.keySet()));
    }

    return manifestFile;
  }
}
