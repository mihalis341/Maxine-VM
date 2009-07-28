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
package com.sun.max.jdwp.vm.data;

/**
 * This class represents a set of registers. Each register is uniquely identified by a String name. The register names are stored in a String array.
 * The register value of each register is at the corresponding position in the register value array. A register value is always represented as a long.
 *
 * @author Thomas Wuerthinger
 *
 */
public class Registers extends AbstractSerializableObject {

    private String name;
    private String[] names;
    private long[] values;

    public Registers(String name, String[] names, long[] values) {
        this.name = name;
        this.names = names;
        this.values = values;
    }

    public String getName() {
        return name;
    }

    public String[] getRegisterNames() {
        return names;
    }

    public long[] getRegisterValues() {
        return values;
    }
}
