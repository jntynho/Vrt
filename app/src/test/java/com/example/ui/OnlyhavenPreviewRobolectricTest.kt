package com.example.ui

import android.app.Application
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.network.OnlyhavenCreator
import com.example.network.OnlyhavenMediaItem
import com.example.ui.components.OnlyhavenPreviewOverlay
import com.example.ui.theme.GVJVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 100% Guaranteed Robolectric Verification Test for Onlyhaven Preview Save/Heart button.
 * Rigorously verifies click responsiveness, StateFlow reactivity, and carousel preservation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnlyhavenPreviewRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var viewModel: MainViewModel

    private val testCreator = OnlyhavenCreator(
        id = "998877",
        name = "test_creator",
        displayName = "Test Creator",
        service = "onlyfans",
        profileUrl = "https://cum.st/creators/onlyfans/998877",
        avatarUrl = "https://img.cum.st/avatar.webp",
        headerUrl = "https://img.cum.st/header.webp"
    )

    private val testAttachment1 = OnlyhavenMediaItem(
        id = "att_1",
        sha256 = "sha256_1",
        kind = "image",
        mimeType = "image/jpeg",
        originalFilename = "img1.jpg",
        width = 1080,
        height = 1920,
        durationMs = null,
        thumbnailUrl = "https://img.cum.st/thumb1.webp",
        originalUrl = "https://e1.cum.st/media/orig1.jpg",
        postCaption = "Awesome carousel post",
        postId = "post_123"
    )

    private val testAttachment2 = OnlyhavenMediaItem(
        id = "att_2",
        sha256 = "sha256_2",
        kind = "image",
        mimeType = "image/jpeg",
        originalFilename = "img2.jpg",
        width = 1080,
        height = 1920,
        durationMs = null,
        thumbnailUrl = "https://img.cum.st/thumb2.webp",
        originalUrl = "https://e1.cum.st/media/orig2.jpg",
        postCaption = "Awesome carousel post",
        postId = "post_123"
    )

    private val testPost = testAttachment1.copy(
        postAttachments = listOf(testAttachment1, testAttachment2)
    )

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        viewModel = MainViewModel(app)
    }

    @Test
    fun testPreviewSaveHeartButtonResponsivenessAndCarouselPreservation() {
        var dismissed = false

        composeTestRule.setContent {
            GVJVaultTheme {
                OnlyhavenPreviewOverlay(
                    parentPost = testPost,
                    creator = testCreator,
                    initialIndex = 0,
                    onDismiss = { dismissed = true },
                    viewModel = viewModel
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Initial State: Must be displayed and contentDescription should be "Save Post"
        val saveButton = composeTestRule.onNodeWithTag("preview_save_button")
        saveButton.assertIsDisplayed()
        saveButton.assertContentDescriptionEquals("Save Post")
        assertFalse(viewModel.isPostSaved(testPost, testCreator.service))

        // 2. Perform Click: Click the heart button
        saveButton.performClick()
        composeTestRule.waitForIdle()

        // 3. Verify it IMMEDIATELY responds and updates contentDescription to "Unsave Post"
        saveButton.assertContentDescriptionEquals("Unsave Post")
        assertTrue("Post must be marked as saved in ViewModel", viewModel.isPostSaved(testPost, testCreator.service))
        assertTrue("Saved posts list must contain 1 post", viewModel.savedOnlyhavenPosts.value.isNotEmpty())

        val savedPost = viewModel.savedOnlyhavenPosts.value.first()
        assertEquals("post_123", savedPost.mediaItem.postId)
        assertEquals(2, savedPost.mediaItem.postAttachments.size)
        assertTrue("Carousel must be preserved", savedPost.mediaItem.isMultiMedia)

        // 4. Perform Second Click: Toggle save off
        saveButton.performClick()
        composeTestRule.waitForIdle()

        // 5. Verify it IMMEDIATELY toggles back to "Save Post"
        saveButton.assertContentDescriptionEquals("Save Post")
        assertFalse("Post must no longer be saved", viewModel.isPostSaved(testPost, testCreator.service))
        assertTrue("Saved posts list must be empty", viewModel.savedOnlyhavenPosts.value.isEmpty())

        // 6. Test Back button
        val backButton = composeTestRule.onNodeWithTag("preview_back_button")
        backButton.assertIsDisplayed()
        backButton.performClick()
        composeTestRule.waitForIdle()
        assertTrue("onDismiss must be triggered", dismissed)
    }
}
