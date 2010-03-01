/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.max.tele.debug;

import com.sun.max.program.*;
import com.sun.max.tele.*;
import com.sun.max.tele.debug.TeleStackFrameWalker.*;
import com.sun.max.tele.method.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.stack.*;

/**
 * Hierarchy of classes that act as wrappers for VM instances of {@link StackFrame}, with additional
 * contextual information added for the benefits of clients.  The hierarchy also includes the two subclasses
 * {@link ErrorFrame} and {@link TruncatedFrame}, which are <em>synthetic</em>: they
 * correspond to no VM frame types, but are rather used to as markers by the stack walker for errors
 * and compression of long stacks respectively.
 *
 * @author Michael Van De Vanter
 */
public abstract class TeleStackFrame<StackFrame_Type extends StackFrame> extends AbstractTeleVMHolder implements MaxStackFrame {

    /**
     * Factory method for wrapping VM (and synthetic) stack frames with additional information, in a type
     * hierarchy that partially mirrors the types of frames.
     *
     * @param teleVM the VM
     * @param teleStack the stack containing the frame
     * @param position the position in the stack of the frame; position 0 is the top
     * @param stackFrame the frame to be wrapped
     * @return a newly created instance of {@link TeleStackFrame}
     */
    public static TeleStackFrame createFrame(TeleVM teleVM, TeleStack teleStack, int position, StackFrame stackFrame) {
        if (stackFrame instanceof CompiledStackFrame) {
            final CompiledStackFrame compiledStackFrame = (CompiledStackFrame) stackFrame;
            return new CompiledFrame(teleVM, teleStack, position, compiledStackFrame);
        }
        if (stackFrame instanceof NativeStackFrame) {
            final NativeStackFrame nativeStackFrame = (NativeStackFrame) stackFrame;
            return new NativeFrame(teleVM, teleStack, position, nativeStackFrame);
        }
        if (stackFrame instanceof ErrorStackFrame) {
            final ErrorStackFrame errorStackFrame = (ErrorStackFrame) stackFrame;
            return new ErrorFrame(teleVM, teleStack, position, errorStackFrame);
        }
        ProgramError.unexpected("Unknown stack frame kind");
        return null;
    }

    protected final StackFrame_Type stackFrame;
    private final TeleStack teleStack;
    private final int position;
    private final CodeLocation codeLocation;

    protected TeleStackFrame(TeleVM teleVM, TeleStack teleStack, int position, StackFrame_Type stackFrame) {
        super(teleVM);
        this.stackFrame = stackFrame;
        this.teleStack = teleStack;
        this.position = position;

        CodeLocation location = null;
        Pointer instructionPointer = stackFrame.ip;
        if (!instructionPointer.isZero()) {
            final StackFrame callee = stackFrame.calleeFrame();
            if (callee == null) {
                // Top frame, not a call return so no adjustment.
                location = teleVM.codeManager().createMachineCodeLocation(instructionPointer, "top stack frame IP");
            } else {
                // A call frame; record the return location, the next to be executed upon return.
                // Add a platform-specific offset from the stored code address to the actual call return site.
                final TargetMethod calleeTargetMethod = callee.targetMethod();
                if (calleeTargetMethod != null) {
                    final ClassMethodActor calleeClassMethodActor = calleeTargetMethod.classMethodActor();
                    if (calleeClassMethodActor != null) {
                        if (calleeClassMethodActor.isTrapStub()) {
                            // Special case, where the IP caused a trap; no adjustment.
                            location = teleVM.codeManager().createMachineCodeLocation(instructionPointer, "stack frame return");
                        }
                    }
                }
                if (location == null) {
                    // An ordinary call; apply a platform-specific adjustment to get the real return address.
                    final int offsetToReturnPC = teleVM.vmConfiguration().platform.processorKind.instructionSet.offsetToReturnPC;
                    location = teleVM.codeManager().createMachineCodeLocation(instructionPointer.plus(offsetToReturnPC), "stack frame return");
                }
            }
        }
        this.codeLocation = location;
    }

    public final MaxStack stack() {
        return teleStack;
    }

    public final int position() {
        return position;
    }

    public final boolean isTop() {
        return position == 0;
    }

    public Pointer ip() {
        return stackFrame.ip;
    }

    public Pointer sp() {
        return stackFrame.sp;
    }

    public Pointer fp() {
        return stackFrame.fp;
    }

    public final MaxCodeLocation codeLocation() {
        return codeLocation;
    }

    public final TargetMethod targetMethod() {
        return stackFrame.targetMethod();
    }

    public boolean isSameFrame(MaxStackFrame maxStackFrame) {
        // By default, delegate definition of "same" to the wrapped frames.
        final TeleStackFrame otherStackFrame = (TeleStackFrame) maxStackFrame;
        return this.stackFrame.isSameFrame(otherStackFrame.stackFrame);
    }

    @Override
    public String toString() {
        return Integer.toString(position) + ":  " + description();
    }

    static final class CompiledFrame extends TeleStackFrame<CompiledStackFrame> implements MaxStackFrame.Compiled {

        protected CompiledFrame(TeleVM teleVM, TeleStack teleStack, int position, CompiledStackFrame compiledStackFrame) {
            super(teleVM, teleStack, position, compiledStackFrame);
        }

        public CompiledStackFrameLayout layout() {
            return stackFrame.layout;
        }

        public Pointer slotBase() {
            return stackFrame.slotBase();
        }

        public Offset biasedFPOffset(Offset offset) {
            return stackFrame.biasedFPOffset(offset);
        }

        public StackBias bias() {
            return stackFrame.bias();
        }

        public String description() {
            return "<" + stackFrame.getClass().getSimpleName() + "> " + stackFrame.toString();
        }
    }

    static final class NativeFrame extends TeleStackFrame<NativeStackFrame> implements MaxStackFrame.Native {

        private NativeFrame(TeleVM teleVM, TeleStack teleStack, int position, NativeStackFrame nativeStackFrame) {
            super(teleVM, teleStack, position, nativeStackFrame);
        }

        @Override
        public String description() {
            return "<Native stack frame>  " + stackFrame.toString();
        }
    }

    static final class ErrorFrame extends TeleStackFrame<ErrorStackFrame> implements MaxStackFrame.Error {

        private ErrorFrame(TeleVM teleVM, TeleStack teleStack, int position, ErrorStackFrame errorStackFrame) {
            super(teleVM, teleStack, position, errorStackFrame);
        }

        @Override
        public Pointer ip() {
            return Pointer.zero();
        }

        @Override
        public Pointer sp() {
            return Pointer.zero();
        }

        @Override
        public Pointer fp() {
            return Pointer.zero();
        }

        public String errorMessage() {
            return stackFrame.errorMessage();
        }

        public String description() {
            return "<error: " + errorMessage() + ">" + stackFrame.toString();
        }
    }

}