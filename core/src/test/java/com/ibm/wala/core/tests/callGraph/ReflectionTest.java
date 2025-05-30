/*
 * Copyright (c) 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 */
package com.ibm.wala.core.tests.callGraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.core.tests.util.TestConstants;
import com.ibm.wala.core.tests.util.WalaTestCase;
import com.ibm.wala.core.util.warnings.Warning;
import com.ibm.wala.core.util.warnings.Warnings;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisOptions.ReflectionOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.ContextKey;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.propagation.ConstantKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.LocalPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.ReceiverInstanceContext;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.Selector;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.WalaException;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Iterator2Collection;
import com.ibm.wala.util.collections.Iterator2Iterable;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.intset.OrdinalSet;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** Tests for Call Graph construction */
public class ReflectionTest extends WalaTestCase {

  public static void main(String[] args) {
    justThisTest(ReflectionTest.class);
  }

  private static AnalysisScope cachedScope;

  private static AnalysisScope findOrCreateAnalysisScope() throws IOException {
    if (cachedScope == null) {
      cachedScope =
          CallGraphTestUtil.makeJ2SEAnalysisScope(
              TestConstants.WALA_TESTDATA, "Java60RegressionExclusions.txt");
    }
    return cachedScope;
  }

  private static IClassHierarchy cachedCHA;

  private static IClassHierarchy findOrCreateCHA(AnalysisScope scope)
      throws ClassHierarchyException {
    if (cachedCHA == null) {
      cachedCHA = ClassHierarchyFactory.make(scope);
    }
    return cachedCHA;
  }

  @AfterAll
  public static void afterClass() {
    cachedCHA = null;
    cachedScope = null;
  }

  /** test that when analyzing Reflect1.main(), there is no warning about "Integer". */
  @Test
  public void testReflect1()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT1_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);

    Warnings.clear();
    CallGraphTest.doCallGraphs(options, new AnalysisCacheImpl(), cha);
    for (Warning w : Iterator2Iterable.make(Warnings.iterator())) {
      if (w.toString().indexOf("com/ibm/jvm") > 0) {
        continue;
      }
      if (w.toString().contains("Integer")) {
        fail(w.toString());
      }
    }
  }

  /**
   * Test that when analyzing reflect2, the call graph includes a node for
   * java.lang.Integer.<clinit>. This should be forced by the call for
   * Class.forName("java.lang.Integer").
   */
  @Test
  public void testReflect2()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT2_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);

    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Ljava/lang/Integer");
    MethodReference mr = MethodReference.findOrCreate(tr, "<clinit>", "()V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }

  /**
   * Check that when analyzing Reflect3, the successors of newInstance do not include
   * reflection/Reflect3$Hash
   */
  @Test
  public void testReflect3()
      throws IOException, ClassHierarchyException, IllegalArgumentException, CancelException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT3_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);

    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Ljava/lang/Class");
    MethodReference mr = MethodReference.findOrCreate(tr, "newInstance", "()Ljava/lang/Object;");
    Set<CGNode> newInstanceNodes = cg.getNodes(mr);
    Set<CGNode> succNodes = HashSetFactory.make();
    for (CGNode newInstanceNode : newInstanceNodes) {
      Iterator<? extends CGNode> succNodesIter = cg.getSuccNodes(newInstanceNode);
      while (succNodesIter.hasNext()) {
        succNodes.add(succNodesIter.next());
      }
    }
    TypeReference extraneousTR =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Reflect3$Hash");
    IClass hashClass = cha.lookupClass(extraneousTR);
    assert hashClass != null;
    MethodReference extraneousMR = MethodReference.findOrCreate(extraneousTR, "<init>", "()V");
    Set<CGNode> extraneousNodes = cg.getNodes(extraneousMR);
    succNodes.retainAll(extraneousNodes);
    assertTrue(succNodes.isEmpty());
  }

  /**
   * Check that when analyzing Reflect4, successors of newInstance() do not include FilePermission
   * ctor.
   */
  @Test
  public void testReflect4()
      throws IOException, ClassHierarchyException, IllegalArgumentException, CancelException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT4_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);

    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Ljava/lang/Class");
    MethodReference mr = MethodReference.findOrCreate(tr, "newInstance", "()Ljava/lang/Object;");
    Set<CGNode> newInstanceNodes = cg.getNodes(mr);
    Set<CGNode> succNodes = HashSetFactory.make();
    for (CGNode newInstanceNode : newInstanceNodes) {
      Iterator<? extends CGNode> succNodesIter = cg.getSuccNodes(newInstanceNode);
      while (succNodesIter.hasNext()) {
        succNodes.add(succNodesIter.next());
      }
    }
    TypeReference extraneousTR =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Ljava/io/FilePermission");
    MethodReference extraneousMR =
        MethodReference.findOrCreate(
            extraneousTR, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");
    Set<CGNode> extraneousNodes = cg.getNodes(extraneousMR);
    succNodes.retainAll(extraneousNodes);
    assertTrue(succNodes.isEmpty());
  }

  /**
   * Check that when analyzing Reflect5, successors of newInstance do not include a Reflect5$A ctor
   */
  @Test
  public void testReflect5()
      throws IOException, ClassHierarchyException, IllegalArgumentException, CancelException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT5_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);

    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Ljava/lang/Class");
    MethodReference mr = MethodReference.findOrCreate(tr, "newInstance", "()Ljava/lang/Object;");
    Set<CGNode> newInstanceNodes = cg.getNodes(mr);
    Set<CGNode> succNodes = HashSetFactory.make();
    for (CGNode newInstanceNode : newInstanceNodes) {
      Iterator<? extends CGNode> succNodesIter = cg.getSuccNodes(newInstanceNode);
      while (succNodesIter.hasNext()) {
        succNodes.add(succNodesIter.next());
      }
    }
    TypeReference extraneousTR =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Reflect5$A");
    MethodReference extraneousMR = MethodReference.findOrCreate(extraneousTR, "<init>", "()V");
    Set<CGNode> extraneousNodes = cg.getNodes(extraneousMR);
    succNodes.retainAll(extraneousNodes);
    assertTrue(succNodes.isEmpty());
  }

  /**
   * Check that when analyzing Reflect6, successors of newInstance do not include a Reflect6$A ctor
   */
  @Test
  public void testReflect6()
      throws IOException, ClassHierarchyException, IllegalArgumentException, CancelException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT6_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);

    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Ljava/lang/Class");
    MethodReference mr = MethodReference.findOrCreate(tr, "newInstance", "()Ljava/lang/Object;");
    Set<CGNode> newInstanceNodes = cg.getNodes(mr);
    Set<CGNode> succNodes = HashSetFactory.make();
    for (CGNode newInstanceNode : newInstanceNodes) {
      Iterator<? extends CGNode> succNodesIter = cg.getSuccNodes(newInstanceNode);
      while (succNodesIter.hasNext()) {
        succNodes.add(succNodesIter.next());
      }
    }
    TypeReference extraneousTR =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Reflect6$A");
    MethodReference extraneousMR = MethodReference.findOrCreate(extraneousTR, "<init>", "(I)V");
    Set<CGNode> extraneousNodes = cg.getNodes(extraneousMR);
    succNodes.retainAll(extraneousNodes);
    assertTrue(succNodes.isEmpty());
  }

  @Test
  public void testReflect7() throws Exception {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT7_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);

    final String mainClass = "Lreflection/Reflect7";
    TypeReference mainTr = TypeReference.findOrCreate(ClassLoaderReference.Application, mainClass);
    MethodReference mainMr = MethodReference.findOrCreate(mainTr, "main", "([Ljava/lang/String;)V");

    TypeReference constrTr =
        TypeReference.findOrCreate(
            ClassLoaderReference.Primordial, "Ljava/lang/reflect/Constructor");
    MethodReference newInstanceMr =
        MethodReference.findOrCreate(
            constrTr, "newInstance", "([Ljava/lang/Object;)Ljava/lang/Object;");

    String fpInitSig = "java.io.FilePermission.<init>(Ljava/lang/String;Ljava/lang/String;)V";
    String fpToStringSig = "java.security.Permission.toString()Ljava/lang/String;";

    Set<CGNode> mainNodes = cg.getNodes(mainMr);

    // Get all the children of the main node(s)
    Collection<CGNode> mainChildren = getSuccNodes(cg, mainNodes);

    // Verify that one of those children is Constructor.newInstance, where
    // Constructor is a FilePermission constructor
    CGNode filePermConstrNewInstanceNode = null;

    for (CGNode node : mainChildren) {
      Context context = node.getContext();
      if (context.isA(ReceiverInstanceContext.class)
          && node.getMethod().getReference().equals(newInstanceMr)) {
        @SuppressWarnings("unchecked")
        ConstantKey<IMethod> c = (ConstantKey<IMethod>) context.get(ContextKey.RECEIVER);
        IMethod ctor = c.getValue();
        if (ctor.getSignature().equals(fpInitSig)) {
          filePermConstrNewInstanceNode = node;
          break;
        }
      }
    }
    assertNotNull(filePermConstrNewInstanceNode);

    // Now verify that this node has FilePermission.<init> children
    CGNode filePermInitNode = null;

    Iterator<? extends CGNode> filePermConstrNewInstanceChildren =
        cg.getSuccNodes(filePermConstrNewInstanceNode);
    while (filePermConstrNewInstanceChildren.hasNext()) {
      CGNode node = filePermConstrNewInstanceChildren.next();
      if (node.getMethod().getSignature().equals(fpInitSig)) {
        filePermInitNode = node;
        break;
      }
    }
    assertNotNull(filePermInitNode);

    // Furthermore, verify that main has a FilePermission.toString child
    CGNode filePermToStringNode = null;
    for (CGNode node : mainChildren) {
      if (node.getMethod().getSignature().equals(fpToStringSig)) {
        filePermToStringNode = node;
        break;
      }
    }

    assertNotNull(filePermToStringNode);
  }

  private static Collection<CGNode> getSuccNodes(CallGraph cg, Collection<CGNode> nodes) {
    Set<CGNode> succNodes = HashSetFactory.make();
    for (CGNode newInstanceNode : nodes) {
      Iterator<? extends CGNode> succNodesIter = cg.getSuccNodes(newInstanceNode);
      while (succNodesIter.hasNext()) {
        succNodes.add(succNodesIter.next());
      }
    }
    return succNodes;
  }

  /**
   * Test that when analyzing reflect8, the call graph includes a node for
   * java.lang.Integer.toString()
   */
  @Test
  public void testReflect8()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT8_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);
    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Primordial, "Ljava/lang/Integer");
    MethodReference mr = MethodReference.findOrCreate(tr, "toString", "()Ljava/lang/String;");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }

  /**
   * Test that when analyzing reflect9, the call graph includes a node for
   * java.lang.Integer.toString()
   */
  @Test
  public void testReflect9()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT9_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);
    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Primordial, "Ljava/lang/Integer");
    MethodReference mr = MethodReference.findOrCreate(tr, "toString", "()Ljava/lang/String;");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }

  /**
   * Test that when analyzing Reflect10, the call graph includes a node for
   * java.lang.Integer.toString()
   */
  @Test
  public void testReflect10()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT10_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);
    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Primordial, "Ljava/lang/Integer");
    MethodReference mr = MethodReference.findOrCreate(tr, "toString", "()Ljava/lang/String;");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }

  /**
   * Test that when analyzing Reflect11, the call graph includes a node for java.lang.Object.wait()
   */
  @Test
  public void testReflect11()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT11_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);
    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Primordial, "Ljava/lang/Object");
    MethodReference mr = MethodReference.findOrCreate(tr, "wait", "()V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }

  /**
   * Test that when analyzing Reflect12, the call graph does not include a node for
   * reflection.Helper.n but does include a node for reflection.Helper.m
   */
  @Test
  public void testReflect12()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT12_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);
    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Helper");
    MethodReference mr =
        MethodReference.findOrCreate(
            tr, "m", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
    mr = MethodReference.findOrCreate(tr, "n", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    nodes = cg.getNodes(mr);
    assertTrue(nodes.isEmpty());
  }

  /**
   * Test that when analyzing Reflect13, the call graph includes both a node for reflection.Helper.n
   * and a node for reflection.Helper.m
   */
  @Test
  public void testReflect13()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT13_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);
    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Helper");
    MethodReference mr =
        MethodReference.findOrCreate(
            tr, "m", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
    mr = MethodReference.findOrCreate(tr, "n", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }

  /**
   * Test that when analyzing Reflect14, the call graph does not include a node for
   * reflection.Helper.n but does include a node for reflection.Helper.s
   */
  @Test
  public void testReflect14()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT14_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);
    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Helper");
    MethodReference mr =
        MethodReference.findOrCreate(tr, "s", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
    mr = MethodReference.findOrCreate(tr, "n", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    nodes = cg.getNodes(mr);
    assertTrue(nodes.isEmpty());
  }

  /**
   * Test that when analyzing Reflect15, the call graph includes a node for the constructor of
   * Helper that takes 2 parameters and for Helper.n, but no node for the constructors of Helper
   * that takes 0 or 1 parameters.
   */
  @Test
  public void testReflect15()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT15_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);
    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Helper");
    MethodReference mr =
        MethodReference.findOrCreate(tr, "<init>", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
    mr = MethodReference.findOrCreate(tr, "<init>", "(Ljava/lang/Object;)V");
    nodes = cg.getNodes(mr);
    assertTrue(nodes.isEmpty());
    mr = MethodReference.findOrCreate(tr, "<init>", "()V");
    nodes = cg.getNodes(mr);
    assertTrue(nodes.isEmpty());
    mr = MethodReference.findOrCreate(tr, "n", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }

  /**
   * Test that when analyzing Reflect16, the call graph includes a node for
   * java.lang.Integer.toString()
   */
  @Test
  public void testReflect16()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT16_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);
    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Primordial, "Ljava/lang/Integer");
    MethodReference mr = MethodReference.findOrCreate(tr, "toString", "()Ljava/lang/String;");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }

  /**
   * Test that when analyzing Reflect17, the call graph does not include any edges from
   * reflection.Helper.t()
   */
  @Test
  public void testReflect17()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT17_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);
    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Helper");
    MethodReference mr = MethodReference.findOrCreate(tr, "t", "(Ljava/lang/Integer;)V");
    CGNode node = cg.getNode(cg.getClassHierarchy().resolveMethod(mr), Everywhere.EVERYWHERE);
    assertEquals(0, cg.getSuccNodeCount(node));
  }

  /**
   * Test that when analyzing Reflect18, the call graph includes a node for
   * java.lang.Integer.toString()
   */
  @Test
  public void testReflect18()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT18_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);
    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Helper");
    MethodReference mr = MethodReference.findOrCreate(tr, "t", "(Ljava/lang/Integer;)V");
    CGNode node = cg.getNode(cg.getClassHierarchy().resolveMethod(mr), Everywhere.EVERYWHERE);
    assertEquals(1, cg.getSuccNodeCount(node));
    CGNode succ = cg.getSuccNodes(node).next();
    assertEquals(
        "Node: < Primordial, Ljava/lang/Integer, toString()Ljava/lang/String; > Context: Everywhere",
        succ.toString());
  }

  /**
   * Test that when analyzing Reflect19, the call graph includes a node for
   * java.lang.Integer.toString()
   */
  @Test
  public void testReflect19()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT19_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);
    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Primordial, "Ljava/lang/Integer");
    MethodReference mr = MethodReference.findOrCreate(tr, "toString", "()Ljava/lang/String;");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }

  /** Test that when analyzing Reflect20, the call graph includes a node for Helper.o. */
  @Test
  public void testReflect20()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT20_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);
    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Helper");
    MethodReference mr =
        MethodReference.findOrCreate(tr, "o", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }

  /**
   * Test that when analyzing Reflect21, the call graph includes a node for the constructor of
   * {@code Helper} that takes two {@link Object} parameters. This is to test the support for
   * Class.getDeclaredConstructor.
   */
  @Test
  public void testReflect21()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT21_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);
    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Helper");
    MethodReference mr =
        MethodReference.findOrCreate(tr, "<init>", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }

  /**
   * Test that when analyzing Reflect22, the call graph includes a node for the constructor of
   * {@code Helper} that takes one {@link Integer} parameters. This is to test the support for
   * Class.getDeclaredConstructors.
   */
  @Test
  public void testReflect22()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT22_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);
    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Helper");
    MethodReference mr = MethodReference.findOrCreate(tr, "<init>", "(Ljava/lang/Integer;)V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }

  /**
   * Test that when analyzing Reflect22, the call graph includes a node for the constructor of
   * {@code Helper} that takes one {@link Integer} parameters. This is to test the support for
   * Class.getDeclaredConstructors.
   */
  @Test
  public void testReflect23()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT23_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);
    TypeReference tr =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Helper");
    MethodReference mr = MethodReference.findOrCreate(tr, "u", "(Ljava/lang/Integer;)V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertFalse(nodes.isEmpty());
  }

  /**
   * Test that when analyzing Reflect24.
   *
   * <p>Through the pointer analysis in the CallGraph construction process, it can be inferred that
   * the type pointed to by the 0th parameter of the {@code com.ibm.wala.test.People#doNothing()}
   * method is {@code Helper}
   *
   * <p>This is to test the support for Object.getClass().
   */
  @Test
  public void testReflect24()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(cha, TestConstants.REFLECT24_MAIN);

    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    Pair<CallGraph, PointerAnalysis<InstanceKey>> pair =
        CallGraphTestUtil.buildNCFA(1, options, new AnalysisCacheImpl(), cha);

    CallGraph cg = pair.fst;
    PointerAnalysis<InstanceKey> pointerAnalysis = pair.snd;

    TypeReference helperTr =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Helper");
    IClass helperClass = cha.lookupClass(helperTr);
    assertNotNull(helperClass);

    TypeReference reflect24Tr =
        TypeReference.findOrCreate(ClassLoaderReference.Application, "Lreflection/Reflect24");
    MethodReference mr =
        MethodReference.findOrCreate(reflect24Tr, "doNothing", "(Ljava/lang/Class;)V");
    Set<CGNode> nodes = cg.getNodes(mr);
    assertEquals(1, nodes.size());

    // get the pts corresponding to the 0th parameter of the Reflect24#doNothing() method
    Optional<CGNode> firstMatched = nodes.stream().findFirst();
    assertTrue(firstMatched.isPresent());
    CGNode cgNode = firstMatched.get();

    LocalPointerKey localPointerKey = new LocalPointerKey(cgNode, cgNode.getIR().getParameter(0));
    OrdinalSet<InstanceKey> pts = pointerAnalysis.getPointsToSet(localPointerKey);
    assertEquals(1, pts.size());

    for (InstanceKey mappedObject : pts) {
      // the type corresponding to the 0th parameter should be Helper
      assertInstanceOf(ConstantKey.class, mappedObject);
      assertEquals(((ConstantKey<?>) mappedObject).getValue(), helperClass);
    }
  }

  /**
   * Test that when analyzing GetMethodContext, the call graph must contain exactly one call to each
   * of the following methods:
   *
   * <ul>
   *   <li>GetMethodContext$B#foo()
   *   <li>GetMethodContext$C#bar()
   * </ul>
   *
   * and must not contain
   *
   * <ul>
   *   <li>GetMethodContext$A#bar()
   *   <li>GetMethodContext$A#baz()
   *   <li>GetMethodContext$A#foo()
   *   <li>GetMethodContext$B#bar()
   *   <li>GetMethodContext$B#baz()
   *   <li>GetMethodContext$C#baz()
   *   <li>GetMethodContext$C#foo()
   * </ul>
   */
  @Test
  public void testGetMethodContext()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    TypeReference ta =
        TypeReference.findOrCreate(
            ClassLoaderReference.Application, "Lreflection/GetMethodContext$A");
    TypeReference tb =
        TypeReference.findOrCreate(
            ClassLoaderReference.Application, "Lreflection/GetMethodContext$B");
    TypeReference tc =
        TypeReference.findOrCreate(
            ClassLoaderReference.Application, "Lreflection/GetMethodContext$C");
    Selector sfoo = Selector.make("foo()V"),
        sbar = Selector.make("bar()V"),
        sbaz = Selector.make("baz()V");
    MethodReference mafoo = MethodReference.findOrCreate(ta, sfoo),
        mbfoo = MethodReference.findOrCreate(tb, sfoo),
        mcfoo = MethodReference.findOrCreate(tc, sfoo),
        mabar = MethodReference.findOrCreate(ta, sbar),
        mbbar = MethodReference.findOrCreate(tb, sbar),
        mcbar = MethodReference.findOrCreate(tc, sbar),
        mabaz = MethodReference.findOrCreate(ta, sbaz),
        mbbaz = MethodReference.findOrCreate(tb, sbaz),
        mcbaz = MethodReference.findOrCreate(tc, sbaz);
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(
            cha, TestConstants.REFLECTGETMETHODCONTEXT_MAIN);
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    CallGraph cg = CallGraphTestUtil.buildZeroOneCFA(options, new AnalysisCacheImpl(), cha, false);
    Set<CGNode> cgn;
    cgn = cg.getNodes(mabar);
    assertTrue(cgn.isEmpty());
    cgn = cg.getNodes(mabaz);
    assertTrue(cgn.isEmpty());
    cgn = cg.getNodes(mafoo);
    assertTrue(cgn.isEmpty());

    cgn = cg.getNodes(mbbar);
    assertTrue(cgn.isEmpty());
    cgn = cg.getNodes(mbbaz);
    assertTrue(cgn.isEmpty());

    cgn = cg.getNodes(mcbaz);
    assertTrue(cgn.isEmpty());
    cgn = cg.getNodes(mcfoo);
    assertTrue(cgn.isEmpty());

    cgn = cg.getNodes(mbfoo);
    assertEquals(1, cgn.size());

    cgn = cg.getNodes(mcbar);
    assertEquals(1, cgn.size());
  }

  @Test
  public void testForNameThrownExceptions()
      throws WalaException, IllegalArgumentException, CancelException, IOException {
    AnalysisScope scope = findOrCreateAnalysisScope();
    IClassHierarchy cha = findOrCreateCHA(scope);
    Iterable<Entrypoint> entrypoints =
        com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(
            cha, "Lreflection/ForNameThrownExceptions");
    AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
    options.setReflectionOptions(ReflectionOptions.NONE);
    CallGraph cg = CallGraphTestUtil.buildZeroCFA(options, new AnalysisCacheImpl(), cha, false);
    IMethod mainMethod = entrypoints.iterator().next().getMethod();
    List<CGNode> mainCallees =
        Iterator2Collection.toList(cg.getSuccNodes(cg.getNode(mainMethod, Everywhere.EVERYWHERE)));
    assertTrue(mainCallees.stream().anyMatch(n -> n.toString().contains("getMessage")));
    options.setReflectionOptions(ReflectionOptions.STRING_ONLY);
    cg = CallGraphTestUtil.buildZeroCFA(options, new AnalysisCacheImpl(), cha, false);
    mainCallees =
        Iterator2Collection.toList(cg.getSuccNodes(cg.getNode(mainMethod, Everywhere.EVERYWHERE)));
    // getMessage() should _not_ be a callee with reflection handling enabled
    assertFalse(mainCallees.stream().anyMatch(n -> n.toString().contains("getMessage")));
  }
}
