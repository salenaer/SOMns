package tools.concurrency;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

import som.vm.VmSettings;
import tools.TraceData;


public abstract class TracingActivityThread extends ForkJoinWorkerThread {
  private static AtomicInteger threadIdGen = new AtomicInteger(1);
  protected final long threadId;
  protected long nextActivityId = 1;
  protected long nextMessageId;
  protected long nextPromiseId;

  // Used for tracing, accessed by the ExecAllMessages classes
  public long createdMessages;
  public long resolvedPromises;
  public long erroredPromises;

  protected final TraceBuffer traceBuffer;

  public TracingActivityThread(final ForkJoinPool pool) {
    super(pool);
    if (VmSettings.ACTOR_TRACING) {
      traceBuffer = TraceBuffer.create();
      threadId = threadIdGen.getAndIncrement();
      nextActivityId = 1 + (threadId << TraceData.ACTIVITY_ID_BITS);
      nextMessageId = (threadId << TraceData.ACTIVITY_ID_BITS);
      nextPromiseId = (threadId << TraceData.ACTIVITY_ID_BITS);
    } else {
      threadId = 0;
      traceBuffer = null;
    }
    setName(getClass().getSimpleName() + "-" + threadId);
  }

  public long generateActivityId() {
    long result = nextActivityId;
    nextActivityId++;
    assert TraceData.isWithinJSIntValueRange(result);
    return result;
  }

  public long generatePromiseId() {
    return nextPromiseId++;
  }

  public final TraceBuffer getBuffer() {
    return traceBuffer;
  }

  public abstract long getCurrentMessageId();

  @Override
  protected void onStart() {
    super.onStart();
    if (VmSettings.ACTOR_TRACING) {
      traceBuffer.init(ActorExecutionTrace.getEmptyBuffer(), threadId);
      ActorExecutionTrace.registerThread(this);
    }
  }

  @Override
  protected void onTermination(final Throwable exception) {
    if (VmSettings.ACTOR_TRACING) {
      traceBuffer.returnBuffer();
      ActorExecutionTrace.unregisterThread(this);
    }
    super.onTermination(exception);
  }
}
