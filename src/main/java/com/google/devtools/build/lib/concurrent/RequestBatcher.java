// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.concurrent;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.Futures.addCallback;
import static com.google.devtools.build.lib.concurrent.PaddedAddresses.createPaddedBaseAddress;
import static com.google.devtools.build.lib.concurrent.PaddedAddresses.getAlignedAddress;
import static java.lang.Math.min;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.unsafe.UnsafeProvider;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.ref.Cleaner;
import java.util.List;
import java.util.concurrent.Executor;
import sun.misc.Unsafe;

/**
 * Provides a unary request-response interface but implements batching.
 *
 * <p>Clients should provide a {@link Multiplexer} implementation that performs the actual batched
 * operations.
 *
 * <p>This class is thread-safe.
 */
@SuppressWarnings("SunApi") // TODO: b/359688989 - clean this up
public final class RequestBatcher<RequestT, ResponseT> {
  /* This class employs concurrent workers that perform the following cycle:
   *
   *   1. Collect as many request-response pairs from the queue as possible.
   *   2. Execute the collected pairs as a batch.
   *
   * We guarantee that every submitted request is handled. The following traces all possible paths a
   * request-response pair can take through the batcher to demonstrate this guarantee.
   *
   * Possible Paths:
   *
   *   1. The pair is present in some `submit` call. A special case is when the queue is full.
   *   2. The pair is enqueued, but not yet reflected in the request-responses count.
   *   3. The pair is enqueued, and request-responses count has been incremented.
   *
   * Step 1: Initial part of `submit`
   *
   * A. We check the active-workers count. If it's less than `targetWorkerCount`, a new worker is
   *    started and the pair is directly assigned to it.
   *
   * B. Otherwise, we attempt to enqueue the pair and if enqueuing succeeds, we proceed to Step 2.
   *
   * C. If the queue is full, we start a new worker even if active-workers count is at the target,
   *    and assign the rejected pair to this new worker.
   *
   * In both cases (A, C) that bypass Step 2, the pair is immediately assigned a worker.
   *
   * Step 2: Request-response Enqueued
   *
   * Step 2 is not atomic with Step 1, so the counters might have changed. We re-check
   * active-workers count.
   *
   * A. If it's at `targetWorkerCount` or higher, we attempt to increment request-responses count
   *    atomically, ensuring active-workers count remains unchanged during the increment. Success
   *    leads to Step 3.
   *
   * B. If active-workers count is below the target (due to concurrent activity), we start a new
   *    worker like in Step 1, and dequeue an arbitrary element to assign to it. This maintains
   *    consistency between queue size and request-responses count. The new worker guarantees
   *    processing of all enqueued request-responses (including the one we just added), even if that
   *    specific request ends up handled by a different worker.
   *
   * Step 3: Request-response Enqueued and request-responses count Incremented
   *
   * The atomic request-responses count increment only happens in Step 2 if active-workers count is
   * at or above the target. Workers only stop if active-workers count exceeds the target or
   * request-responses count is 0. Since `targetWorkerCount` > 0, there's always at least one
   * active worker to handle the request-response.
   */

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /**
   * A common cleaner shared by all instances.
   *
   * <p>Used to free memory allocated by {@link PaddedAddresses}.
   */
  private static final Cleaner cleaner = Cleaner.create();

  /**
   * Reads this many at a time when taking queue elements.
   *
   * <p>Reading requires a {@link #countersAddress} CAS operation so it's better to read many at
   * once. On the other hand, reading too many at once could decrease concurrency or lead to stalls.
   */
  private static final int BATCH_READ_STRIDE = 128;

  private final Executor executor;
  private final Multiplexer<RequestT, ResponseT> multiplexer;

  /** Number of active workers to target. */
  private final int targetWorkerCount;

  /**
   * Address of an integer containing two counters.
   *
   * <p>Having two counters in the same integer enables simultaneous, atomic updates of both values.
   *
   * <ul>
   *   <li><b>request-responses count</b>: the lower 20-bits (occupying the bits of {@link
   *       #REQUEST_COUNT_MASK}) contain a lower bound of request-responses in {@link #queue}. This
   *       is incremented after successful enqueuing and decremented before dequeuing. This counter
   *       value is never more than the size of the queue so it can be used to guarantee that the
   *       number of calls to {@link ConcurrentFifo#take} do not exceed the number of successful
   *       {@link ConcurrentFifo#tryAppend} calls.
   *   <li><b>active-workers count</b>: the upper 12-bits (starting from {@link
   *       #ACTIVE_WORKERS_COUNT_BIT_OFFSET}) contain the number of active workers.
   * </ul>
   */
  private final long countersAddress;

  private final ConcurrentFifo<RequestResponse<RequestT, ResponseT>> queue;

  /** Injectable batching logic. */
  @FunctionalInterface
  public interface Multiplexer<RequestT, ResponseT> {
    /**
     * Evaluates {@code requests} as a batch.
     *
     * @return a future containing a list of responses, positionally aligned with {@code requests}
     */
    ListenableFuture<List<ResponseT>> execute(List<RequestT> requests);
  }

  public static <RequestT, ResponseT> RequestBatcher<RequestT, ResponseT> create(
      Executor executor, Multiplexer<RequestT, ResponseT> multiplexer, int targetWorkerCount) {
    long baseAddress = createPaddedBaseAddress(4);
    long countersAddress = getAlignedAddress(baseAddress, /* offset= */ 0);

    var queue =
        new ConcurrentFifo<RequestResponse<RequestT, ResponseT>>(
            RequestResponse.class,
            /* sizeAddress= */ getAlignedAddress(baseAddress, /* offset= */ 1),
            /* appendIndexAddress= */ getAlignedAddress(baseAddress, /* offset= */ 2),
            /* takeIndexAddress= */ getAlignedAddress(baseAddress, /* offset= */ 3));

    var batcher =
        new RequestBatcher<RequestT, ResponseT>(
            executor, multiplexer, targetWorkerCount, countersAddress, queue);

    cleaner.register(batcher, new AddressFreer(baseAddress));

    return batcher;
  }

  /**
   * Low-level constructor.
   *
   * <p>Caller owns memory addresses used by {@code queue} and cleanup of memory at {@code
   * countersAddress}.
   */
  @VisibleForTesting
  RequestBatcher(
      Executor executor,
      Multiplexer<RequestT, ResponseT> multiplexer,
      int targetWorkerCount,
      long countersAddress,
      ConcurrentFifo<RequestResponse<RequestT, ResponseT>> queue) {
    checkArgument(targetWorkerCount > 0, "targetWorkerCount=%s < 1", targetWorkerCount);
    checkArgument(
        targetWorkerCount <= ACTIVE_WORKERS_COUNT_MAX,
        "targetWorkerCount=%s > %s",
        targetWorkerCount,
        ACTIVE_WORKERS_COUNT_MAX);
    this.executor = executor;
    this.multiplexer = multiplexer;
    this.targetWorkerCount = targetWorkerCount;
    this.countersAddress = countersAddress;
    this.queue = queue;

    // Initializes memory at countersAddress.
    UNSAFE.putInt(null, countersAddress, 0);
  }

  /**
   * Submits a request, subject to batching.
   *
   * <p>Callers should consider processing the response on a different executor if processing is
   * expensive to avoid delaying work pending other responses in the batch.
   */
  public ListenableFuture<ResponseT> submit(RequestT request) {
    RequestResponse<RequestT, ResponseT> requestResponse = new RequestResponse<>(request);

    // Tries to start a worker as long as the active worker count is less than `targetWorkerCount`.
    while (true) {
      int snapshot = UNSAFE.getIntVolatile(null, countersAddress);
      int activeWorkers = snapshot >>> ACTIVE_WORKERS_COUNT_BIT_OFFSET;
      if (activeWorkers >= targetWorkerCount) {
        break;
      }
      if (UNSAFE.compareAndSwapInt(null, countersAddress, snapshot, snapshot + ONE_ACTIVE_WORKER)) {
        // An active worker was reserved. Starts the worker by executing a batch.
        executeBatch(requestResponse, /* ownsPermit= */ true);
        return requestResponse;
      }
    }

    if (!queue.tryAppend(requestResponse)) {
      // The queue rejected it. Since there's nowhere else for the request to go, unconditionally
      // starts a worker, disregarding `targetWorkerCount`.
      unconditionallyStartWorker(requestResponse);
      return requestResponse;
    }
    // Enqueuing succeeded.

    while (true) {
      int snapshot = UNSAFE.getIntVolatile(null, countersAddress); // pessimistic read
      int activeWorkers = snapshot >>> ACTIVE_WORKERS_COUNT_BIT_OFFSET;
      if (activeWorkers >= targetWorkerCount) {
        // Increments the request-responses count.
        if (UNSAFE.compareAndSwapInt(null, countersAddress, snapshot, snapshot + ONE_REQUEST)) {
          // This must not be reached if `activeWorkers` is 0. Guaranteed by the enclosing check.
          return requestResponse;
        }
      } else {
        // This is a less common case where the task was enqueued, but the number of active workers
        // immediately dipped below `targetWorkersCount`. Starts a worker.
        if (UNSAFE.compareAndSwapInt(
            null, countersAddress, snapshot, snapshot + ONE_ACTIVE_WORKER)) {
          // Usually, decrementing the request-responses count must precede taking from the queue.
          // Here, a request-response was just enqueued and the count has not yet been incremented.
          executeBatch(queue.take(), /* ownsPermit= */ true);
          return requestResponse;
        }
      }
    }
  }

  @Override
  public String toString() {
    int snapshot = UNSAFE.getIntVolatile(null, countersAddress);
    return String.format(
        "activeWorkers=%d, requestCount=%d\nqueue=%s\n",
        snapshot >>> ACTIVE_WORKERS_COUNT_BIT_OFFSET, snapshot & REQUEST_COUNT_MASK, queue);
  }

  /**
   * Executes a batch without regard for {@link #targetWorkerCount}.
   *
   * <p>This method handles queue overflows.
   */
  private void unconditionallyStartWorker(RequestResponse<RequestT, ResponseT> requestResponse) {
    while (true) {
      int snapshot = UNSAFE.getIntVolatile(null, countersAddress);
      int activeWorkers = snapshot >>> ACTIVE_WORKERS_COUNT_BIT_OFFSET;
      if (activeWorkers >= ACTIVE_WORKERS_COUNT_MAX) {
        // Permits have overflowed. This should be unlikely to occur in practice. Just executes a
        // batch, marked without ownership.
        logger.atWarning().log(
            "activeWorkers have overflowed: %d >= %d", activeWorkers, ACTIVE_WORKERS_COUNT_MAX);
        executeBatch(requestResponse, /* ownsPermit= */ false);
        return;
      }
      if (UNSAFE.compareAndSwapInt(null, countersAddress, snapshot, snapshot + ONE_ACTIVE_WORKER)) {
        executeBatch(requestResponse, /* ownsPermit= */ true);
        return;
      }
    }
  }

  /**
   * Constructs a batch by polling elements from the queue until it is empty, then executes it.
   *
   * <p>After the batch is executed, if {@code ownsPermit} is true, arranges follow-up work by
   * calling {@code #continueToNextBatchOrBecomeIdle}.
   *
   * @param requestResponse a single element to be included in the batch. This ensures the batch is
   *     non-empty.
   * @param ownsPermit true if the current worker has an associated increment in the active-workers
   *     count in {@link #countersAddress}. Notably, this is false when workers overflow that
   *     counter.
   */
  private void executeBatch(
      RequestResponse<RequestT, ResponseT> requestResponse, boolean ownsPermit) {
    ImmutableList<RequestResponse<RequestT, ResponseT>> batch = populateBatch(requestResponse);
    ListenableFuture<List<ResponseT>> futureResponses =
        multiplexer.execute(Lists.transform(batch, RequestResponse::request));

    if (ownsPermit) {
      futureResponses.addListener(this::continueToNextBatchOrBecomeIdle, executor);
    }

    addCallback(
        futureResponses,
        new FutureCallback<List<ResponseT>>() {
          @Override
          public void onFailure(Throwable t) {
            for (RequestResponse<RequestT, ResponseT> requestResponse : batch) {
              requestResponse.setException(t);
            }
          }

          @Override
          public void onSuccess(List<ResponseT> responses) {
            if (responses.size() != batch.size()) {
              onFailure(
                  new AssertionError(
                      "RequestBatcher expected batch.size()="
                          + batch.size()
                          + " responses, but responses.size()="
                          + responses.size()));
              return;
            }
            for (int i = 0; i < responses.size(); ++i) {
              batch.get(i).setResponse(responses.get(i));
            }
          }
        },
        executor);
  }

  /**
   * Polls everything available in {@link #queue} and creates a batch.
   *
   * @param requestResponse an element to add to the batch.
   */
  private ImmutableList<RequestResponse<RequestT, ResponseT>> populateBatch(
      RequestResponse<RequestT, ResponseT> requestResponse) {
    var accumulator =
        ImmutableList.<RequestResponse<RequestT, ResponseT>>builder().add(requestResponse);
    while (true) {
      int snapshot = UNSAFE.getIntVolatile(null, countersAddress);
      int requestCount = snapshot & REQUEST_COUNT_MASK;
      if (requestCount == 0) {
        break;
      }
      int toRead = min(BATCH_READ_STRIDE, requestCount);
      if (!UNSAFE.compareAndSwapInt(null, countersAddress, snapshot, snapshot - toRead)) {
        continue;
      }
      for (int i = 0; i < toRead; i++) {
        accumulator.add(queue.take());
      }
      if (toRead == requestCount) {
        break; // the queue is drained
      }
    }
    return accumulator.build();
  }

  /**
   * Either processes the next batch or releases the held token.
   *
   * <p>Tries to process the next batch if enqueued requests are available and there are not too
   * many active workers already. Otherwise, stops working and decrements the active worker count.
   */
  private void continueToNextBatchOrBecomeIdle() {
    while (true) {
      int snapshot = UNSAFE.getIntVolatile(null, countersAddress);
      int activeWorkers = snapshot >> ACTIVE_WORKERS_COUNT_BIT_OFFSET;
      if (activeWorkers > targetWorkerCount || (snapshot & REQUEST_COUNT_MASK) == 0) {
        // Either the active workers count is above target or there are no enqueued requests. Tries
        // to become idle.
        if (UNSAFE.compareAndSwapInt(
            null, countersAddress, snapshot, snapshot - ONE_ACTIVE_WORKER)) {
          return;
        }
      } else {
        // Tries to reserve an enqueued request-response to begin another batch.
        if (UNSAFE.compareAndSwapInt(null, countersAddress, snapshot, snapshot - ONE_REQUEST)) {
          executeBatch(queue.take(), /* ownsPermit= */ true);
          return;
        }
      }
    }
  }

  @VisibleForTesting
  static class RequestResponse<RequestT, ResponseT> extends AbstractFuture<ResponseT> {
    private final RequestT request;

    private RequestResponse(RequestT request) {
      this.request = request;
    }

    private RequestT request() {
      return request;
    }

    private void setResponse(ResponseT response) {
      set(response);
    }

    @Override
    @CanIgnoreReturnValue
    protected boolean setException(Throwable t) {
      return super.setException(t);
    }
  }

  private static final int REQUEST_COUNT_MASK = 0x000F_FFFF;
  private static final int ONE_REQUEST = 1;

  private static final int ACTIVE_WORKERS_COUNT_BIT_OFFSET = 20;
  private static final int ONE_ACTIVE_WORKER = 1 << ACTIVE_WORKERS_COUNT_BIT_OFFSET;
  private static final int ACTIVE_WORKERS_COUNT_MAX = 0x0000_0FFF;

  static {
    checkState(
        REQUEST_COUNT_MASK == ConcurrentFifo.CAPACITY_MASK,
        "Request Count Constants inconsistent with ConcurrentFifo");
    checkState(
        ONE_REQUEST == (REQUEST_COUNT_MASK & -REQUEST_COUNT_MASK),
        "Inconsistent Request Count Constants");
  }

  private static final Unsafe UNSAFE = UnsafeProvider.unsafe();
}
