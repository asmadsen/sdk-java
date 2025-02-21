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

package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.NoArgsActivity;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import org.junit.Rule;
import org.junit.Test;

public class IdentityInPendingActivityTest {

  private final NoArgsActivity activityImpl = new ActivityImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(WorkflowImpl.class)
          .setActivityImplementations(activityImpl)
          .build();

  @Test
  public void testPendingActivityHasIdentity() throws InterruptedException {
    NoArgsWorkflow workflow = testWorkflowRule.newWorkflowStub(NoArgsWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);
    DescribeWorkflowExecutionResponse response =
        DescribeWorkflowExecutionResponse.getDefaultInstance();
    // Call describeWorkflowExecution until we see pending activities.
    // We see a pending activity without an identity first before the worker picks it up.
    while (response.getPendingActivitiesCount() == 0) {
      Thread.sleep(50);
      response =
          testWorkflowRule
              .getWorkflowClient()
              .getWorkflowServiceStubs()
              .blockingStub()
              .describeWorkflowExecution(
                  DescribeWorkflowExecutionRequest.newBuilder()
                      .setNamespace(
                          testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                      .setExecution(execution)
                      .build());
      if (response.getPendingActivitiesCount() > 0) {
        assertEquals(
            "Pending activity identity is not as expected",
            testWorkflowRule.getTestEnvironment().getWorkflowClient().getOptions().getIdentity(),
            response.getPendingActivities(0).getLastWorkerIdentity());
      }
    }
  }

  public static class WorkflowImpl implements NoArgsWorkflow {

    private final NoArgsActivity activities =
        Workflow.newActivityStub(
            NoArgsActivity.class, SDKTestOptions.newActivityOptions20sScheduleToClose());

    @Override
    public void execute() {
      activities.execute();
    }
  }

  public static class ActivityImpl implements NoArgsActivity {
    @Override
    public void execute() {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
