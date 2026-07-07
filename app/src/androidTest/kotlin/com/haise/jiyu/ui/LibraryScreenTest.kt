package com.haise.jiyu.ui

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class LibraryScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun libraryScreen_isDisplayed() {
        composeRule.onNodeWithText("Knihovna").assertIsDisplayed()
    }

    @Test
    fun bottomNavigation_tabsExist() {
        composeRule.onNodeWithText("Procházet").assertExists()
        composeRule.onNodeWithText("Historie").assertExists()
        composeRule.onNodeWithText("Nastavení").assertExists()
    }

    @Test
    fun navigateToBrowse_works() {
        composeRule.onNodeWithText("Procházet").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Procházet").assertIsDisplayed()
    }

    @Test
    fun navigateToSettings_works() {
        composeRule.onNodeWithText("Nastavení").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Nastavení").assertIsDisplayed()
    }
}
