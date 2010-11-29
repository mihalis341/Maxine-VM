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
package com.sun.max.vm.cps.eir;

import com.sun.max.*;
import com.sun.max.annotate.*;
import com.sun.max.collect.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.member.*;

/**
 * Eir ABI aggregate.
 *
 * @author Bernd Mathiske
 */
public abstract class EirABIsScheme<EirRegister_Type extends EirRegister> extends AbstractVMScheme implements VMScheme {

    public final EirABI<EirRegister_Type> javaABI;
    public final EirABI<EirRegister_Type> templateABI;

    /**
     * The ABI for a {@code native} method annotated with {@link C_FUNCTION}. If more than one compiler scheme is in use
     * with differing calling conventions, frame adapters need to be generated for such methods.
     */
    public final EirABI<EirRegister_Type> j2cFunctionABI;

    /**
     * The ABI for a Java method than is only called from native code. These are all the methods annotated
     * with {@link VM_ENTRY_POINT}. These methods only need a single entry point and have no frame adapter.
     */
    public final EirABI<EirRegister_Type> c2jFunctionABI;

    public final EirABI<EirRegister_Type> nativeABI;
    public final EirABI<EirRegister_Type> treeABI;

    public abstract EirRegister_Type safepointLatchRegister();

    /**
     *
     * @param javaABI
     * @param nativeABI the ABI
     * @param j2cFunctionABI ABI for {@linkplain C_FUNCTION VM exit} methods
     * @param c2jFunctionABI ABI for {@linkplain VM_ENTRY_POINT VM entry} methods
     * @param trampolineABI
     * @param templateABI
     * @param treeABI abi for tree calls.
     */
    @HOSTED_ONLY
    protected EirABIsScheme(EirABI<EirRegister_Type> javaABI,
                            EirABI<EirRegister_Type> nativeABI,
                            EirABI<EirRegister_Type> j2cFunctionABI,
                            EirABI<EirRegister_Type> c2jFunctionABI,
                            EirABI<EirRegister_Type> templateABI,
                            EirABI<EirRegister_Type> treeABI
                            ) {
        this.javaABI = javaABI;
        this.templateABI = templateABI;
        this.j2cFunctionABI = j2cFunctionABI;
        this.c2jFunctionABI = c2jFunctionABI;
        this.nativeABI = nativeABI;
        this.treeABI = treeABI;
        assert Utils.indexOfIdentical(c2jFunctionABI.calleeSavedRegisters(), safepointLatchRegister()) != -1;
    }

    /**
     * Gets the ABI for a given method.
     *
     * @param classMethodActor the method for which the calling conventions are being requested
     * @return
     */
    public EirABI getABIFor(ClassMethodActor classMethodActor) {
        final MethodActor compilee = classMethodActor.compilee();
        if (compilee.isVmEntryPoint()) {
            return c2jFunctionABI;
        }
        if (compilee.isCFunction()) {
            return j2cFunctionABI;
        }
        if (compilee.isTemplate()) {
            return templateABI;
        }
        return javaABI;
    }

    public abstract Pool<EirRegister_Type> registerPool();
}
