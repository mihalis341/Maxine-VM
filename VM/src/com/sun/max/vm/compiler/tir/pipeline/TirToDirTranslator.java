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
package com.sun.max.vm.compiler.tir.pipeline;

import com.sun.max.collect.*;
import com.sun.max.lang.*;
import com.sun.max.program.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.bytecode.*;
import com.sun.max.vm.compiler.dir.*;
import com.sun.max.vm.compiler.dir.transform.*;
import com.sun.max.vm.compiler.ir.IrBlock.*;
import com.sun.max.vm.compiler.tir.*;
import com.sun.max.vm.compiler.tir.TirInstruction.*;
import com.sun.max.vm.compiler.tir.pipeline.TirToDirTranslator.VariableAllocator.*;
import com.sun.max.vm.hotpath.*;
import com.sun.max.vm.hotpath.state.*;
import com.sun.max.vm.type.*;
import com.sun.max.vm.value.*;

public class TirToDirTranslator extends TirPipelineFilter  {
    public static class VariableAllocator {
        private static enum VariableType {
            CLEAN, DIRTY
        }

        private int _serial = 0;
        private VariableSequence<DirVariable> [] _dirtyStacks = UnsafeLoophole.cast(Arrays.newInstance(VariableSequence.class, KindEnum.VALUES.length()));
        private VariableSequence<DirVariable> dirtyStack(Kind kind) {
            return _dirtyStacks[kind.asEnum().ordinal()];
        }

        private VariableMapping<TirInstruction, DirVariable> _bindings = new IdentityHashMapping<TirInstruction, DirVariable>();

        public VariableAllocator() {
            for (Kind kind : new Kind[] {Kind.INT, Kind.FLOAT, Kind.LONG, Kind.DOUBLE, Kind.REFERENCE, Kind.VOID}) {
                _dirtyStacks[kind.asEnum().ordinal()] = new ArrayListSequence<DirVariable>();
            }
        }

        public DirVariable allocate(Kind kind, VariableType type) {
            if (type == VariableType.CLEAN || dirtyStack(kind).isEmpty()) {
                return new DirVariable(kind, _serial++);
            }
            return dirtyStack(kind).removeLast();
        }

        public void bind(TirInstruction instruction, DirVariable variable) {
            _bindings.put(instruction, variable);
        }

        public DirVariable allocate(TirInstruction instruction, VariableType type) {
            DirVariable variable = variable(instruction);
            if (variable == null) {
                variable = allocate(instruction.kind(), type);
                bind(instruction, variable);
            }
            return variable;
        }

        public DirVariable variable(TirInstruction instruction) {
            return _bindings.get(instruction);
        }

        public boolean isLive(TirInstruction instruction) {
            return variable(instruction) != null;
        }

        public void free(TirInstruction instruction) {
            final DirVariable variable = _bindings.remove(instruction);
            ProgramError.check(variable != null);
            dirtyStack(variable.kind()).append(variable);
        }
    }

    private static final ClassMethodActor _bailoutMethod = HotpathSnippet.CallBailout.SNIPPET.classMethodActor();
    private static final ClassMethodActor _saveRegistersMethod = HotpathSnippet.SaveRegisters.SNIPPET.classMethodActor();

    private DirTree _dirTree;
    private DirVariable[] _parameters;
    private VariableSequence<DirBlock> _blocks = new ArrayListSequence<DirBlock>();
    private GrowableDeterministicSet<DirGoto> _loopPatchList = new LinkedIdentityHashSet<DirGoto>();
    private VariableAllocator _allocator = new VariableAllocator();

    /*
     * === LOCAL =================
     *
     * === PROLOGUE ==============
     *
     * === TRACE =================
     *
     * === TRACE =================
     *
     * === TRACE =================
     *
     * === BAILOUT ===============
     *
     * ===========================
     */

    private DirBlock _localBlock = new DirBlock(Role.NORMAL);
    private DirBlock _prologueBlock = new DirBlock(Role.NORMAL);
    private DirBlock _bailoutBlock = new DirBlock(Role.NORMAL);
    private DirVariable _bailoutGuard = _allocator.allocate(Kind.REFERENCE, VariableType.CLEAN);

    private int _serial;

    public TirToDirTranslator() {
        super(TirPipelineOrder.REVERSE, TirVoidSink.SINK);
    }

    public DirTree method() {
        ProgramError.check(_dirTree.isGenerated());
        return _dirTree;
    }

    private DirBlock current() {
        return _blocks.first();
    }

    private void emitBlockIfNotEmpty(DirBlock block) {
        if (block.isEmpty() == false) {
            emitBlock(block);
        }
    }

    private void emitBlock(DirBlock block) {
        block.setSerial(_blocks.length());
        if (_blocks.isEmpty() == false) {
            link(block, current());
        }
        _blocks.prepend(block);
    }

    private void link(DirBlock a, DirBlock b) {
        a.successors().add(b);
        b.predecessors().add(a);
    }

    private void emitInstruction(DirInstruction... instructions) {
        for (int i = instructions.length - 1; i >= 0; i--) {
            current().instructions().prepend(instructions[i]);
        }
    }

    private DirVariable var(TirInstruction instruction) {
        return (DirVariable) use(instruction);
    }

    private DirValue use(TirInstruction instruction) {
        if (instruction instanceof TirConstant) {
            final TirConstant constant = (TirConstant) instruction;
            return new DirConstant(constant.value());
        } else if (instruction instanceof Placeholder) {
            return DirValue.UNDEFINED;
        }
        return _allocator.allocate(instruction, variableType(instruction));
    }

    private DirValue[] useMany(TirInstruction... instruction) {
        final DirValue[] variables = new DirValue[instruction.length];
        for (int i = 0; i < instruction.length; i++) {
            variables[i] = use(instruction[i]);
        }
        return variables;
    }

    private VariableType variableType(TirInstruction instruction) {
        if (isInvariant(instruction)) {
            return VariableType.CLEAN;
        }
        return VariableType.DIRTY;
    }

    private void emitCall(ClassMethodActor method, TirState state, DirValue... arguments) {
        final DirVariable result;
        if (method.resultKind() != Kind.VOID) {
            result = _allocator.allocate(method.resultKind(), VariableType.DIRTY);
        } else {
            result = null;
        }
        final DirJavaFrameDescriptor frameDescriptor = tirStateToJavaFrameDescriptor(state);
        final DirMethodCall call = new DirMethodCall(result, new DirMethodValue(method), arguments, null, false, frameDescriptor);
        emitInstruction(call);
    }

    private DirJavaFrameDescriptor tirStateToJavaFrameDescriptor(TirState state) {
        final DirJavaFrameDescriptor frameDescriptor;
        if (state != null) {
            final DirValue[] locals = useMany(state.getLocalSlots());
            final DirValue[] stack = useMany(state.getStackSlots());
            final BytecodeLocation location = state.last().location();
            frameDescriptor = new DirJavaFrameDescriptor(null, location.classMethodActor(), location.bytecodePosition(), locals, stack);
        } else {
            frameDescriptor = null;
        }
        return frameDescriptor;
    }

    @Override
    public void beginTree() {
        _dirTree = new DirTree(tree(), tree().anchor().method());
        _parameters = new DirVariable[tree().entryState().length()];
        tree().entryState().visit(new StateVisitor<TirInstruction>() {
            @Override
            public void visit(TirInstruction entry) {
                final TirLocal local = (TirLocal) entry;
                if (local.flags().isRead()) {
                    _parameters[_index] = (DirVariable) use(entry);
                } else {
                    _parameters[_index] = _allocator.allocate(Kind.VOID, VariableType.DIRTY);
                }
                if (local.kind().isCategory2()) {
                    _parameters[_index + 1] = _allocator.allocate(Kind.VOID, VariableType.DIRTY);
                }
            }
        });

        emitBlock(_bailoutBlock);
        emitInstruction(new DirReturn(_bailoutGuard));
        emitCall(_bailoutMethod, null, _bailoutGuard);
        emitCall(_saveRegistersMethod, null);
    }

    @Override
    public void beginTrace() {
        emitBlock(new DirBlock(Role.NORMAL));
        final DirGoto dirGoto = new DirGoto(null);
        _loopPatchList.add(dirGoto);
        emitInstruction(dirGoto);
        emitLoopbacks(tree().entryState(), trace().tailState());
    }

    @Override
    public void endTree() {
        patchLoops();
        emitBlockIfNotEmpty(_prologueBlock);
        emitBlockIfNotEmpty(_localBlock);
        _dirTree.setGenerated(_parameters, new ArrayListSequence<DirBlock>(_blocks));
    }

    @Override
    public void visit(TirBuiltinCall call) {
        final DirVariable result = _allocator.variable(call);
        final DirBuiltinCall dirCall = new DirBuiltinCall(result, call.builtin(), useMany(call.operands()), null, null);
        emitInstruction(dirCall);
    }

    @Override
    public void visit(TirMethodCall call) {
        emitCall(call.method(), call.state(), useMany(call.operands()));
    }

    @Override
    public void visit(TirGuard guard) {
        // Jump to bail-out block if guard fails.
        final DirSwitch dirSwitch = new DirSwitch(guard.kind(),
                                                  guard.valueComparator().complement(),
                                                  use(guard.operand0()),
                                                  Arrays.fromElements(use(guard.operand1())),
                                                  Arrays.fromElements(_bailoutBlock), current());
        emitBlock(new DirBlock(Role.NORMAL));
        emitInstruction(dirSwitch);

        // Capture state.
        final DirGuardpoint dirGuardpoint = new DirGuardpoint(tirStateToJavaFrameDescriptor(guard.state()));
        emitInstruction(dirGuardpoint);

        // Assign guard constant to the bail-out guard variable.
        final DirAssign dirAssignment = new DirAssign(_bailoutGuard, createConstant(guard));
        emitInstruction(dirAssignment);
    }

    private DirConstant createConstant(Object object) {
        return new DirConstant(ObjectReferenceValue.from(object));
    }

    @Override
    public void visit(TirDirCall call) {
        Problem.unimplemented("Dir Inlining not supported yet.");
    }

    private void patchLoops() {
        for (DirGoto dirGoto : _loopPatchList) {
            dirGoto.setTargetBlock(current());
        }
    }

    /**
     * Generate assignments for loop variables, variables that are used and updated on the trace. We need to generate
     * assignments for parallel moves. For example: (x,y,z) <= (y,x,x), would require the following assignment sequence:
     *
     * z <= x;
     * t <= x; We need a temporary t, to break the cycle.
     * x <= y;
     * y <= t;
     *
     * even more intelligently, we could generate:
     *
     * z <= x;
     * x <= y;
     * y <= z;
     *
     */
    private void emitLoopbacks(TirState entryState, TirState tailState) {
        final VariableSequence<Pair<TirInstruction, TirLocal>> writes = new ArrayListSequence<Pair<TirInstruction, TirLocal>>();

        // Accumulate loop variables that need to be written back. These are locals that are used
        // in the trace tree and then updated.
        entryState.compare(tailState, new StatePairVisitor<TirInstruction, TirInstruction>() {
            @Override
            public void visit(TirInstruction entry, TirInstruction tail) {
                final TirLocal local = (TirLocal) entry;
                if (local.flags().isRead() && local != tail) {
                    writes.append(new Pair<TirInstruction, TirLocal>(tail, local));
                }
            }
        });

        // Emit writes in topological order until there are no writes left. If we end up in a cycle, we emit a
        // temporary variable.
        // Checkstyle: stop
        while (writes.isEmpty() == false) {
            for (int i = 0; i < writes.length(); i++) {
                final Pair<TirInstruction, TirLocal> write = writes.get(i);
                boolean writeDestinationIsUsed = false;
                // Can we find another write that has a dependency on the destination?
                for (int j = 0; j < writes.length(); j++) {
                    if (writes.get(j).first() == write.second() && i != j) {
                        writeDestinationIsUsed = true;
                        break;
                    }
                }
                if (writeDestinationIsUsed == false) {
                    // No dependency, just emit the move and remove it from the work list.
                    emitWrite(var(write.second()), use(write.first()));
                    writes.remove(i--);
                } else {
                    ProgramError.unexpected();
                    // final DirVariable temporay = createVariable(write.first().kind());
                    // There's a dependency on the destination. Save the destination to a temporary.
                    // emitWrite(use(write.second()), temporay);
                    // emitWrite(use(write.first()), def(write.second()));
                }
            }
        }
        // Checkstyle: resume
    }

    private void emitWrite(DirVariable destination, DirValue source) {
        emitInstruction(new DirAssign(destination, source));
    }

    private void emit(DirMethod method, DirValue... arguments) {

    }

    private Sequence<DirBlock> inline(DirMethod method, DirValue... arguments) {
        Trace.stream().println(method.traceToString());

        final GrowableMapping<DirBlock, DirBlock> blockMap = new IdentityHashMapping<DirBlock, DirBlock>();
        final GrowableMapping<DirValue, DirValue> valueMap = new IdentityHashMapping<DirValue, DirValue>();

        // Map parameters onto arguments.
        for (int i = 0; i < method.parameters().length; i++) {
            valueMap.put(method.parameters()[i], arguments[i]);
        }

        // Map blocks.
        for (DirBlock block : method.blocks()) {
            blockMap.put(block, new DirBlock(block.role()));
        }

        // Map instructions.
        for (DirBlock block : method.blocks()) {
            for (DirInstruction instruction : block.instructions()) {
                instruction.acceptVisitor(new DirVisitor() {
                    @Override
                    public void visitAssign(DirAssign dirAssign) {
                        Problem.unimplemented();
                    }

                    @Override
                    public void visitBuiltinCall(DirBuiltinCall dirBuiltinCall) {
                        Problem.unimplemented();
                    }

                    @Override
                    public void visitGoto(DirGoto dirGoto) {
                        Problem.unimplemented();
                    }

                    @Override
                    public void visitMethodCall(DirMethodCall dirMethodCall) {
                        Problem.unimplemented();
                        // final DirVariable result = dirMethodCall.result();
                        // final DirVariable callResult = _allocator.allocate(result.kind(), VariableType.DIRTY);
                        // final DirMethodCall call = new DirMethodCall(callResult, dirMethodCall.method(), );
                    }

                    @Override
                    public void visitReturn(DirReturn dirReturn) {
                        Problem.unimplemented();
                    }

                    @Override
                    public void visitSafepoint(DirSafepoint safepoint) {
                        Problem.unimplemented();
                    }

                    @Override
                    public void visitSwitch(DirSwitch dirSwitch) {
                        Problem.unimplemented();
                    }

                    @Override
                    public void visitThrow(DirThrow dirThrow) {
                        Problem.unimplemented();
                    }

                    @Override
                    public void visitGuardpoint(DirGuardpoint guardpoint) {
                        Problem.unimplemented();
                    }

                    @Override
                    public void visitJump(DirJump dirJump) {
                        Problem.unimplemented();
                    }
                });
            }
        }

        return new ArrayListSequence<DirBlock>(blockMap.values());
    }
}
