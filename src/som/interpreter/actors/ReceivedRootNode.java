package som.interpreter.actors;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

import som.VM;
import som.interpreter.actors.SPromise.SResolver;
import som.interpreter.SomException;
import som.vm.VmSettings;
import som.vm.NotYetImplementedException;
import som.vmobjects.SAbstractObject;
import tools.debugger.WebDebugger;


public abstract class ReceivedRootNode extends RootNode {

  @Child protected ResolvePromiseNode resolve;
  @Child protected RuinPromiseNode ruin;

  protected final WebDebugger dbg;

  protected ReceivedRootNode(final Class<? extends TruffleLanguage<?>> language,
      final SourceSection sourceSection, final FrameDescriptor frameDescriptor) {
    super(language, sourceSection, frameDescriptor);
    assert sourceSection != null;
    if (VmSettings.TRUFFLE_DEBUGGER_ENABLED) {
      this.dbg = VM.getWebDebugger();
    } else {
      this.dbg = null;
    }
  }

  protected final void resolvePromise(final VirtualFrame frame,
      final SResolver resolver, final Object result,
      final boolean isBreakpointOnPromiseResolution) {
    // lazy initialization of resolution node
    if (resolve == null) {
      CompilerDirectives.transferToInterpreterAndInvalidate();
      if (resolver == null) {
        this.resolve = insert(new NullResolver(getSourceSection()));
      } else {
        this.resolve = insert(ResolvePromiseNodeFactory.create(false, getSourceSection(), null, null, null));
      }
    }

    // resolve promise
    resolve.executeEvaluated(frame, resolver, result, isBreakpointOnPromiseResolution);
  }

  protected final void ruinPromise(final VirtualFrame frame,
      final SResolver resolver, final SAbstractObject exception) {
    // lazy initialization of resolution node
    if (ruin == null) {
      CompilerDirectives.transferToInterpreterAndInvalidate();
      if (resolver == null) {
        throw new SomException(exception); //I'm ruining a promise without a resolver. No onError can be added so rethrow the exception
      } else {
        this.ruin = insert(RuinPromiseNodeFactory.create(false, getSourceSection(), null, null));
      }
    }

    // ruin promise
    ruin.executeEvaluated(frame, resolver, exception);
  }

  /**
   * Promise resolver for the case that the actual promise has been optimized out.
   */
  public final class NullResolver extends ResolvePromiseNode {
    public NullResolver(final SourceSection source) {
      super(false, source);
    }

    @Override
    public Object executeEvaluated(final VirtualFrame frame,
        final SResolver receiver, final Object argument,
        final boolean isBreakpointOnPromiseResolution) {
      assert receiver == null;
      /* TODO: add a tag to the send node to disable or remove the button
       * if (isBreakpointOnResolutionAtRcvr) {} */
      return null;
    }

    @Override
    public Object executeEvaluated(final VirtualFrame frame, final Object receiver,
        final Object argument, final Object isBreakpointOnPromiseResolution) {
      return null;
    }

    @Override
    public Object executeGeneric(final VirtualFrame frame) {
      return null;
    }
  }
}
