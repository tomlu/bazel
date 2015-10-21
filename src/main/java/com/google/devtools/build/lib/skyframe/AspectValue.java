// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.analysis.Aspect;
import com.google.devtools.build.lib.analysis.AspectWithParameters;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.AspectClass;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;

import javax.annotation.Nullable;

/**
 * An aspect in the context of the Skyframe graph.
 */
public final class AspectValue extends ActionLookupValue {

  /**
   * A base class for a key representing an aspect applied to a particular target.
   */
  public abstract static class AspectKey extends ActionLookupKey {
    protected final Label label;
    protected final BuildConfiguration configuration;

    protected AspectKey(Label label, BuildConfiguration configuration) {
      this.label = label;
      this.configuration = configuration;
    }

    @Override
    public Label getLabel() {
      return label;
    }

    public abstract AspectParameters getParameters();

    public abstract String getDescription();

    public BuildConfiguration getConfiguration() {
      return configuration;
    }
  }

  /**
   * The key of an action that is generated by a native aspect.
   */
  public static final class NativeAspectKey extends AspectKey {
    private final AspectWithParameters aspect;

    private NativeAspectKey(
        Label label,
        BuildConfiguration configuration,
        AspectClass aspectClass ,
        AspectParameters parameters) {
      super(label, configuration);
      Preconditions.checkNotNull(parameters);
      this.aspect = new AspectWithParameters(aspectClass, parameters);
    }

    public AspectClass getAspect() {
      return aspect.getAspectClass();
    }

    @Override
    @Nullable
    public AspectParameters getParameters() {
      return aspect.getParameters();
    }

    @Override
    public String getDescription() {
      return String.format("%s of %s", aspect.getAspectClass().getName(), getLabel());
    }

    @Override
    SkyFunctionName getType() {
      return SkyFunctions.NATIVE_ASPECT;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(label, configuration, aspect);
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }

      if (!(other instanceof NativeAspectKey)) {
        return false;
      }

      NativeAspectKey that = (NativeAspectKey) other;
      return Objects.equal(label, that.label)
          && Objects.equal(configuration, that.configuration)
          && Objects.equal(aspect, that.aspect);
    }

    @Override
    public String toString() {
      return label + "#" + aspect.getAspectClass().getName() + " "
          + (configuration == null ? "null" : configuration.checksum()) + " "
          + aspect.getParameters();
    }
  }

  /**
   * The key of an action that is generated by a skylark aspect.
   */
  public static class SkylarkAspectKey extends AspectKey {
    private final PackageIdentifier extensionFile;
    private final String skylarkFunctionName;

    private SkylarkAspectKey(
        Label targetLabel,
        BuildConfiguration targetConfiguration,
        PackageIdentifier extensionFile,
        String skylarkFunctionName) {
      super(targetLabel, targetConfiguration);
      this.extensionFile = extensionFile;
      this.skylarkFunctionName = skylarkFunctionName;
    }

    public PackageIdentifier getExtensionFile() {
      return extensionFile;
    }

    public String getSkylarkValueName() {
      return skylarkFunctionName;
    }

    @Override
    public AspectParameters getParameters() {
      return AspectParameters.EMPTY;
    }

    @Override
    public String getDescription() {
      // Skylark aspects are referred to on command line with <file>%<value name>
      return String.format(
          "%s%%%s of %s", extensionFile.toString(), skylarkFunctionName, getLabel());
    }

    @Override
    SkyFunctionName getType() {
      return SkyFunctions.SKYLARK_ASPECT;
    }
  }


  private final Label label;
  private final Location location;
  private final AspectKey key;
  private final Aspect aspect;
  private final NestedSet<Package> transitivePackages;

  public AspectValue(
      AspectKey key, Label label, Location location, Aspect aspect, Iterable<Action> actions,
      NestedSet<Package> transitivePackages) {
    super(actions);
    this.location = location;
    this.label = label;
    this.key = key;
    this.aspect = aspect;
    this.transitivePackages = transitivePackages;
  }

  public Aspect getAspect() {
    return aspect;
  }

  public Label getLabel() {
    return label;
  }

  public Location getLocation() {
    return location;
  }

  public AspectKey getKey() {
    return key;
  }

  public NestedSet<Package> getTransitivePackages() {
    return transitivePackages;
  }

  public static SkyKey key(
      Label label,
      BuildConfiguration configuration,
      AspectClass aspectFactory,
      AspectParameters additionalConfiguration) {
    return new SkyKey(
        SkyFunctions.NATIVE_ASPECT,
        new NativeAspectKey(label, configuration, aspectFactory, additionalConfiguration));
  }

  public static SkyKey key(AspectKey aspectKey) {
    return new SkyKey(aspectKey.getType(), aspectKey);
  }

  public static NativeAspectKey createAspectKey(
      Label label, BuildConfiguration configuration, AspectClass aspectFactory) {
    return new NativeAspectKey(label, configuration, aspectFactory, AspectParameters.EMPTY);
  }

  public static SkylarkAspectKey createSkylarkAspectKey(
      Label targetLabel,
      BuildConfiguration targetConfiguration,
      PackageIdentifier bzlFile,
      String skylarkFunctionName) {
    return new SkylarkAspectKey(targetLabel, targetConfiguration, bzlFile, skylarkFunctionName);
  }
}
