/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.persistserver;

/**
 * THROWAWAY de-risk spike (CallVault Plan 5, Task 0c) — the BINDER COMMAND CHANNEL.
 *
 * Implemented by the detached shell-uid daemon ([BinderDebugDaemon]) and called BY THE APP over a
 * raw binder (no ADB), even while Wireless debugging is OFF. Proves risks #3 (shell->app provider
 * delivers a usable binder) and #4 (app passes a ParcelFileDescriptor the shell-uid daemon writes).
 */
interface IPersistDebugService {
    /** Round-trip liveness check. Returns "pong from uid=<daemonUid> pid=<daemonPid> msg=<msg>". */
    String ping(String msg);

    /**
     * risk #4 test: the app opens a fd to a file IT can write (SAF/app-owned) and hands it over;
     * the shell-uid daemon — which lacks that grant — writes [text] bytes into the fd. Returns true
     * on success. The app then reads the file back to confirm the daemon really wrote it.
     */
    boolean writeToPfd(in ParcelFileDescriptor pfd, String text);

    /** Returns Process.myUid() of the daemon, so the app can confirm it is talking to shell uid 2000. */
    int myUid();
}
