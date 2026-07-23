package com.ugviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.*
import com.ugviewer.ui.search.SearchScreen
import com.ugviewer.ui.viewer.TabViewerScreen

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
                }
            )
        },
        detailPane = {
            val content = navigator.currentDestination?.content
            if (content != null) {
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
