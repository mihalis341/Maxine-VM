/*
 * Copyright (c) 2007, 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.sun.max.vm.jni;

import static com.sun.max.vm.classfile.constant.PoolConstantFactory.*;
import static com.sun.max.vm.classfile.constant.SymbolTable.*;

import com.sun.max.io.*;
import com.sun.max.program.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.bytecode.graft.*;
import com.sun.max.vm.classfile.*;
import com.sun.max.vm.classfile.constant.*;
import com.sun.max.vm.log.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.thread.*;
import com.sun.max.vm.type.*;
import com.sun.max.vm.jni.JniFunctions.JxxFunctionsLogger;

/**
 * A utility class for generating bytecode that implements the transition
 * from Java to native code. Most of these transitions will made for calling a native function via JNI.
 * However, faster transitions to MaxineVM specific native code is also supported.
 * The steps performed by a generated stub are:
 * <p>
 * <ol>
 *   <li>Record the {@linkplain JniHandles#top() top} of {@linkplain VmThread#jniHandles() the current thread's JNI handle stack}.</li>
 *   <li>Push the pointer to the {@linkplain VmThread#jniEnv() current thread's native JNI environment data structure}.</li>
 *   <li>If the native method is static, {@linkplain JniHandles#createStackHandle(Object) handlize} and push the class reference
 *       otherwise handlize and push the receiver reference.</li>
 *   <li>Push the remaining parameters, handlizing non-null references before they are pushed.</li>
 *   <li>Save last Java frame info (stack, frame and instruction pointers) from thread local storage (TLS) to
 *       local variables and then update the TLS info to reflect the frame of the native stub.
 *   <li>Invoke the native function via a Maxine VM specific bytecode which also handles resolving the native function.
 *       The native function symbol is generated by {@linkplain Mangle mangling} the name and signature of the native method appropriately.</li>
 *   <li>Set the last Java instruction pointer in TLS to zero to indicate transition back into Java code.
 *   <li>If the native method returns a reference, {@linkplain JniHandle#unhand() unwrap} the returned handle.</li>
 *   <li>Restore the JNI frame as recorded in the first step.</li>
 *   <li>Throw any {@linkplain VmThread#throwJniException() pending exception} (if any) for the current thread.</li>
 *   <li>Return the result to the caller.</li>
 * </ol>
 * <p>
 */
public final class NativeStubGenerator extends BytecodeAssembler {

    public NativeStubGenerator(ConstantPoolEditor constantPoolEditor, ClassMethodActor classMethodActor) {
        super(constantPoolEditor);
        this.classMethodActor = classMethodActor;
        allocateParameters(classMethodActor.isStatic(), classMethodActor.descriptor());
        generateCode(classMethodActor.isCFunction(), classMethodActor.isStatic(), classMethodActor.holder(), classMethodActor.descriptor());
    }

    private final SeekableByteArrayOutputStream codeStream = new SeekableByteArrayOutputStream();
    private final ClassMethodActor classMethodActor;

    @Override
    public void writeByte(byte b) {
        codeStream.write(b);
    }

    @Override
    protected void setWriteBCI(int bci) {
        codeStream.seek(bci);
    }

    @Override
    public byte[] code() {
        fixup();
        return codeStream.toByteArray();
    }

    public CodeAttribute codeAttribute() {
        return new CodeAttribute(constantPool(),
                                 code(),
                                 (char) maxStack(),
                                 (char) maxLocals(),
                                 CodeAttribute.NO_EXCEPTION_HANDLER_TABLE,
                                 LineNumberTable.EMPTY,
                                 LocalVariableTable.EMPTY,
                                 null);
    }

    /**
     * These methods may be called from a generated native stub.
     */
    private static final ClassMethodRefConstant jniEnv = createClassMethodConstant(VmThread.class, makeSymbol("jniEnv"));
    private static final ClassMethodRefConstant currentThread = createClassMethodConstant(VmThread.class, makeSymbol("current"));
    private static final ClassMethodRefConstant handlesCount = createClassMethodConstant(JniHandles.class, makeSymbol("handlesCount"), SignatureDescriptor.class);
    private static final ClassMethodRefConstant throwJniException = createClassMethodConstant(VmThread.class, makeSymbol("throwJniException"));
    private static final ClassMethodRefConstant getHandle = createClassMethodConstant(JniHandles.class, makeSymbol("getHandle"), Pointer.class, int.class, Object.class);
    private static final ClassMethodRefConstant alloca = createClassMethodConstant(Intrinsics.class, makeSymbol("alloca"), int.class, boolean.class);
    private static final ClassMethodRefConstant unhandHandle = createClassMethodConstant(JniHandle.class, makeSymbol("unhand"));
    private static final ClassMethodRefConstant handlesTop = createClassMethodConstant(VmThread.class, makeSymbol("jniHandlesTop"));
    private static final ClassMethodRefConstant resetHandlesTop = createClassMethodConstant(VmThread.class, makeSymbol("resetJniHandlesTop"), int.class);
    private static final FieldRefConstant jniLogger = createFieldConstant(JniFunctions.class, makeSymbol("logger"));
    private static final FieldRefConstant downCallEntry = createFieldConstant(JxxFunctionsLogger.class, makeSymbol("DOWNCALL_ENTRY"));
    private static final FieldRefConstant downCallExit = createFieldConstant(JxxFunctionsLogger.class, makeSymbol("DOWNCALL_EXIT"));
    private static final ClassMethodRefConstant toWord = createClassMethodConstant(Address.class, makeSymbol("fromInt"), int.class);
    private static final ClassMethodRefConstant log2 = createClassMethodConstant(VMLogger.class, makeSymbol("log"), int.class, Word.class, Word.class);
    private static final ClassMethodRefConstant enabled = createClassMethodConstant(VMLogger.class, makeSymbol("enabled"));
    private static final ClassMethodRefConstant link = createClassMethodConstant(NativeFunction.class, makeSymbol("link"));
    private static final ClassMethodRefConstant nativeCallPrologue = createClassMethodConstant(Snippets.class, makeSymbol("nativeCallPrologue"), NativeFunction.class);
    private static final ClassMethodRefConstant nativeCallPrologueForC = createClassMethodConstant(Snippets.class, makeSymbol("nativeCallPrologueForC"), NativeFunction.class);
    private static final ClassMethodRefConstant nativeCallEpilogue = createClassMethodConstant(Snippets.class, makeSymbol("nativeCallEpilogue"));
    private static final ClassMethodRefConstant nativeCallEpilogueForC = createClassMethodConstant(Snippets.class, makeSymbol("nativeCallEpilogueForC"));

    private static final ClassMethodRefConstant writeObject = createClassMethodConstant(Pointer.class, makeSymbol("writeObject"), int.class, Object.class);

    private int methodIDAsInt;
    /**
     * Allocates a block of handles and copies the object arguments into the block.
     *
     * @param sig the signature determining how many object handles are needed
     * @return the index of the local variable holding the address of the handles block
     */
    private int initializeHandles(SignatureDescriptor sig, boolean isStatic) {

        int handles = allocateLocal(Kind.WORD);
        int handleOffset = 0;

        ldc(createObjectConstant(sig));
        invokestatic(handlesCount, 1, 1);

        iconst(1); // add 1 for the receiver/class argument
        iadd();

        iconst(Word.size()); // Multiply by the size of a word
        imul();

        iconst(1);
        invokestatic(alloca, 2, 1);
        astore(handles);

        aload(handles);
        iconst(handleOffset);
        if (isStatic) {
            // Push the class for a static method
            ldc(createClassConstant(classMethodActor.holder().toJava()));
        } else {
            // Push the receiver for a non-static method
            aload(0);
        }
        invokevirtual(writeObject, 3, 0);
        handleOffset += Word.size();

        // Store the reference parameters in JNI handles
        int javaIndex = isStatic ? 0 : 1;
        for (int i = 0; i < sig.numberOfParameters(); i++) {
            final TypeDescriptor parameterDescriptor = sig.parameterDescriptorAt(i);
            Kind kind = parameterDescriptor.toKind();
            if (kind.isReference) {
                aload(handles);
                iconst(handleOffset);
                aload(javaIndex);
                invokevirtual(writeObject, 3, 0);
                handleOffset += Word.size();
            }
            javaIndex += kind.stackSlots;
        }

        return handles;
    }


    private void generateCode(boolean isCFunction, boolean isStatic, ClassActor holder, SignatureDescriptor sig) {
        final TypeDescriptor resultDescriptor = sig.resultDescriptor();
        final Kind resultKind = resultDescriptor.toKind();
        final StringBuilder nativeFunctionDescriptor = new StringBuilder("(");
        int nativeFunctionArgSlots = 0;
        final TypeDescriptor nativeResultDescriptor = resultKind.isReference ? JavaTypeDescriptor.JNI_HANDLE : resultDescriptor;

        int top = 0;

        int currentThread = -1;

        int handles = -1;
        int handleOffset = 0;

        if (!isCFunction) {
            handles = initializeHandles(sig, isStatic);

            // Cache current thread in a local variable
            invokestatic(NativeStubGenerator.currentThread, 0, 1);
            currentThread = allocateLocal(Kind.REFERENCE);
            astore(currentThread);

            methodIDAsInt = MethodID.fromMethodActor(classMethodActor).asAddress().toInt();
            logJniEntry();

            // Save current JNI frame.
            top = allocateLocal(Kind.INT);
            aload(currentThread);
            invokevirtual(handlesTop, 1, 1);
            istore(top);

            // Push the JNI environment variable
            invokestatic(jniEnv, 0, 1);

            final TypeDescriptor jniEnvDescriptor = jniEnv.signature(constantPool()).resultDescriptor();
            nativeFunctionDescriptor.append(jniEnvDescriptor);
            nativeFunctionArgSlots += jniEnvDescriptor.toKind().stackSlots;

            // Push the handle for the receiver/class
            assert handleOffset == 0;
            aload(handles);
            handleOffset += Word.size();

            nativeFunctionDescriptor.append(JavaTypeDescriptor.WORD);
            nativeFunctionArgSlots += Kind.WORD.stackSlots;

        } else {
            assert isStatic;
        }

        // Push the remaining parameters, wrapping reference parameters in JNI handles
        int parameterLocalIndex = isStatic ? 0 : 1;
        for (int i = 0; i < sig.numberOfParameters(); i++) {
            final TypeDescriptor parameterDescriptor = sig.parameterDescriptorAt(i);
            TypeDescriptor nativeParameterDescriptor = parameterDescriptor;
            switch (parameterDescriptor.toKind().asEnum) {
                case BYTE:
                case BOOLEAN:
                case SHORT:
                case CHAR:
                case INT: {
                    iload(parameterLocalIndex);
                    break;
                }
                case FLOAT: {
                    fload(parameterLocalIndex);
                    break;
                }
                case LONG: {
                    lload(parameterLocalIndex);
                    ++parameterLocalIndex;
                    break;
                }
                case DOUBLE: {
                    dload(parameterLocalIndex);
                    ++parameterLocalIndex;
                    break;
                }
                case WORD: {
                    aload(parameterLocalIndex);
                    break;
                }
                case REFERENCE: {
                    assert !isCFunction;

                    aload(handles);
                    iconst(handleOffset);
                    aload(parameterLocalIndex);
                    invokestatic(getHandle, 3, 1);
                    handleOffset += Word.size();

                    nativeParameterDescriptor = JavaTypeDescriptor.JNI_HANDLE;

                    break;
                }
                case VOID: {
                    throw ProgramError.unexpected();
                }
            }
            nativeFunctionDescriptor.append(nativeParameterDescriptor);
            nativeFunctionArgSlots += nativeParameterDescriptor.toKind().stackSlots;
            ++parameterLocalIndex;
        }

        // Link native function
        ObjectConstant nf = createObjectConstant(classMethodActor.nativeFunction);
        ldc(nf);
        invokevirtual(link, 1, 1);

        if (NativeInterfaces.needsPrologueAndEpilogue(classMethodActor)) {
            ldc(nf);
            invokestatic(!isCFunction ? nativeCallPrologue : nativeCallPrologueForC, 1, 0);
        }

        // Invoke the native function
        callnative(SignatureDescriptor.create(nativeFunctionDescriptor.append(')').append(nativeResultDescriptor).toString()), nativeFunctionArgSlots, nativeResultDescriptor.toKind().stackSlots);

        if (NativeInterfaces.needsPrologueAndEpilogue(classMethodActor)) {
            invokestatic(!isCFunction ? nativeCallEpilogue : nativeCallEpilogueForC, 0, 0);
        }

        if (!isCFunction) {
            // Unwrap a reference result from its enclosing JNI handle. This must be done
            // *before* the JNI frame is restored.
            if (resultKind.isReference) {
                invokevirtual(unhandHandle, 1, 1);
            }

            // Restore JNI frame.
            aload(currentThread);
            iload(top);
            invokevirtual(resetHandlesTop, 2, 0);

            logJniExit();

            // throw (and clear) any pending exception
            aload(currentThread);
            invokevirtual(throwJniException, 1, 0);
        }

        // Return result
        if (resultKind.isReference) {
            assert !isCFunction;

            // Insert cast if return type is not java.lang.Object
            if (resultDescriptor != JavaTypeDescriptor.OBJECT) {
                checkcast(createClassConstant(resultDescriptor));
            }
        }

        return_(resultKind);
    }

    private void logJni(FieldRefConstant callType) {
        getstatic(jniLogger);
        invokevirtual(enabled, 1, 1);
        final Label noTracing = newLabel();
        ifeq(noTracing);
        getstatic(jniLogger);
        ldc(PoolConstantFactory.createIntegerConstant(JniFunctions.LogOperations.NativeMethodCall.ordinal()));
        getstatic(callType);
        ldc(PoolConstantFactory.createIntegerConstant(methodIDAsInt));
        invokestatic(toWord, 1, 1);
        invokevirtual(log2, 4, 0);
        noTracing.bind();
    }

    private void logJniEntry() {
        logJni(downCallEntry);
    }

    private void logJniExit() {
        logJni(downCallExit);
    }

}
