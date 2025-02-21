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

package io.temporal.internal.retryer;

import static org.junit.Assert.*;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.serviceclient.RpcRetryOptions;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class GrpcSyncRetryerTest {

  private static final GrpcSyncRetryer DEFAULT_SYNC_RETRYER =
      new GrpcSyncRetryer(Clock.systemUTC());

  private static ScheduledExecutorService scheduledExecutor;

  @BeforeClass
  public static void beforeClass() {
    scheduledExecutor = Executors.newScheduledThreadPool(1);
  }

  @AfterClass
  public static void afterClass() {
    scheduledExecutor.shutdownNow();
  }

  @Test
  public void testExpiration() {
    final Status.Code STATUS_CODE = Status.Code.DATA_LOSS;

    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .setExpiration(Duration.ofMillis(500))
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    try {
      DEFAULT_SYNC_RETRYER.retry(
          options,
          () -> {
            throw new StatusRuntimeException(Status.fromCode(STATUS_CODE));
          });
      fail("unreachable");
    } catch (Exception e) {
      assertTrue(e instanceof StatusRuntimeException);
      assertEquals(STATUS_CODE, ((StatusRuntimeException) e).getStatus().getCode());
    }

    assertTrue(System.currentTimeMillis() - start > 500);
  }

  @Test
  public void testDoNotRetry() {
    final Status.Code STATUS_CODE = Status.Code.DATA_LOSS;

    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .addDoNotRetry(STATUS_CODE, null)
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    try {
      DEFAULT_SYNC_RETRYER.retry(
          options,
          () -> {
            throw new StatusRuntimeException(Status.fromCode(STATUS_CODE));
          });
      fail("unreachable");
    } catch (Exception e) {
      assertTrue(e instanceof StatusRuntimeException);
      assertEquals(STATUS_CODE, ((StatusRuntimeException) e).getStatus().getCode());
    }
    assertTrue(
        "We should fail fast on exception that we specified to don't retry",
        System.currentTimeMillis() - start < 10_000);
  }

  @Test
  public void testInterruptedException() {
    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    try {
      DEFAULT_SYNC_RETRYER.retry(
          options,
          () -> {
            throw new InterruptedException();
          });
      fail("unreachable");
    } catch (Exception e) {
      assertTrue(e instanceof CancellationException);
    }
    assertTrue(
        "We should fail fast on InterruptedException", System.currentTimeMillis() - start < 10_000);
  }

  @Test
  public void testNotStatusRuntimeException() {
    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(10))
            .setMaximumInterval(Duration.ofMillis(100))
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    try {
      DEFAULT_SYNC_RETRYER.retry(
          options,
          () -> {
            throw new IllegalArgumentException("simulated");
          });
      fail("unreachable");
    } catch (Exception e) {
      assertTrue(e instanceof IllegalArgumentException);
      assertEquals("simulated", e.getMessage());
    }
    assertTrue(
        "If the exception is not StatusRuntimeException - we shouldn't retry",
        System.currentTimeMillis() - start < 10_000);
  }

  @Test
  public void testRetryDeadlineExceededException() {
    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(100))
            .setMaximumInterval(Duration.ofMillis(100))
            .setExpiration(Duration.ofMillis(500))
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    final AtomicInteger attempts = new AtomicInteger();

    StatusRuntimeException e =
        assertThrows(
            StatusRuntimeException.class,
            () -> {
              DEFAULT_SYNC_RETRYER.retry(
                  options,
                  () -> {
                    attempts.incrementAndGet();
                    throw new StatusRuntimeException(
                        Status.fromCode(Status.Code.DEADLINE_EXCEEDED));
                  });
            });

    assertEquals(Status.Code.DEADLINE_EXCEEDED, e.getStatus().getCode());
    assertTrue(
        "We should retry DEADLINE_EXCEEDED if global Grpc Deadline, attempts, time are not exhausted.",
        System.currentTimeMillis() - start > 400);

    assertTrue(
        "We should retry DEADLINE_EXCEEDED if global Grpc Deadline, attempts, time are not exhausted.",
        attempts.get() > 2);
  }

  @Test
  public void testRespectGlobalDeadlineExceeded() {
    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(100))
            .setMaximumInterval(Duration.ofMillis(100))
            .setExpiration(Duration.ofMillis(50000))
            .validateBuildWithDefaults();
    long start = System.currentTimeMillis();
    final AtomicInteger attempts = new AtomicInteger();
    final AtomicReference<StatusRuntimeException> exception = new AtomicReference<>();

    Context.current()
        .withDeadlineAfter(500, TimeUnit.MILLISECONDS, scheduledExecutor)
        .run(
            () ->
                exception.set(
                    assertThrows(
                        StatusRuntimeException.class,
                        () -> {
                          DEFAULT_SYNC_RETRYER.retry(
                              options,
                              () -> {
                                attempts.incrementAndGet();
                                throw new StatusRuntimeException(
                                    Status.fromCode(Status.Code.DATA_LOSS));
                              });
                        })));

    assertEquals(Status.Code.DATA_LOSS, exception.get().getStatus().getCode());
    assertTrue(
        "We shouldn't retry after gRPC deadline from the context is expired",
        System.currentTimeMillis() - start < 10_000);

    assertTrue(
        "We shouldn't retry after gRPC deadline from the context is expired", attempts.get() < 8);
  }

  @Test
  public void testGlobalDeadlineExceededAfterAnotherException() {
    RpcRetryOptions options =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(100))
            .setMaximumInterval(Duration.ofMillis(100))
            .validateBuildWithDefaults();

    final AtomicReference<StatusRuntimeException> exception = new AtomicReference<>();

    Context.current()
        .withDeadlineAfter(500, TimeUnit.MILLISECONDS, scheduledExecutor)
        .run(
            () ->
                exception.set(
                    assertThrows(
                        StatusRuntimeException.class,
                        () -> {
                          DEFAULT_SYNC_RETRYER.retry(
                              options,
                              () -> {
                                if (Context.current().getDeadline().isExpired()) {
                                  throw new StatusRuntimeException(
                                      Status.fromCode(Status.Code.DEADLINE_EXCEEDED));
                                } else {
                                  throw new StatusRuntimeException(
                                      Status.fromCode(Status.Code.DATA_LOSS));
                                }
                              });
                        })));

    assertEquals(
        "We should get a previous exception in case of DEADLINE_EXCEEDED",
        Status.Code.DATA_LOSS,
        exception.get().getStatus().getCode());
  }
}
