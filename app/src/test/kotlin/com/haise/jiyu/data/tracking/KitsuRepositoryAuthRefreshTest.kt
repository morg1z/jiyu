package com.haise.jiyu.data.tracking

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Refresh token se dřív ukládal, ale nikdy nepoužil - po vypršení access tokenu sync do Kitsu tiše
 * přestal fungovat. Klient tu je testovací interceptor (bez sítě): 401 na starý token, 200 na nový.
 */
class KitsuRepositoryAuthRefreshTest {

    private fun clientAnswering(seenAuth: MutableList<String?>) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val authHeader = request.header("Authorization")
            seenAuth += authHeader
            val code = if (authHeader == "Bearer old") 401 else 200
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body("""{"data":[{"id":"entry1"}]}""".toResponseBody("application/vnd.api+json".toMediaType()))
                .build()
        }
        .build()

    @Test
    fun `a 401 triggers one refresh and the request is retried with the new token`() = runBlocking {
        var token = "old"
        val authManager = mockk<KitsuAuthManager>()
        coEvery { authManager.getToken() } answers { token }
        coEvery { authManager.getUserId() } returns "u1"
        coEvery { authManager.refresh(any()) } answers { token = "new"; true }
        val seen = mutableListOf<String?>()

        val entryId = KitsuRepository(clientAnswering(seen), authManager).getLibraryEntryId("m1")

        assertEquals("entry1", entryId)
        assertEquals(listOf("Bearer old", "Bearer new"), seen)
        coVerify(exactly = 1) { authManager.refresh(any()) }
    }

    @Test
    fun `a failed refresh gives up without retrying the request`() = runBlocking {
        val authManager = mockk<KitsuAuthManager>()
        coEvery { authManager.getToken() } returns "old"
        coEvery { authManager.getUserId() } returns "u1"
        coEvery { authManager.refresh(any()) } returns false
        val seen = mutableListOf<String?>()

        val entryId = KitsuRepository(clientAnswering(seen), authManager).getLibraryEntryId("m1")

        assertNull(entryId)
        assertEquals(listOf<String?>("Bearer old"), seen)
    }

    @Test
    fun `a valid token is used as is and never refreshed`() = runBlocking {
        val authManager = mockk<KitsuAuthManager>()
        coEvery { authManager.getToken() } returns "fresh"
        coEvery { authManager.getUserId() } returns "u1"
        val seen = mutableListOf<String?>()

        val entryId = KitsuRepository(clientAnswering(seen), authManager).getLibraryEntryId("m1")

        assertEquals("entry1", entryId)
        coVerify(exactly = 0) { authManager.refresh(any()) }
    }
}
