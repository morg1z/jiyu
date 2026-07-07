package com.haise.jiyu.ui

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.haise.jiyu.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ReaderSmokeTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun app_launches_without_crash() {
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test
    fun library_emptyState_isDisplayed() {
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Knihovna").assertExists()
    }
}
