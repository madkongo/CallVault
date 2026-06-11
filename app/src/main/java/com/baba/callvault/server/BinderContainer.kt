/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

/**
 * CallVault Plan 5 — PRODUCTION binder subsystem.
 *
 * A [Parcelable] wrapping a live [IBinder] so it can ride inside a [android.os.Bundle] passed to a
 * [android.content.ContentProvider.call]. `writeStrongBinder`/`readStrongBinder` are the ONLY safe
 * way to move an IBinder across the provider boundary — a plain serialize would lose the binder.
 *
 * Ported verbatim from the proven spike (persistserver/BinderContainer.kt), itself ported from
 * Shizuku-API:
 *   RikkaApps/Shizuku-API — provider/src/main/java/moe/shizuku/api/BinderContainer.java
 *     public IBinder binder;
 *     writeToParcel(dest,flags){ dest.writeStrongBinder(this.binder); }
 *     BinderContainer(Parcel in){ this.binder = in.readStrongBinder(); }
 *     CREATOR{ createFromParcel(src)=new BinderContainer(src); newArray(n)=new BinderContainer[n]; }
 * Declared in AIDL as `parcelable BinderContainer;` (BinderContainer.aidl), exactly like Shizuku.
 *
 * @property binder the strong binder being transported; never persisted, only marshalled live.
 */
class BinderContainer : Parcelable {

    @JvmField
    val binder: IBinder?

    constructor(binder: IBinder?) {
        this.binder = binder
    }

    private constructor(source: Parcel) {
        // Mirrors Shizuku: read the strong binder back out on the receiving side.
        this.binder = source.readStrongBinder()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        // Mirrors Shizuku: writeStrongBinder is what keeps the IBinder usable across the boundary.
        dest.writeStrongBinder(binder)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<BinderContainer> = object : Parcelable.Creator<BinderContainer> {
            override fun createFromParcel(source: Parcel): BinderContainer = BinderContainer(source)
            override fun newArray(size: Int): Array<BinderContainer?> = arrayOfNulls(size)
        }
    }
}
