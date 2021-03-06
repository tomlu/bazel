// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.analysis.BaseRuleClasses.ACTION_LISTENER;
import static com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode.TARGET;
import static com.google.devtools.build.lib.packages.Attribute.ConfigurationTransition.HOST;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil.NullAction;
import com.google.devtools.build.lib.analysis.BuildView.AnalysisResult;
import com.google.devtools.build.lib.analysis.util.AnalysisTestCase;
import com.google.devtools.build.lib.analysis.util.TestAspects;
import com.google.devtools.build.lib.analysis.util.TestAspects.AspectApplyingToFiles;
import com.google.devtools.build.lib.analysis.util.TestAspects.AspectInfo;
import com.google.devtools.build.lib.analysis.util.TestAspects.AspectRequiringRule;
import com.google.devtools.build.lib.analysis.util.TestAspects.BaseRule;
import com.google.devtools.build.lib.analysis.util.TestAspects.DummyRuleFactory;
import com.google.devtools.build.lib.analysis.util.TestAspects.RuleInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.OutputFilter.RegexOutputFilter;
import com.google.devtools.build.lib.packages.AspectDefinition;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.Attribute.LateBoundDefault;
import com.google.devtools.build.lib.packages.NativeAspectClass;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.skyframe.AspectValue;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for aspect creation and merging with configured targets.
 *
 * <p>Uses the complete analysis machinery and depends on custom rules so that behaviors related to
 * aspects can be tested even if they aren't used by regular rules.
 */
@RunWith(JUnit4.class)
public class AspectTest extends AnalysisTestCase {

  private void pkg(String name, String... contents) throws Exception {
    scratch.file("" + name + "/BUILD", contents);
  }

  @Test
  public void testAspectAppliedToAliasWithSelect() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(), new AspectRequiringRule());
    pkg("a",
        "aspect(name='a', foo=[':b'])",
        "alias(name='b', actual=select({'//conditions:default': ':c'}))",
        "base(name='c')");
    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData())
        .containsExactly("aspect //a:c", "rule //a:a");
  }

  @Test
  public void testAspectAppliedToChainedAliases() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(), new AspectRequiringRule());
    pkg("a",
        "aspect(name='a', foo=[':b'])",
        "alias(name='b', actual=':c')",
        "alias(name='c', actual=':d')",
        "alias(name='d', actual=':e')",
        "base(name='e')");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData())
        .containsExactly("aspect //a:e", "rule //a:a");
  }

  @Test
  public void testAspectAppliedToChainedAliasesAndSelect() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(), new AspectRequiringRule());
    pkg("a",
        "aspect(name='a', foo=[':b'])",
        "alias(name='b', actual=select({'//conditions:default': ':c'}))",
        "alias(name='c', actual=select({'//conditions:default': ':d'}))",
        "base(name='d')");
    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData())
        .containsExactly("aspect //a:d", "rule //a:a");
  }

  @Test
  public void providersOfAspectAreMergedIntoDependency() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(), new AspectRequiringRule());
    pkg("a",
        "aspect(name='a', foo=[':b'])",
        "aspect(name='b', foo=[])");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData())
        .containsExactly("aspect //a:b", "rule //a:a");
  }

  @Test
  public void aspectIsNotCreatedIfAdvertisedProviderIsNotPresent() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(), new TestAspects.LiarRule(),
        new TestAspects.AspectRequiringProviderRule());

    pkg("a",
        "aspect_requiring_provider(name='a', foo=[':b'])",
        "liar(name='b', foo=[])");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData()).containsExactly("rule //a:a");
  }

  @Test
  public void aspectIsNotCreatedIfAdvertisedProviderIsNotPresentWithAlias() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(), new TestAspects.LiarRule(),
        new TestAspects.AspectRequiringProviderRule());

    pkg("a",
        "aspect_requiring_provider(name='a', foo=[':b'])",
        "alias(name = 'b_alias', actual = ':b')",
        "liar(name='b', foo=[])");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData()).containsExactly("rule //a:a");
  }

  @Test
  public void aspectIsNotPropagatedThroughLiars() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(),
        new TestAspects.LiarRule(),
        new TestAspects.HonestRule(),
        new TestAspects.AspectRequiringProviderRule());

    pkg("a",
        "aspect_requiring_provider(name='a', foo=[':b_alias'])",
        "alias(name = 'b_alias', actual = ':b')",
        "liar(name='b', foo=[':c'])",
        "honest(name = 'c', foo = [])"
    );

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData()).containsExactly("rule //a:a");
  }

  @Test
  public void aspectPropagatedThroughAliasRule() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(), new TestAspects.HonestRule(),
        new TestAspects.AspectRequiringProviderRule());

    pkg("a",
        "aspect_requiring_provider(name='a', foo=[':b_alias'])",
        "alias(name = 'b_alias', actual = ':b')",
        "honest(name='b', foo=[])");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData()).containsExactly(
        "rule //a:a", "aspect //a:b");
  }

  @Test
  public void aspectPropagatedThroughAliasRuleAndHonestRules() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(), new TestAspects.HonestRule(),
        new TestAspects.AspectRequiringProviderRule());

    pkg("a",
        "aspect_requiring_provider(name='a', foo=[':b'])",
        "alias(name = 'b_alias', actual = ':b')",
        "honest(name='b', foo=[':c'])",
        "honest(name='c', foo=[])"
    );

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData()).containsExactly(
        "rule //a:a", "aspect //a:b", "aspect //a:c");
  }





  @Test
  public void aspectCreationWorksThroughBind() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(), new TestAspects.HonestRule(),
        new TestAspects.AspectRequiringProviderRule());

    pkg("a",
        "aspect_requiring_provider(name='a', foo=['//external:b'])",
        "honest(name='b', foo=[])");

    scratch.overwriteFile("WORKSPACE",
        new ImmutableList.Builder<String>()
            .addAll(analysisMock.getWorkspaceContents(mockToolsConfig))
            .add("bind(name='b', actual='//a:b')")
            .build());

    skyframeExecutor.invalidateFilesUnderPathForTesting(reporter,
        ModifiedFileSet.EVERYTHING_MODIFIED, rootDirectory);

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData())
        .containsExactly("rule //a:a", "aspect //a:b");
  }


  @Test
  public void aspectCreatedIfAdvertisedProviderIsPresent() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(), new TestAspects.HonestRule(),
        new TestAspects.AspectRequiringProviderRule());

    pkg("a",
        "aspect_requiring_provider(name='a', foo=[':b'])",
        "honest(name='b', foo=[])");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData())
        .containsExactly("rule //a:a", "aspect //a:b");
  }

  @Test
  public void aspectCreatedIfAtLeastOneSetOfAdvertisedProvidersArePresent() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(), new TestAspects.HonestRule(),
        new TestAspects.HonestRule2(), new TestAspects.AspectRequiringProviderSetsRule());

    pkg("a",
        "aspect_requiring_provider_sets(name='a', foo=[':b', ':c'])",
        "honest(name='b', foo=[])",
        "honest2(name='c', foo=[])");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData())
        .containsExactly("rule //a:a", "aspect //a:b", "aspect //a:c");
  }

  @Test
  public void aspectWithParametrizedDefinition() throws Exception {
    setRulesAvailableInTests(
        new TestAspects.BaseRule(),
        new TestAspects.HonestRule(),
        new TestAspects.ParametrizedDefinitionAspectRule());

    pkg(
        "a",
        "honest(name='q', foo=[])",
        "parametrized_definition_aspect(name='a', foo=[':b'], baz='//a:q')",
        "honest(name='c', foo=[])",
        "honest(name='b', foo=[':c'])");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(TestAspects.RuleInfo.class).getData())
        .containsExactly(
            "rule //a:a",
            "aspect //a:b data //a:q $dep:[ //a:q]",
            "aspect //a:c data //a:q $dep:[ //a:q]");
  }

  @Test
  public void aspectInError() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(), new TestAspects.ErrorAspectRule(),
        new TestAspects.SimpleRule());

    pkg("a",
        "simple(name='a', foo=[':b'])",
        "error_aspect(name='b', foo=[':c'])",
        "simple(name='c')");

    reporter.removeHandler(failFastHandler);
    // getConfiguredTarget() uses a separate code path that does not hit
    // SkyframeBuildView#configureTargets
    try {
      update("//a:a");
      fail();
    } catch (ViewCreationFailedException e) {
      // expected
    }
    assertContainsEvent("Aspect error");
  }

  @Test
  public void transitiveAspectInError() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(), new TestAspects.ErrorAspectRule(),
        new TestAspects.SimpleRule());

    pkg("a",
        "error_aspect(name='a', foo=[':b'])",
        "error_aspect(name='b', bar=[':c'])",
        "error_aspect(name='c', bar=[':d'])",
        "error_aspect(name='d')");

    reporter.removeHandler(failFastHandler);
    // getConfiguredTarget() uses a separate code path that does not hit
    // SkyframeBuildView#configureTargets
    try {
      update("//a:a");
      fail();
    } catch (ViewCreationFailedException e) {
      // expected
    }
    assertContainsEvent("Aspect error");
  }

  @Test
  public void aspectDependenciesDontShowDeprecationWarnings() throws Exception {
    setRulesAvailableInTests(
        new TestAspects.BaseRule(), new TestAspects.ExtraAttributeAspectRule());

    pkg("extra", "base(name='extra', deprecation='bad aspect')");

    pkg("a",
        "rule_with_extra_deps_aspect(name='a', foo=[':b'])",
        "base(name='b')");

    getConfiguredTarget("//a:a");
    assertContainsEventWithFrequency("bad aspect", 0);
  }

  @Test
  public void ruleDependencyDeprecationWarningsAbsentDuringAspectEvaluations() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(), new TestAspects.AspectRequiringRule());

    pkg("a", "aspect(name='a', foo=['//b:b'])");
    pkg("b", "aspect(name='b', bar=['//d:d'])");
    pkg("d", "base(name='d', deprecation='bad rule')");

    getConfiguredTarget("//a:a");
    assertContainsEventWithFrequency("bad rule", 1);
  }

  @Test
  public void aspectWarningsFilteredByOutputFiltersForAssociatedRules() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(), new TestAspects.WarningAspectRule());
    pkg("a", "warning_aspect(name='a', foo=['//b:b', '//c:c'])");
    pkg("b", "base(name='b')");
    pkg("c", "base(name='c')");

    reporter.setOutputFilter(RegexOutputFilter.forPattern(Pattern.compile("^//b:")));

    getConfiguredTarget("//a:a");
    assertContainsEventWithFrequency("Aspect warning on //b:b", 1);
    assertContainsEventWithFrequency("Aspect warning on //c:c", 0);
  }

  @Test
  public void sameTargetInDifferentAttributes() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(), new TestAspects.AspectRequiringRule(),
        new TestAspects.SimpleRule());
    pkg("a",
        "aspect(name='a', foo=[':b'], bar=[':b'])",
        "aspect(name='b', foo=[])");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData())
        .containsExactly("aspect //a:b", "rule //a:a");
  }

  @Test
  public void sameTargetInDifferentAttributesWithDifferentAspects() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(), new TestAspects.MultiAspectRule(),
        new TestAspects.SimpleRule());
    pkg("a",
        "multi_aspect(name='a', foo=':b', bar=':b')",
        "simple(name='b')");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData()).containsExactly("foo", "bar");
  }

  @Test
  public void informationFromBaseRulePassedToAspect() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(), new TestAspects.HonestRule(),
        new TestAspects.AspectRequiringProviderRule());

    pkg("a",
        "aspect_requiring_provider(name='a', foo=[':b'], baz='hello')",
        "honest(name='b', foo=[])");

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData())
        .containsExactly("rule //a:a", "aspect //a:b data hello");
  }

  /**
   * Rule definitions to be used in emptyAspectAttributesAreAvailableInRuleContext().
   */
  public static class EmptyAspectAttributesAreAvailableInRuleContext {
    public static class TestRule implements RuleDefinition {
      @Override
      public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment environment) {
        return builder
            .add(attr("foo", LABEL_LIST).legacyAllowAnyFileType()
                .aspect(ASPECT_WITH_EMPTY_LATE_BOUND_ATTRIBUTE))
            .build();
      }

      @Override
      public Metadata getMetadata() {
        return RuleDefinition.Metadata.builder().name("testrule")
            .factoryClass(DummyRuleFactory.class).ancestors(BaseRule.class).build();
      }
    }

    public static class AspectWithEmptyLateBoundAttribute extends NativeAspectClass
      implements ConfiguredAspectFactory {
      @Override
      public AspectDefinition getDefinition(AspectParameters params) {
        return new AspectDefinition.Builder(this)
            .add(attr(":late", LABEL).value(LateBoundDefault.alwaysNull()))
            .build();
      }

      @Override
      public ConfiguredAspect create(
          ConfiguredTarget base, RuleContext ruleContext, AspectParameters parameters)
          throws InterruptedException {
        Object lateBoundPrereq = ruleContext.getPrerequisite(":late", TARGET);
        return new ConfiguredAspect.Builder(this, parameters, ruleContext)
            .addProvider(
                AspectInfo.class,
                new AspectInfo(
                    NestedSetBuilder.create(
                        Order.STABLE_ORDER, lateBoundPrereq != null ? "non-empty" : "empty")))
            .build();
      }
    }
    public static final AspectWithEmptyLateBoundAttribute ASPECT_WITH_EMPTY_LATE_BOUND_ATTRIBUTE =
        new AspectWithEmptyLateBoundAttribute();
  }

  /**
   * An Aspect has a late-bound attribute with no value (that is, a LateBoundDefault whose
   * getDefault() returns `null`). Test that this attribute is available in the RuleContext which is
   * provided to the Aspect's `create()` method.
   */
  @Test
  public void emptyAspectAttributesAreAvailableInRuleContext() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(),
        new EmptyAspectAttributesAreAvailableInRuleContext.TestRule());
    pkg("a",
        "testrule(name='a', foo=[':b'])",
        "testrule(name='b')");
    ConfiguredTarget a = getConfiguredTarget("//a:a");
    assertThat(a.getProvider(RuleInfo.class).getData()).contains("empty");
  }

  /**
   * Rule definitions to be used in extraActionsAreEmitted().
   */
  public static class ExtraActionsAreEmitted {
    public static class TestRule implements RuleDefinition {
      @Override
      public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment environment) {
        return builder
            .add(attr("foo", LABEL_LIST).legacyAllowAnyFileType()
                .aspect(ASPECT_THAT_REGISTERS_ACTION))
            .add(attr(":action_listener", LABEL_LIST).cfg(HOST).value(ACTION_LISTENER))
            .build();
      }

      @Override
      public Metadata getMetadata() {
        return RuleDefinition.Metadata.builder().name("testrule")
            .factoryClass(DummyRuleFactory.class).ancestors(BaseRule.class).build();
      }
    }

    public static class AspectThatRegistersAction extends NativeAspectClass
      implements ConfiguredAspectFactory {
      @Override
      public AspectDefinition getDefinition(AspectParameters params) {
        return new AspectDefinition.Builder(this).build();
      }



      @Override
      public ConfiguredAspect create(
          ConfiguredTarget base, RuleContext ruleContext, AspectParameters parameters)
              throws InterruptedException {
        ruleContext.registerAction(new NullAction(ruleContext.createOutputArtifact()));
        return new ConfiguredAspect.Builder(this, parameters, ruleContext).build();
      }
    }
    private static final AspectThatRegistersAction ASPECT_THAT_REGISTERS_ACTION =
        new AspectThatRegistersAction();
  }

  /**
   * Test that actions registered in an Aspect are reported as extra-actions on the attached rule.
   * AspectThatRegistersAction registers a NullAction, whose mnemonic is "Null". We have an
   * action_listener that targets that mnemonic, which makes sure the Aspect machinery will expose
   * an ExtraActionArtifactsProvider.
   * The rule //a:a doesn't have an aspect, so the only action we get is the one on //a:b
   * (which does have an aspect).
   */
  @Test
  public void extraActionsAreEmitted() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(),
        new ExtraActionsAreEmitted.TestRule());
    useConfiguration("--experimental_action_listener=//extra_actions:listener");
    scratch.file(
        "extra_actions/BUILD",
        "extra_action(name='xa', cmd='echo dont-care')",
        "action_listener(name='listener', mnemonics=['Null'], extra_actions=[':xa'])");
    pkg("a",
        "testrule(name='a', foo=[':b'])",
        "testrule(name='b')");
    update();

    ConfiguredTarget a = getConfiguredTarget("//a:a");
    NestedSet<Artifact> extraActionArtifacts =
        a.getProvider(ExtraActionArtifactsProvider.class).getTransitiveExtraActionArtifacts();
    for (Artifact artifact : extraActionArtifacts) {
      assertThat(artifact.getOwnerLabel()).isEqualTo(Label.create("@//a", "b"));
    }
  }

  @Test
  public void aspectPropagatesToAllAttributes() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(),
        new TestAspects.SimpleRule(),
        new TestAspects.AllAttributesAspectRule());
    pkg("a",
        "simple(name='a', foo=[':b'], foo1=':c', txt='some text')",
        "simple(name='b', foo=[], txt='some text')",
        "simple(name='c', foo=[], txt='more text')",
        "all_attributes_aspect(name='x', foo=[':a'])");

    ConfiguredTarget a = getConfiguredTarget("//a:x");
    assertThat(a.getProvider(RuleInfo.class).getData())
        .containsExactly("aspect //a:a",  "aspect //a:b", "aspect //a:c", "rule //a:x");
  }

  /**
   * Tests that when --experimental_extra_action_top_level_only, Blaze reports extra-actions for
   * actions registered by Aspects injected by a top-level rule. Because we can't know whether an
   * aspect was injected by a top-level target or one of its children, we approximate it by only
   * reporting extra-actions from Aspects that the top-level target could have injected.
   *
   * <p>Here, injector1() and injector2() inject aspects into their children. null_rule() just
   * passes the aspects to its children. The test makes sure that actions registered by aspect1
   * (injected by injector1()) are reported to the extra-action mechanism. Actions registered by
   * aspect2 (from injector2) are not reported, because the target under test (//x:a) doesn't inject
   * aspect2.
   */
  @Test
  public void extraActionsAreEmitted_topLevel() throws Exception {
    useConfiguration(
        "--experimental_action_listener=//pkg1:listener",
        "--experimental_extra_action_top_level_only");

    scratch.file(
        "x/BUILD",
        "load(':extension.bzl', 'injector1', 'injector2', 'null_rule')",
        "injector1(name='a', deps=[':b'])",
        "null_rule(name='b', deps=[':c'])",
        "null_rule(name='c', deps=[':d'])",
        "injector2(name = 'd', extra_deps=[':e'])",
        "null_rule(name = 'e')");

    scratch.file(
        "x/extension.bzl",
        "def _aspect_impl(target, ctx):",
        "  ctx.actions.do_nothing(mnemonic='Mnemonic')",
        "  return struct()",
        "aspect1 = aspect(_aspect_impl, attr_aspects=['deps'])",
        "aspect2 = aspect(_aspect_impl, attr_aspects=['extra_deps'])",
        "def _rule_impl(ctx):",
        "  return struct()",
        "injector1 = rule(_rule_impl, attrs = { 'deps' : attr.label_list(aspects = [aspect1]) })",
        "null_rule = rule(_rule_impl, attrs = { 'deps' : attr.label_list() })",
        "injector2 = rule(",
        "  _rule_impl, attrs = { 'extra_deps' : attr.label_list(aspects = [aspect2]) })");

    scratch.file(
        "pkg1/BUILD",
        "extra_action(name='xa', cmd='echo dont-care')",
        "action_listener(name='listener', mnemonics=['Mnemonic'], extra_actions=[':xa'])");

    // Sanity check: //x:d injects an aspect which produces some extra-action.
    {
      BuildView.AnalysisResult analysisResult = update("//x:d");

      // Get owners of all extra-action artifacts.
      List<Label> extraArtifactOwners = new ArrayList<>();
      for (Artifact artifact : analysisResult.getAdditionalArtifactsToBuild()) {
        if (artifact.getRootRelativePathString().endsWith(".xa")) {
          extraArtifactOwners.add(artifact.getOwnerLabel());
        }
      }
      assertThat(extraArtifactOwners).containsExactly(Label.create("@//x", "e"));
    }

    // Actual test: //x:a reports actions registered by the aspect it injects.
    {
      BuildView.AnalysisResult analysisResult = update("//x:a");

      // Get owners of all extra-action artifacts.
      List<Label> extraArtifactOwners = new ArrayList<>();
      for (Artifact artifact : analysisResult.getAdditionalArtifactsToBuild()) {
        if (artifact.getRootRelativePathString().endsWith(".xa")) {
          extraArtifactOwners.add(artifact.getOwnerLabel());
        }
      }
      assertThat(extraArtifactOwners)
          .containsExactly(
              Label.create("@//x", "b"), Label.create("@//x", "c"), Label.create("@//x", "d"));
    }
  }

  @Test
  public void extraActionsFromDifferentAspectsDontConflict() throws Exception {
    useConfiguration(
        "--experimental_action_listener=//pkg1:listener",
        "--experimental_extra_action_top_level_only");

    scratch.file(
        "x/BUILD",
        "load(':extension.bzl', 'injector1', 'injector2', 'null_rule')",
        "injector2(name='i2_a', deps = [':i1_a'])",
        "injector1(name='i1_a', deps=[':n'], param = 'a')",
        "injector1(name='i1_b', deps=[':n'], param = 'b')",
        "injector2(name='i2', deps=[':n'])",
        "null_rule(name = 'n')");

    scratch.file(
        "x/extension.bzl",
        "def _aspect_impl(target, ctx):",
        "  ctx.actions.do_nothing(mnemonic='Mnemonic')",
        "  return struct()",
        "aspect1 = aspect(_aspect_impl, attr_aspects=['deps'], attrs =",
        "    {'param': attr.string(values = ['a', 'b'])})",
        "aspect2 = aspect(_aspect_impl, attr_aspects=['deps'])",
        "def _rule_impl(ctx):",
        "  return struct()",
        "injector1 = rule(_rule_impl, attrs =",
        "    { 'deps' : attr.label_list(aspects = [aspect1]), 'param' : attr.string() })",
        "injector2 = rule(_rule_impl, attrs = { 'deps' : attr.label_list(aspects = [aspect2]) })",
        "null_rule = rule(_rule_impl, attrs = { 'deps' : attr.label_list() })"
    );

    scratch.file(
        "pkg1/BUILD",
        "extra_action(name='xa', cmd='echo dont-care')",
        "action_listener(name='listener', mnemonics=['Mnemonic'], extra_actions=[':xa'])");

    update("//x:i1_a", "//x:i1_b", "//x:i2", "//x:i2_a");

    // Implicitly check that update() didn't throw an exception because of two actions producing
    // the same outputs.
  }

  @Test
  public void aspectPropagatesToAllAttributesImplicit() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(),
        new TestAspects.SimpleRule(),
        new TestAspects.ImplicitDepRule(),
        new TestAspects.AllAttributesAspectRule());

    scratch.file(
        "extra/BUILD",
        "simple(name ='extra')"
    );
    pkg("a",
        "simple(name='a', foo=[':b'], foo1=':c', txt='some text')",
        "simple(name='b', foo=[], txt='some text')",
        "implicit_dep(name='c')",
        "all_attributes_aspect(name='x', foo=[':a'])");
    update();

    ConfiguredTarget a = getConfiguredTarget("//a:x");
    assertThat(a.getProvider(RuleInfo.class).getData())
        .containsExactly(
            "aspect //a:a",
            "aspect //a:b",
            "aspect //a:c",
            "aspect //extra:extra",
            "rule //a:x");
  }


  @Test
  public void aspectPropagatesToAllAttributesLateBound() throws Exception {
    setRulesAvailableInTests(new TestAspects.BaseRule(),
        new TestAspects.SimpleRule(),
        new TestAspects.LateBoundDepRule(),
        new TestAspects.AllAttributesAspectRule());

    scratch.file(
        "extra/BUILD",
        "simple(name ='extra')"
    );
    pkg("a",
        "simple(name='a', foo=[':b'], foo1=':c', txt='some text')",
        "simple(name='b', foo=[], txt='some text')",
        "late_bound_dep(name='c')",
        "all_attributes_aspect(name='x', foo=[':a'])");
    useConfiguration("--plugin=//extra:extra");
    update();

    ConfiguredTarget a = getConfiguredTarget("//a:x");
    assertThat(a.getProvider(RuleInfo.class).getData())
        .containsExactly(
            "aspect //a:a",
            "aspect //a:b",
            "aspect //a:c",
            "aspect //extra:extra",
            "rule //a:x");
  }

  /**
   * Ensures an aspect with attr = '*' doesn't try to propagate to its own implicit attributes.
   * Doing so leads to a dependency cycle.
   */
  @Test
  public void aspectWithAllAttributesDoesNotPropagateToOwnImplicitAttributes() throws Exception {
    setRulesAvailableInTests(
        new TestAspects.BaseRule(),
        new TestAspects.SimpleRule(),
        new TestAspects.AllAttributesWithToolAspectRule());
    pkg(
        "a",
        "simple(name='tool')",
        "simple(name='a')",
        "all_attributes_with_tool_aspect(name='x', foo=[':a'])");

    ConfiguredTarget a = getConfiguredTarget("//a:x");
    assertThat(a.getProvider(RuleInfo.class).getData())
        .containsExactly("aspect //a:a", "rule //a:x");
  }

  /**
   * Makes sure the aspect *will* propagate to its implicit attributes if there is a "regular"
   * dependency path to it (i.e. not through its own implicit attributes).
   */
  @Test
  public void aspectWithAllAttributesPropagatesToItsToolIfThereIsPath() throws Exception {
    setRulesAvailableInTests(
        new TestAspects.BaseRule(),
        new TestAspects.SimpleRule(),
        new TestAspects.AllAttributesWithToolAspectRule());
    pkg(
        "a",
        "simple(name='tool')",
        "simple(name='a', foo=[':b'], foo1=':c', txt='some text')",
        "simple(name='b', foo=[], txt='some text')",
        "simple(name='c', foo=[':tool'], txt='more text')",
        "all_attributes_with_tool_aspect(name='x', foo=[':a'])");

    ConfiguredTarget a = getConfiguredTarget("//a:x");
    assertThat(a.getProvider(RuleInfo.class).getData())
        .containsExactly(
            "aspect //a:a", "aspect //a:b", "aspect //a:c", "aspect //a:tool", "rule //a:x");
  }

  @Test
  public void aspectTruthInAdvertisement() throws Exception {
    reporter.removeHandler(failFastHandler); // expect errors
    setRulesAvailableInTests(
        new TestAspects.BaseRule(),
        new TestAspects.SimpleRule(),
        new TestAspects.FalseAdvertisementAspectRule());
    pkg(
        "a",
        "simple(name = 's')",
        "false_advertisement_aspect(name = 'x', deps = [':s'])"
    );
    try {
      update("//a:x");
    } catch (ViewCreationFailedException e) {
      // expected.
    }
    assertContainsEvent(
        "Aspect 'FalseAdvertisementAspect', applied to '//a:s',"
            + " does not provide advertised provider 'RequiredProvider'");
    assertContainsEvent(
        "Aspect 'FalseAdvertisementAspect', applied to '//a:s',"
            + " does not provide advertised provider 'advertised_provider'");
  }

  @Test
  public void aspectApplyingToFiles() throws Exception {
    AspectApplyingToFiles aspectApplyingToFiles = new AspectApplyingToFiles();
    setRulesAndAspectsAvailableInTests(
        ImmutableList.<NativeAspectClass>of(aspectApplyingToFiles),
        ImmutableList.<RuleDefinition>of());
    pkg(
        "a",
        "java_binary(name = 'x', main_class = 'x.FooBar', srcs = ['x.java'])"
    );
    AnalysisResult analysisResult = update(new EventBus(), defaultFlags(),
        ImmutableList.of(aspectApplyingToFiles.getName()),
        "//a:x_deploy.jar");
    AspectValue aspect = Iterables.getOnlyElement(analysisResult.getAspects());
    AspectApplyingToFiles.Provider provider =
        aspect.getConfiguredAspect().getProvider(AspectApplyingToFiles.Provider.class);
    assertThat(provider.getLabel())
        .isEqualTo(Label.parseAbsoluteUnchecked("//a:x_deploy.jar"));
  }

  @Test
  public void aspectApplyingToSourceFilesIgnored() throws Exception {
    AspectApplyingToFiles aspectApplyingToFiles = new AspectApplyingToFiles();
    setRulesAndAspectsAvailableInTests(
        ImmutableList.<NativeAspectClass>of(aspectApplyingToFiles),
        ImmutableList.<RuleDefinition>of());
    pkg(
        "a",
        "java_binary(name = 'x', main_class = 'x.FooBar', srcs = ['x.java'])"
    );
    scratch.file("a/x.java", "");
    AnalysisResult analysisResult = update(new EventBus(), defaultFlags(),
        ImmutableList.of(aspectApplyingToFiles.getName()),
        "//a:x.java");
    AspectValue aspect = Iterables.getOnlyElement(analysisResult.getAspects());
    assertThat(aspect.getConfiguredAspect().getProvider(AspectApplyingToFiles.Provider.class))
        .isNull();
  }

}
