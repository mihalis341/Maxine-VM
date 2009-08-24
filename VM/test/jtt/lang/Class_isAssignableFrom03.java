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
/*
 * @Harness: java
 * @Runs: 0 = false; 1 = true; 2 = false; 3 = true; 4 = false; 5 = true; 6 = true; 7 = false
 */
package jtt.lang;

public final class Class_isAssignableFrom03 implements Cloneable {
    private Class_isAssignableFrom03() {
    }

    public static boolean test(int i) {
        Class source = Object.class;
        if (i == 0) {
            source = int.class;
        }
        if (i == 1) {
            source = int[].class;
        }
        if (i == 2) {
            source = float.class;
        }
        if (i == 3) {
            source = Cloneable.class;
        }
        if (i == 4) {
            source = Runnable.class;
        }
        if (i == 5) {
            source = Class_isAssignableFrom03.class;
        }
        if (i == 6) {
            source = Object[].class;
        }
        return Cloneable.class.isAssignableFrom(source);
    }
}
