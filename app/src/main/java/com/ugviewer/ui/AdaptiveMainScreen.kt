package com.ugviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.*
import com.ugviewer.ui.chord.ChordSearchScreen
import com.ugviewer.ui.search.SearchScreen
import com.ugviewer.ui.studio.LayoutStudioScreen
import com.ugviewer.ui.viewer.TabViewerScreen

/** Sentinel destination for the chord-shape screen (tab ids are always positive). */
private const val CHORD_SHAPES_DESTINATION = -1L

/** Sentinel destination for the Layout Studio (below the chord-shapes sentinel). */
private const val LAYOUT_STUDIO_DESTINATION = -2L

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AdaptiveMainScreen() {
    val navigator = rememberListDetailPaneScaffoldNavigator<Long>()

    BackHandler(navigator.canNavigateBack()) {
        navigator.navigateBack()
    }

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            SearchScreen(
                onTabSelected = { tabId ->
                    navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, tabId)
                },
                onChordShapesSelected = {
                    navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, CHORD_SHAPES_DESTINATION)
                },
                onDesignStudioSelected = {
                    navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, LAYOUT_STUDIO_DESTINATION)
                }
            )
        },
        detailPane = {
            val content = navigator.currentDestination?.content
            if (content == CHORD_SHAPES_DESTINATION) {
                ChordSearchScreen(
                    onBack = {
                        navigator.navigateBack()
                    }
                )
            } else if (content == LAYOUT_STUDIO_DESTINATION) {
                LayoutStudioScreen(
                    onBack = {
                        navigator.navigateBack()
                    }
                )
            } else if (content != null) {
                TabViewerScreen(
                    tabId = content,
                    onBack = {
                        navigator.navigateBack()
                    }
                )
            }
        }
    )
}
