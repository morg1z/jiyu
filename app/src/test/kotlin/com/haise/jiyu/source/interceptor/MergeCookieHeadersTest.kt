package com.haise.jiyu.source.interceptor

import org.junit.Assert.assertEquals
import org.junit.Test

class MergeCookieHeadersTest {

    @Test
    fun `no existing cookie header yields just the clearance cookies`() {
        assertEquals("cf_clearance=abc", mergeCookieHeaders(null, "cf_clearance=abc"))
    }

    @Test
    fun `existing session cookies are kept next to the clearance`() {
        assertEquals("session=1; cf_clearance=abc", mergeCookieHeaders("session=1", "cf_clearance=abc"))
    }

    @Test
    fun `a stale cookie with the same name is replaced by the clearance one`() {
        assertEquals("a=1; cf_clearance=new", mergeCookieHeaders("a=1; cf_clearance=old", "cf_clearance=new"))
    }
}
