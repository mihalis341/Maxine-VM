/*
 * Copyright (c) 2017, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
 * Copyright (c) 2007, 2011, Oracle and/or its affiliates. All rights reserved.
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
 */
#include "os.h"
#include "jni.h"
#include "maxine.h"
#include <sys/types.h>

#if !os_WINDOWS
#include <sys/time.h> //MINGW may contain this file but it is not officially part of WINAPI so other SDKs (eg. Visual Studio do not include it)
#endif
#if os_DARWIN
#include <mach/mach_time.h>
#include <mach/kern_return.h>
#elif os_LINUX
#include <dlfcn.h>
#elif os_WINDOWS
#include <windows.h>
#include <stdint.h>
int gettimeofday(struct timeval * tp)
{
    // Note: some broken versions only have 8 trailing zero's, the correct epoch has 9 trailing zero's
    // This magic number is the number of 100 nanosecond intervals since January 1, 1601 (UTC)
    // until 00:00:00 January 1, 1970 
    static const uint64_t EPOCH = ((uint64_t) 116444736000000000ULL);

    SYSTEMTIME  system_time;
    FILETIME    file_time;
    uint64_t    time;

    GetSystemTime( &system_time );
    SystemTimeToFileTime( &system_time, &file_time );
    time =  ((uint64_t)file_time.dwLowDateTime )      ;
    time += ((uint64_t)file_time.dwHighDateTime) << 32;

    tp->tv_sec  = (long) ((time - EPOCH) / 10000000L);
    tp->tv_usec = (long) (system_time.wMilliseconds * 1000);
    return 0;
}

int clock_gettime(int x, struct timespec *spec)     
{  __int64 wintime; GetSystemTimeAsFileTime((FILETIME*)&wintime);
   wintime      -=(int64_t)116444736000000000;  //1jan1601 to 1jan1970
   spec->tv_sec  =wintime / (int64_t)10000000;           //seconds
   spec->tv_nsec =wintime % (int64_t)10000000 *100;      //nano-seconds
   return 0;
}
#endif


jlong native_nanoTime(void) {
#if os_SOLARIS
	return gethrtime();
#elif os_DARWIN
	struct mach_timebase_info temp;
	static struct mach_timebase_info timebase = { 0, 0 };
	static double factor = 0.0;
	static int failed = 0;

	/* get the factors the first time */
	if (timebase.denom == 0) {
		if (!failed) {
			if (mach_timebase_info(&temp) != KERN_SUCCESS) {
				factor = (double)temp.numer / (double)temp.denom;
				timebase = temp;
			} else {
				timebase.denom = timebase.numer = 0;
				failed = 1;
			}
		}
	}

	/* special case: absolute time is in nanoseconds */
	if (timebase.denom == 1 && timebase.numer == 1) {
		return mach_absolute_time();
	}

	/* general case: multiply by factor to get nanoseconds. */
	if (factor != 0.0) {
		return mach_absolute_time() * factor;
	}

	/* worst case: fallback to gettimeofday(). */
	struct timeval tv;
	gettimeofday(&tv, NULL);
	return (uint64_t)tv.tv_sec * (uint64_t)(1000 * 1000 * 1000) + (uint64_t)(tv.tv_usec * 1000);
#elif os_LINUX 

#ifndef CLOCK_MONOTONIC
#define CLOCK_MONOTONIC (1)
#endif

    static int (*clock_gettime_func)(clockid_t, struct timespec*) = NULL;
    static int initialized = 0;

    if (!initialized) {
        initialized = 1;

        /* This code is adopted from the HotSpot handling of System.nanoTime for Linux */
        /* we do dlopen's in this particular order due to bug in linux
           dynamical loader (see 6348968) leading to crash on exit */
        void* handle = dlopen("librt.so.1", RTLD_LAZY);
        if (handle == NULL) {
            handle = dlopen("librt.so", RTLD_LAZY);
        }

        if (handle) {
            clock_gettime_func = (int(*)(clockid_t, struct timespec*))dlsym(handle, "clock_gettime");
            if (clock_gettime_func) {
                struct timespec tp;
                if (clock_gettime_func(CLOCK_MONOTONIC, &tp) != 0) {
                    /* monotonic clock is not supported */
                    clock_gettime_func = NULL;
                    dlclose(handle);
                }
            }
        }
	}

    if (clock_gettime_func != NULL) {
        struct timespec tp;
        clock_gettime_func(CLOCK_MONOTONIC, &tp);
#ifdef arm
	return ((long long) tp.tv_sec) * (1000 * 1000 * 1000) + (long long) tp.tv_nsec;
#else
    return ((jlong)tp.tv_sec) * (1000 * 1000 * 1000) + (jlong) tp.tv_nsec;
#endif

    }

    /* worst case: fallback to gettimeofday(). */
    struct timeval time;
    int status = gettimeofday(&time, NULL);
    c_ASSERT(status != -1);
    jlong usecs = ((jlong) time.tv_sec) * (1000 * 1000) + (jlong) time.tv_usec;
    return 1000 * usecs;
#elif os_WINDOWS
    struct timespec time;
	clock_gettime(1, &time);
	return time.tv_nsec; //NOT 100% TESTED
#else
	return 1;
#endif
}

jlong native_currentTimeMillis(void) {
#if os_SOLARIS || os_DARWIN || os_LINUX || os_WINDOWS
	struct timeval tv;
	#if os_WINDOWS
	gettimeofday(&tv);
	#else
	gettimeofday(&tv, NULL);
	#endif
	// we need to cast to jlong to avoid overflows in ARMv7
	return ((jlong) tv.tv_sec * 1000) + ((jlong) tv.tv_usec / 1000);
#else
	return 1;
#endif
}
