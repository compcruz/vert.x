/*
 * Copyright 2014 Red Hat, Inc.
 *
 *   Red Hat licenses this file to you under the Apache License, version 2.0
 *   (the "License"); you may not use this file except in compliance with the
 *   License.  You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *   WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 *   License for the specific language governing permissions and limitations
 *   under the License.
 */

package io.vertx.test.core;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.impl.Deployment;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.core.spi.cluster.ClusterManager;
import io.vertx.test.fakecluster.FakeClusterManager;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class ComplexHATest extends VertxTestBase {

  protected ClusterManager getClusterManager() {
    return new FakeClusterManager();
  }

  @Rule
  public RepeatRule repeatRule = new RepeatRule();

  private Random random = new Random();

  protected int maxVerticlesPerNode = 20;
  protected Set<Deployment>[] deploymentSnapshots;
  protected volatile int totDeployed;
  protected volatile int killedNode;
  protected List<Integer> aliveNodes;

  @Before
  public void before() {
    deploymentSnapshots = null;
    totDeployed = 0;
    killedNode = 0;
    aliveNodes = null;
  }

  @Test
  @Repeat(times = 10)
  public void testComplexFailover() {
    try {
      int numNodes = 8;
      createNodes(numNodes);
      deployRandomVerticles(() -> {
        killRandom();
      });
      await(10, TimeUnit.MINUTES);
    } catch (Throwable t) {
      // Need to explicitly catch throwables in repeats or they will be swallowed
      t.printStackTrace();
    }
  }

  protected void deployRandomVerticles(Runnable runner) {
    int toDeploy = 0;
    AtomicInteger deployCount = new AtomicInteger();
    for (int pos: aliveNodes) {
      int numToDeploy = random.nextInt(maxVerticlesPerNode + 1);
      Vertx v = vertices[pos];
      int ii = pos;
      for (int j = 0; j < numToDeploy; j++) {
        JsonObject config = new JsonObject();
        config.putString("foo", TestUtils.randomAlphaString(100));
        DeploymentOptions options = DeploymentOptions.options().setHA(true).setConfig(config);
        String verticleName = "java:io.vertx.test.core.HAVerticle" + (random.nextInt(3) + 1);
        toDeploy++;
        v.deployVerticle(verticleName, options, ar -> {
          assertTrue(ar.succeeded());
          deployCount.incrementAndGet();
        });
      }
    }
    int ttoDeploy = toDeploy;
    eventLoopWaitUntil(() -> ttoDeploy == deployCount.get(), () -> {
      totDeployed += ttoDeploy;
      runner.run();
    });
  }

  protected void undeployRandomVerticles(Runnable runner) {
    int toUndeploy = 0;
    AtomicInteger undeployCount = new AtomicInteger();
    for (int pos: aliveNodes) {
      Vertx v = vertices[pos];
      int deployedNum = v.deployments().size();
      int numToUnDeploy = random.nextInt(deployedNum + 1);
      List<String> deployed = new ArrayList<>(v.deployments());
      int ii = pos;
      for (int j = 0; j < numToUnDeploy; j++) {
        int depPos = random.nextInt(deployed.size());
        String depID = deployed.remove(depPos);
        toUndeploy++;
        v.undeployVerticle(depID, onSuccess(d -> {
          undeployCount.incrementAndGet();
        }));
      }
    }
    int totUndeployed = toUndeploy;
    eventLoopWaitUntil(() -> totUndeployed == undeployCount.get(), () -> {
      totDeployed -= totUndeployed;
      runner.run();
    });

  }

  private void eventLoopWaitUntil(BooleanSupplier supplier, Runnable runner) {
    long start = System.currentTimeMillis();
    doEventLoopWaitUntil(start, supplier, runner);
  }

  private void doEventLoopWaitUntil(long start, BooleanSupplier supplier, Runnable runner) {
    long now = System.currentTimeMillis();
    if (now - start > 10000) {
      fail("Timedout in waiting until");
    } else {
      if (supplier.getAsBoolean()) {
        runner.run();
      } else {
        vertx.setTimer(1, tid -> doEventLoopWaitUntil(start, supplier, runner));
      }
    }
  }

  protected void takeDeploymentSnapshots() {
    for (int i = 0; i < vertices.length; i++) {
      VertxInternal v = (VertxInternal)vertices[i];
      if (!v.isKilled()) {
        deploymentSnapshots[i] = takeDeploymentSnapshot(i);
      }
    }
  }

  protected Set<Deployment> takeDeploymentSnapshot(int pos) {
    Set<Deployment> snapshot = new HashSet<>();
    VertxInternal v = (VertxInternal)vertices[pos];
    for (String depID: v.deployments()) {
      snapshot.add(v.getDeployment(depID));
    }
    return snapshot;
  }

  protected void kill(int pos) {
    // Save the deployments first
    takeDeploymentSnapshots();
    VertxInternal v = (VertxInternal)vertices[pos];
    killedNode = pos;
    v.executeBlocking(() -> {
      v.simulateKill();
      return null;
    }, ar -> {
      assertTrue(ar.succeeded());
    });

  }

  protected void createNodes(int nodes) {
    startNodes(nodes, VertxOptions.options().setHAEnabled(true));
    aliveNodes = new ArrayList<>();
    for (int i = 0; i < nodes; i++) {
      aliveNodes.add(i);
      int pos = i;
      ((VertxInternal)vertices[i]).failoverCompleteHandler(succeeded -> {
        failedOverOnto(pos);
      });
    }
    deploymentSnapshots = new Set[nodes];
  }

  protected void failedOverOnto(int node) {
    checkDeployments();
    checkHasDeployments(node, killedNode);
    if (aliveNodes.size() >= 2) {
      undeployRandomVerticles(() -> {
        deployRandomVerticles(() -> {
          killRandom();
        });
      });
    } else {
      testComplete();
    }
  }

  protected void checkDeployments() {
    int totalDeployed = 0;
    for (int i = 0; i < vertices.length; i++) {
      VertxInternal v = (VertxInternal)vertices[i];
      if (!v.isKilled()) {
        totalDeployed += checkHasDeployments(i, i);
      }
    }
    assertEquals(totDeployed, totalDeployed);
  }

  protected int checkHasDeployments(int pos, int prevPos) {
    Set<Deployment> prevSet = deploymentSnapshots[prevPos];
    Set<Deployment> currSet = takeDeploymentSnapshot(pos);
    for (Deployment prev: prevSet) {
      boolean contains = false;
      for (Deployment curr: currSet) {
        if (curr.verticleName().equals(prev.verticleName()) && curr.deploymentOptions().equals(prev.deploymentOptions())) {
          contains = true;
          break;
        }
      }
      assertTrue(contains);
    }
    return currSet.size();
  }

  protected void killRandom() {
    int i = random.nextInt(aliveNodes.size());
    int pos = aliveNodes.get(i);
    aliveNodes.remove(i);
    kill(pos);
  }

}
