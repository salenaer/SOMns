package som.interpreter.actors;

import java.util.Arrays;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.source.SourceSection;

import som.VM;
import som.interpreter.actors.Actor.ActorProcessingThread;
import som.interpreter.actors.ReceivedMessage.ReceivedCallback;
import som.interpreter.actors.SPromise.SResolver;
import som.vm.Symbols;
import som.vmobjects.SBlock;
import som.vmobjects.SSymbol;


public abstract class EventualMessage {
  private static final SSymbol VALUE_SELECTOR = Symbols.symbolFor("value:");

  protected final Object[]  args;
  protected final SResolver resolver;
  protected final RootCallTarget onReceive;
  protected final long causalMessageId;

  /**
    * It is not final because its value can be updated in order that other breakpoints
    * can reuse the stepping strategy implemented for message receiver breakpoints.
    */
  protected boolean triggerMessageReceiverBreakpoint;
  protected final boolean triggerPromiseResolverBreakpoint;
  protected final boolean triggerPromiseResolutionBreakpoint;

  protected EventualMessage(final long causalMessageId, final Object[] args,
      final SResolver resolver, final RootCallTarget onReceive,
      final boolean triggerMessageReceiverBreakpoint, final boolean triggerPromiseResolverBreakpoint,
      final boolean triggerPromiseResolutionBreakpoint) {
    this.causalMessageId = causalMessageId;
    this.args     = args;
    this.resolver = resolver;
    this.onReceive = onReceive;
    this.triggerMessageReceiverBreakpoint = triggerMessageReceiverBreakpoint;
    this.triggerPromiseResolverBreakpoint = triggerPromiseResolverBreakpoint;
    this.triggerPromiseResolutionBreakpoint = triggerPromiseResolutionBreakpoint;
    assert onReceive.getRootNode() instanceof ReceivedMessage || onReceive.getRootNode() instanceof ReceivedCallback;
  }

  public abstract Actor getTarget();
  public abstract Actor getSender();

  public long getCausalMessageId() {
    return causalMessageId;
  }

  public SResolver getResolver() {
    return resolver;
  }

  public abstract SSymbol getSelector();

  public SourceSection getTargetSourceSection() {
    return onReceive.getRootNode().getSourceSection();
  }

  /**
   * A message to a known receiver that is to be executed on the actor owning
   * the receiver.
   *
   * ARGUMENTS: are wrapped eagerly on message creation
   */
  public static final class DirectMessage extends EventualMessage {
    private final SSymbol selector;
    private final Actor   target;
    private final Actor   sender;

    public DirectMessage(final long causalMessageId, final Actor target, final SSymbol selector,
        final Object[] arguments, final Actor sender, final SResolver resolver,
        final RootCallTarget onReceive, final boolean triggerMessageReceiverBreakpoint,
        final boolean triggerPromiseResolverBreakpoint,
        final boolean triggerPromiseResolutionBreakpoint) {
      super(causalMessageId, arguments, resolver, onReceive, triggerMessageReceiverBreakpoint,
      triggerPromiseResolverBreakpoint, triggerPromiseResolutionBreakpoint);
      this.selector = selector;
      this.sender   = sender;
      this.target   = target;

      assert target != null;
      assert !(args[0] instanceof SFarReference) : "needs to be guaranted by call to this constructor";
      assert !(args[0] instanceof SPromise);
    }

    @Override
    public Actor getTarget() {
      assert target != null;
      return target;
    }

    @Override
    public Actor getSender() {
      assert sender != null;
      return sender;
    }

    @Override
    public SSymbol getSelector() {
      return selector;
    }

    @Override
    public String toString() {
      String t = target.toString();
      return "DirectMsg(" + selector.toString() + ", "
        + Arrays.toString(args) +  ", " + t
        + ", sender: " + (sender == null ? "" : sender.toString()) + ")";
    }
  }

  protected static Actor determineTargetAndWrapArguments(final Object[] arguments,
      Actor target, final Actor currentSender, final Actor originalSender) {
    VM.thisMethodNeedsToBeOptimized("not optimized for compilation");

    // target: the owner of the promise that just got resolved
    // however, if a promise gets resolved to a far reference
    // we need to redirect the message to the owner of that far reference

    Object receiver = target.wrapForUse(arguments[0], currentSender, null);
    assert !(receiver instanceof SPromise) : "TODO: handle this case as well?? Is it possible? didn't think about it";

    if (receiver instanceof SFarReference) {
      // now we are about to send a message to a far reference, so, it
      // is better to just redirect the message back to the current actor
      target   = ((SFarReference) receiver).getActor();
      receiver = ((SFarReference) receiver).getValue();
    }

    arguments[0] = receiver;

    assert !(receiver instanceof SFarReference) : "this should not happen, because we need to redirect messages to the other actor, and normally we just unwrapped this";
    assert !(receiver instanceof SPromise);

    for (int i = 1; i < arguments.length; i++) {
      arguments[i] = target.wrapForUse(arguments[i], originalSender, null);
    }

    return target;
  }

  /** A message send after a promise got resolved. */
  public abstract static class PromiseMessage extends EventualMessage {
    public static final int PROMISE_RCVR_IDX  = 0;
    public static final int PROMISE_VALUE_IDX = 1;

    protected final Actor originalSender; // initial owner of the arguments

    public PromiseMessage(final long causalMessageId, final Object[] arguments, final Actor originalSender,
        final SResolver resolver, final RootCallTarget onReceive, final boolean triggerMessageReceiverBreakpoint,
        final boolean triggerPromiseResolverBreakpoint, final boolean triggerPromiseResolutionBreakpoint) {
      super(causalMessageId, arguments, resolver, onReceive, triggerMessageReceiverBreakpoint,
          triggerPromiseResolverBreakpoint, triggerPromiseResolutionBreakpoint);
      this.originalSender = originalSender;
    }

    public abstract void resolve(final Object rcvr, final Actor target, final Actor sendingActor);

    @Override
    public final Actor getSender() {
      assert originalSender != null;
      return originalSender;
    }

    public abstract SPromise getPromise();
  }

  /**
   * A message that was send with <-: to a promise, and will be delivered
   * after the promise is resolved.
   */
  public static final class PromiseSendMessage extends PromiseMessage {
    private final SSymbol selector;
    protected Actor target;
    protected Actor finalSender;
    protected final SPromise originalTarget;

    protected PromiseSendMessage(final long causalMessageId, final SSymbol selector,
        final Object[] arguments, final Actor originalSender,
        final SResolver resolver, final RootCallTarget onReceive, final boolean triggerMessageReceiverBreakpoint,
        final boolean triggerPromiseResolverBreakpoint, final boolean triggerPromiseResolutionBreakpoint) {
      super(causalMessageId, arguments, originalSender, resolver, onReceive, triggerMessageReceiverBreakpoint,
          triggerPromiseResolverBreakpoint, triggerPromiseResolutionBreakpoint);
      this.selector = selector;
      assert (args[0] instanceof SPromise);
      this.originalTarget = (SPromise) args[0];
    }

    @Override
    public void resolve(final Object rcvr, final Actor target, final Actor sendingActor) {
      determineAndSetTarget(rcvr, target, sendingActor);
    }

    private void determineAndSetTarget(final Object rcvr, final Actor target, final Actor sendingActor) {
      VM.thisMethodNeedsToBeOptimized("not optimized for compilation");

      args[0] = rcvr;
      Actor finalTarget = determineTargetAndWrapArguments(args, target, sendingActor, originalSender);

      this.target      = finalTarget; // for sends to far references, we need to adjust the target
      this.finalSender = sendingActor;
    }

    @Override
    public SSymbol getSelector() {
      return selector;
    }

    @Override
    public Actor getTarget() {
      assert target != null;
      return target;
    }

    @Override
    public String toString() {
      String t;
      if (target == null) {
        t = "null";
      } else {
        t = target.toString();
      }
      return "PSendMsg(" + selector.toString() + " " + Arrays.toString(args) +  ", " + t
        + ", sender: " + (finalSender == null ? "" : finalSender.toString()) + ")";
    }

    @Override
    public SPromise getPromise() {
      return originalTarget;
    }
  }

  /** The callback message to be send after a promise is resolved. */
  public static final class PromiseCallbackMessage extends PromiseMessage {
    protected final SPromise promise;

    public PromiseCallbackMessage(final long causalMessageId, final Actor owner, final SBlock callback,
        final SResolver resolver, final RootCallTarget onReceive, final boolean triggerMessageReceiverBreakpoint,
        final boolean triggerPromiseResolverBreakpoint, final boolean triggerPromiseResolutionBreakpoint, final SPromise parent) {
      super(causalMessageId, new Object[] {callback, null}, owner, resolver, onReceive,
          triggerMessageReceiverBreakpoint, triggerPromiseResolverBreakpoint, triggerPromiseResolutionBreakpoint);
      this.promise = parent;
    }

    @Override
    public void resolve(final Object rcvr, final Actor target, final Actor sendingActor) {
      setPromiseValue(rcvr, sendingActor);
    }

    /**
     * The value the promise was resolved to on which this callback is
     * registered on.
     *
     * @param resolvingActor - the owner of the value, the promise was resolved to.
     */
    private void setPromiseValue(final Object value, final Actor resolvingActor) {
      args[1] = originalSender.wrapForUse(value, resolvingActor, null);
    }

    @Override
    public SSymbol getSelector() {
      return VALUE_SELECTOR;
    }

    @Override
    public Actor getTarget() {
      assert originalSender != null;
      return originalSender;
    }

    @Override
    public String toString() {
      return "PCallbackMsg(" + Arrays.toString(args) + ")";
    }

    @Override
    public SPromise getPromise() {
      return promise;
    }
  }

  public final void execute() {
    try {
      executeMessage();
    } catch (ThreadDeath t) {
      throw t;
    } catch (Throwable t) {
      VM.errorPrintln("EventualMessage: " + toString());
      t.printStackTrace();
      VM.errorExit("EventualMessage failed with Exception.");
    }
  }

  protected final void executeMessage() {
    VM.thisMethodNeedsToBeOptimized("Not Optimized! But also not sure it can be part of compilation anyway");

    Object rcvrObj = args[0];
    assert rcvrObj != null;

    assert !(rcvrObj instanceof SFarReference);
    assert !(rcvrObj instanceof SPromise);

    assert onReceive.getRootNode() instanceof ReceivedMessage || onReceive.getRootNode() instanceof ReceivedCallback;
    onReceive.call(this);
  }

  public static Actor getActorCurrentMessageIsExecutionOn() {
    Thread t = Thread.currentThread();
    return ((ActorProcessingThread) t).currentlyExecutingActor;
  }

  public static EventualMessage getCurrentExecutingMessage() {
    Thread t = Thread.currentThread();
    return ((ActorProcessingThread) t).currentMessage;
  }

  public static long getCurrentExecutingMessageId() {
    Thread t = Thread.currentThread();
    return ((ActorProcessingThread) t).currentMessageId;
  }

  public Object[] getArgs() {
    return args;
  }

  /**
   * Indicates that execution should stop and yield to the debugger,
   * before the message is processed.
   */
  public boolean isBreakpoint() {
    return triggerMessageReceiverBreakpoint;
  }

  /**
   * Updates the value of the flag for the message receiver breakpoint.
   */
  public void setIsMessageReceiverBreakpoint(final boolean triggerBreakpoint) {
    this.triggerMessageReceiverBreakpoint = triggerBreakpoint;
  }
}
