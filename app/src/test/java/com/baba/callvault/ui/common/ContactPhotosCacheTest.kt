/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import com.baba.callvault.data.recordings.ContactsChanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/** C149: the contact photo cache must not keep a failed answer, and must forget when contacts change. */
class ContactPhotosCacheTest {

    @Before
    fun clear() = ContactPhotos.clearForTest()

    @Test
    fun `a lookup that failed is not remembered as no contact`() {
        var calls = 0
        val first = ContactPhotos.resolve("+972500000000") { calls++; null }
        assertNull(first.contactUri)
        assertNull(ContactPhotos.peek("+972500000000"))

        val found = ContactPhotos.Info(null, null)
        val second = ContactPhotos.resolve("+972500000000") { calls++; found }
        assertSame(found, second)
        assertEquals(2, calls)
    }

    @Test
    fun `an answer is remembered until the address book changes`() {
        val answer = ContactPhotos.Info(null, null)
        var calls = 0
        ContactPhotos.resolve("111") { calls++; answer }
        ContactPhotos.resolve("111") { calls++; answer }
        assertEquals(1, calls)
        assertNotNull(ContactPhotos.peek("111"))

        ContactsChanges.bump()

        assertNull(ContactPhotos.peek("111"))
    }

    @Test
    fun `an answer read while the address book changed is used but not kept`() {
        ContactPhotos.resolve("222") { ContactsChanges.bump(); ContactPhotos.Info(null, null) }
        assertNull(ContactPhotos.peek("222"))
    }
}
