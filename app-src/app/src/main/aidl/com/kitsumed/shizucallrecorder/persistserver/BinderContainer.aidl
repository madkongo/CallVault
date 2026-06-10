/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.persistserver;

// THROWAWAY de-risk spike (CallVault Plan 5, Task 0c).
// Declares our hand-written Parcelable so AIDL methods/bundles can reference it.
// Mirrors Shizuku-API: aidl/.../BinderContainer is declared `parcelable BinderContainer;`
// and the Parcelable is hand-written in moe/shizuku/api/BinderContainer.java.
parcelable BinderContainer;
