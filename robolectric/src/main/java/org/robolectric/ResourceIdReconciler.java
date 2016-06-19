package org.robolectric;

import static com.google.common.base.MoreObjects.firstNonNull;

import org.robolectric.manifest.AndroidManifest;
import org.robolectric.res.ResourcePath;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Reconciles library R.java files. Every library may have an R.java file. A particular resource
 * may be referenced by multiple R.java files and given different resource IDs there. The
 * reconciler ensures that a given resource has the same resource IDs in all R.java files
 * where it's declared.
 *
 * <p>The reconciler will not handle correctly the case where multiple libraries define
 * different resources with the same name.
 */
class ResourceIdReconciler {

  private static final boolean DEBUG = false;

  private final AndroidManifest manifest;
  private int packageOffset = 1;

  ResourceIdReconciler(AndroidManifest manifest) {
    this.manifest = manifest;
  }

  public void reconcile() {
    if (manifest.getRClass() != null) {
      HashMap<String, Integer> resIds = new HashMap<>();
      reconcileResourceIds(new Class<?>[]{manifest.getRClass()}, resIds, 0);
      reconcileResourceIdsInLibraries(
          manifest.getLibraryManifests(), resIds, new HashSet<String>());
    } else {
      // TODO(b/20004399): Enforce that it's non-null.
      System.err.println("WARNING: no R.class in package " + manifest.getPackageName());
    }
  }

  private void reconcileResourceIdsInLibraries(List<AndroidManifest> manifests,
      HashMap<String, Integer> resIds, HashSet<String> processedPackages) {
    for (AndroidManifest libManifest :
        firstNonNull(manifests, Collections.<AndroidManifest>emptyList())) {
      for (ResourcePath resourcePath : firstNonNull(
          libManifest.getIncludedResourcePaths(), Collections.<ResourcePath>emptyList())) {
        reconcileResourceIdsInPackage(resourcePath, resIds, processedPackages);
      }
      reconcileResourceIdsInLibraries(libManifest.getLibraryManifests(), resIds, processedPackages);
    }
  }

  private void reconcileResourceIdsInPackage(
      ResourcePath resourcePath,
      HashMap<String, Integer> resIds,
      HashSet<String> processedPackages) {
    if (processedPackages.contains(resourcePath.getPackageName())) {
      return;
    }

    if (resourcePath.rClasses != null) {
      reconcileResourceIds(resourcePath.rClasses, resIds, packageOffset++);
      processedPackages.add(resourcePath.getPackageName());
    } else if (DEBUG) {
      System.out.println(
          "DEBUG: no R.class in package " + resourcePath.getPackageName() + " - skipping");
    }
  }

  /**
   * IDs are in the format
   *
   * 0x PPTTIIII
   *
   * where:
   *
   * P is unique for the package
   * T is unique for the type
   * I is the identifier within that type.
   */
  private static void reconcileResourceIds(Class<?>[] rClasses, HashMap<String, Integer> values,
      int offset) {
    Class<?>[] innerClasses = rClasses;
    for (int i = 0; i < innerClasses.length; i++) {
      String resourceType = innerClasses[i].getSimpleName();
      if (!resourceType.startsWith("styleable")) {
        Field[] fields = innerClasses[i].getFields();
        for (int j = 0; j < fields.length; j++) {
          try {
            // Fixes a bug with the reconciler, when merging two libraries worth
            // of resources, we need to make sure entry is globally unique, when combined.
            String resourceName = resourceType + "/" + fields[j].getName();
            if (!Modifier.isFinal(fields[j].getModifiers())) {
              // Shift the package offset 6 places, 24 binary positions.
              fields[j].setInt(null, fields[j].getInt(null) - (offset << 24));
            }
            Integer value = values.get(resourceName);
            if (value != null) {
              if (!Modifier.isFinal(fields[j].getModifiers())) {
                fields[j].setAccessible(true);
                fields[j].setInt(null, value);
              }
            } else {
              values.put(resourceName, fields[j].getInt(null));
            }
          } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
          }
        }
      } else {
        Field[] fields = innerClasses[i].getFields();

        // First retrieve a mapping of index to name.
        HashMap<String, String> fieldAliasToFieldName = new HashMap<>();
        for (int j = 0; j < fields.length; j++) {
          if (fields[j].getType().equals(int.class)) {
            int idx = fields[j].getName().length();
            // Style names, as well as attributes, can have underscores.
            // Since the resulting R.class must be unambiguous, we can brute-force until we
            // find a valid styleable array.
            // We must go from best-to-worst match.
            // e.g. a class may have styleable Theme and Theme_Custom
            while (--idx >= 0) {
              if (fields[j].getName().charAt(idx) == '_') {
                try {
                  innerClasses[i].getDeclaredField(fields[j].getName().substring(0, idx));
                  break;
                } catch (NoSuchFieldException e) { /* continue */ }
              }
            }
            // Forcing us to calculate this again will crash if something has gone bad.
            String styleName = fields[j].getName().substring(0, idx);
            try {
              int index = fields[j].getInt(null);
              fieldAliasToFieldName.put(styleName + index, fields[j].getName());
            } catch (IllegalAccessException e) {
              throw new RuntimeException(e);
            }
          }
        }

        // Now loop again, and reassign the ids in the style arrays accordingly.
        for (int j = 0; j < fields.length; j++) {
          if (fields[j].getType().equals(int[].class)) {
            try {
              int[] styleableArray = (int[]) (fields[j].get(null));
              for (int k = 0; k < styleableArray.length; k++) {
                String fieldName = fieldAliasToFieldName.get(fields[j].getName() + k);
                String resourceName = resourceType + "/" + fieldName;
                Integer value = values.get(resourceName);
                if (value != null) {
                  styleableArray[k] = value;
                } else {
                  // Shift the package offset 6 places, 24 binary positions.
                  styleableArray[k] = styleableArray[k] - (offset << 24);
                  values.put(resourceName, styleableArray[k]);
                }
              }
            } catch (IllegalAccessException e) {
              throw new IllegalStateException(e);
            }
          }
        }
      }
    }
  }
}
