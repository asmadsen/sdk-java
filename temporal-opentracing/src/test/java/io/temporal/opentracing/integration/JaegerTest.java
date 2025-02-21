/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.opentracing.integration;

import static org.junit.Assert.*;

import io.jaegertracing.internal.JaegerSpan;
import io.jaegertracing.internal.JaegerTracer;
import io.jaegertracing.internal.reporters.InMemoryReporter;
import io.jaegertracing.internal.samplers.ConstSampler;
import io.jaegertracing.spi.Sampler;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.opentracing.OpenTracingClientInterceptor;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.OpenTracingSpanContextCodec;
import io.temporal.opentracing.OpenTracingWorkerInterceptor;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class JaegerTest {
  private static final OpenTracingOptions JAEGER_COMPATIBLE_CONFIG =
      OpenTracingOptions.newBuilder()
          .setSpanContextCodec(OpenTracingSpanContextCodec.TEXT_MAP_CODEC)
          .build();

  private InMemoryReporter reporter;
  private Sampler sampler;
  private Tracer tracer;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setInterceptors(new OpenTracingClientInterceptor(JAEGER_COMPATIBLE_CONFIG))
                  .validateAndBuildWithDefaults())
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new OpenTracingWorkerInterceptor(JAEGER_COMPATIBLE_CONFIG))
                  .validateAndBuildWithDefaults())
          .setWorkflowTypes(WorkflowImpl.class)
          .setActivityImplementations(new ActivityImpl())
          .build();

  @Before
  public void setUp() {
    reporter = new InMemoryReporter();
    sampler = new ConstSampler(true);
    tracer =
        new JaegerTracer.Builder("temporal-test")
            .withReporter(reporter)
            .withSampler(sampler)
            .build();

    GlobalTracer.registerIfAbsent(tracer);
  }

  @After
  public void tearDown() {
    reporter.close();
    sampler.close();
    tracer.close();
  }

  @ActivityInterface
  public interface TestActivity {
    @ActivityMethod
    String activity(String input);
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow(String input);
  }

  public static class ActivityImpl implements TestActivity {
    @Override
    public String activity(String input) {
      return "bar";
    }
  }

  public static class WorkflowImpl implements TestWorkflow {
    private final TestActivity activity =
        Workflow.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(1))
                .validateAndBuildWithDefaults());

    @Override
    public String workflow(String input) {
      return activity.activity(input);
    }
  }

  @Test
  public void trivialTest() {
    Span span = tracer.buildSpan("ClientFunction").start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    try (Scope scope = tracer.scopeManager().activate(span)) {
      TestWorkflow workflow =
          client.newWorkflowStub(
              TestWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .validateBuildWithDefaults());
      assertEquals("bar", workflow.workflow("input"));
    } finally {
      span.finish();
    }

    List<JaegerSpan> reportedSpans = reporter.getSpans();
    assertNotNull(reportedSpans);
    assertTrue(reportedSpans.size() > 1);
  }
}
