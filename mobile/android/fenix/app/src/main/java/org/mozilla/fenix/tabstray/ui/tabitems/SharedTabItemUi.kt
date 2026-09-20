/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.tabstray.ui.tabitems

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Indication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import mozilla.components.compose.base.RadioCheckmark
import mozilla.components.compose.base.RadioCheckmarkColors
import mozilla.components.compose.base.button.IconButton
import mozilla.components.compose.base.menu.DropdownMenu
import mozilla.components.compose.base.menu.MenuItem
import mozilla.components.compose.base.modifier.thenConditional
import mozilla.components.compose.base.text.Text
import mozilla.components.compose.base.theme.AcornCorners
import mozilla.components.compose.base.theme.layout.AcornLayout
import mozilla.components.support.utils.ext.isLandscape
import mozilla.components.ui.colors.NovaColors
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.tabstray.LocalTabManagementFeatureHelper
import org.mozilla.fenix.tabstray.TabsTrayTestTag
import org.mozilla.fenix.tabstray.data.TabsTrayItem
import org.mozilla.fenix.theme.FirefoxTheme

// Rounded corner shape used by all tab items
val tabContentCardShape: CornerBasedShape
    @Composable get() = MaterialTheme.shapes.large

// The corner radius of a tab card's top outer edge
val tabCardTopCornerRadius: CornerSize
    @Composable get() = MaterialTheme.shapes.extraSmall.topStart

// The corner radius of a tab card's bottom outer edge
val tabCardBottomCornerRadius: CornerSize
    @Composable get() = MaterialTheme.shapes.medium.bottomStart

// Rounded shape used for tab thumbnails
val thumbnailShape: Shape
    @Composable
    get() =
        RoundedCornerShape(
            topStart = tabCardTopCornerRadius,
            topEnd = tabCardTopCornerRadius,
            bottomStart = tabCardBottomCornerRadius,
            bottomEnd = tabCardBottomCornerRadius,
        )

// The touch target size of a tab's header icon
val TabHeaderIconTouchTargetSize = 40.dp

val TabListFirstItemShape: Shape
    @Composable
    get() =
        MaterialTheme.shapes.medium.copy(
            bottomStart = CornerSize(0.dp),
            bottomEnd = CornerSize(0.dp),
        )

val TabListLastItemShape: Shape
    @Composable
    get() =
        MaterialTheme.shapes.medium.copy(
            topStart = CornerSize(0.dp),
            topEnd = CornerSize(0.dp),
        )

val TabListSingleItemShape: Shape
    @Composable get() = MaterialTheme.shapes.medium

val TabListBorderMiddleItemShape: Shape
    @Composable get() = RectangleShape

/** Border drawn around a tab list item's thumbnail. */
val tablistItemThumbnailBorder: BorderStroke
    @Composable
    @ReadOnlyComposable
    get() =
        BorderStroke(
            width = AcornLayout.AcornBorder.default,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        )

/**
 * Shape information for a tab item displayed in a list.
 *
 * @property borderShape: The [Shape] representing the item's border.
 * @property clipTabToFit Whether the item content should be clipped to [borderShape].
 */
data class TabListShapeInfo(
    val borderShape: Shape,
    val clipTabToFit: Boolean,
)

@Composable
internal fun BoxScope.MediaPlaybackIndicator(isMediaActive: Boolean) {
    if (isMediaActive) {
        val scrimColor = NovaColors.Gray75
        val height = FirefoxTheme.layout.size.static800
        val bottomToTopGradient = Brush.verticalGradient(colors = listOf(scrimColor.copy(alpha = 0f), scrimColor))

        Spacer(
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(height)
                    .background(
                        brush = bottomToTopGradient,
                        shape =
                            MaterialTheme.shapes.extraSmall.copy(
                                topStart = CornerSize(0.dp),
                                topEnd = CornerSize(0.dp),
                            ),
                    )
        )

        Card(
            modifier =
                Modifier.align(Alignment.BottomEnd)
                    .padding(FirefoxTheme.layout.space.static100)
                    .testTag(TabsTrayTestTag.TAB_ITEM_MEDIA_INDICATOR),
            shape = RoundedCornerShape(AcornCorners.full),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Icon(
                painter = painterResource(id = iconsR.drawable.mozac_ic_audio_24),
                contentDescription = stringResource(id = R.string.tab_indicator_media_playing),
                modifier = Modifier.padding(FirefoxTheme.layout.space.static50),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * @param isSelected: Whether the tab is selected in multiselect mode
 * @param uncheckedBorderColor: The border color to display when the item is unchecked
 */
@Composable
fun MultiSelectTabButton(
    isSelected: Boolean,
    uncheckedBorderColor: Color = RadioCheckmarkColors.default().borderColor,
) {
    Box(
        modifier = Modifier.size(TabHeaderIconTouchTargetSize),
        contentAlignment = Alignment.Center,
    ) {
        RadioCheckmark(
            isSelected = isSelected,
            colors = RadioCheckmarkColors.default(borderColor = uncheckedBorderColor),
        )
    }
}

/**
 * The clickable modifier for tab items.
 *
 * @param clickHandler: ClickHandler object that responds to click events
 * @param clickedItem: The generic TabTray item that is being interacted with
 */
@Composable
fun Modifier.tabItemClickable(
    clickHandler: TabsTrayItemClickHandler,
    clickedItem: TabsTrayItem,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }

    if (clickHandler.onLongClick == null) {
        Modifier.clickable(
            enabled = clickHandler.enabled,
            interactionSource = interactionSource,
            indication = clickRipple,
            onClick = { clickHandler.onClick(clickedItem) },
        )
    } else {
        Modifier.combinedClickable(
            enabled = clickHandler.enabled,
            interactionSource = interactionSource,
            indication = clickRipple,
            onLongClick = { clickHandler.onLongClick(clickedItem) },
            onClick = { clickHandler.onClick(clickedItem) },
        )
    }
}

private val clickRipple: Indication
    @Composable
    get() =
        ripple(
            color =
                when (isSystemInDarkTheme()) {
                    true -> NovaColors.White
                    false -> NovaColors.Black
                }
        )

/**
 * The width to height ratio of the tab grid item. In landscape mode, the width to height ratio is 1:1 and in portrait
 * mode, the width to height ratio is 4:5.
 */
val gridItemAspectRatio: Float
    @Composable
    @ReadOnlyComposable
    get() =
        if (LocalContext.current.isLandscape()) {
            1f
        } else {
            0.8f
        }

/**
 * Renders the three dot button and its menu items for [TabsTrayItem.TabGroup] views.
 *
 * @param modifier: The Modifier parameter
 * @param includeCloseOption: Whether to include the "Close" dropdown item in the menu item list.
 * @param includeUngroupOption: Whether this surface wants the "Ungroup" dropdown item. The item is only shown when
 *   [TabManagementFeatureHelper.ungroupTabGroupEnabled] is also true.
 * @param locked Whether the tab group is currently locked. When locked, the close, share, ungroup, and delete
 *   options are hidden, since a locked group cannot be closed, ungrouped, or deleted until unlocked. Sharing is
 *   also hidden while locked, on the assumption that a locked group is more likely to hold sensitive tabs the
 *   user does not intend to share; this can be revisited if that assumption doesn't hold in practice.
 * @param onDeleteTabGroupClick Invoked when the user clicks on delete tab group.
 * @param onEditTabGroupClick Invoked when the user clicks to edit the selected tab group.
 * @param onCloseTabGroupClick Invoked when the user clicks to close the tab group.
 * @param onShareTabGroupClick Invoked when the user clicks to share the tab group.
 * @param onUngroupTabGroupClick Invoked when the user clicks to ungroup the tab group.
 * @param onToggleLockTabGroupClick Invoked when the user clicks to lock or unlock the tab group.
 */
@Composable
fun TabGroupMenuButton(
    modifier: Modifier = Modifier,
    includeCloseOption: Boolean = false,
    includeUngroupOption: Boolean = false,
    locked: Boolean = false,
    onDeleteTabGroupClick: () -> Unit,
    onEditTabGroupClick: () -> Unit,
    onCloseTabGroupClick: () -> Unit,
    onShareTabGroupClick: () -> Unit,
    onUngroupTabGroupClick: () -> Unit,
    onToggleLockTabGroupClick: () -> Unit,
) {
    var showDropdownMenu by remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            showDropdownMenu = true
        },
        contentDescription = stringResource(R.string.tab_group_three_dot_button_content_description),
        modifier = modifier.testTag(TabsTrayTestTag.TAB_GROUP_THREE_DOT_BUTTON),
        colors = IconButtonDefaults.iconButtonColors(contentColor = LocalContentColor.current),
    ) {
        Icon(
            painter = painterResource(id = iconsR.drawable.mozac_ic_ellipsis_vertical_24),
            contentDescription = null,
        )

        DropdownMenu(
            expanded = showDropdownMenu,
            onDismissRequest = { showDropdownMenu = false },
            menuItems =
                generateTabGroupMenuItems(
                    includeCloseOption = includeCloseOption,
                    editTabGroup = onEditTabGroupClick,
                    closeTabGroup = onCloseTabGroupClick,
                    shareTabGroup = onShareTabGroupClick,
                    deleteTabGroup = onDeleteTabGroupClick,
                    includeUngroupOption =
                        includeUngroupOption && LocalTabManagementFeatureHelper.current.ungroupTabGroupEnabled,
                    ungroupTabGroup = onUngroupTabGroupClick,
                    locked = locked,
                    toggleLockTabGroup = onToggleLockTabGroupClick,
                ),
        )
    }
}

/**
 * The trailing dismiss/close button in the list presentation of tab tray items.
 *
 * @param contentDescription Accessibility label describing the dismiss action.
 * @param modifier The [Modifier] applied to the button.
 * @param locked Whether the tab is locked. When true, the icon shows a lock instead of a close cross and the click
 *   is not forwarded to [onClick].
 * @param onClick Invoked when the dismiss button is clicked. Not invoked while [locked] is true.
 */
@Composable
fun ListItemDismissButton(
    contentDescription: String,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = {
            if (!locked) {
                onClick()
            }
        },
        contentDescription = contentDescription,
        modifier = modifier.size(48.dp),
    ) {
        Icon(
            painter =
                painterResource(
                    id = if (locked) iconsR.drawable.mozac_ic_lock_24 else iconsR.drawable.mozac_ic_cross_24
                ),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
        )
    }
}

/**
 * A small tappable lock icon shown next to a locked tab, allowing the user to unlock it directly from the tab
 * card. Only rendered while the tab is locked; there is no equivalent icon shown on an unlocked tab, since locking
 * is expected to be an occasional action and a permanently visible icon on every tab card would add clutter to the
 * common case. To lock an unlocked tab, the user selects the tab and locks it from the multiselect menu, or
 * long-presses and locks it from there.
 *
 * @param tab The locked [TabsTrayItem.Tab] this icon toggles the lock state of.
 * @param onToggleLockClick Invoked when the user taps the icon. Since this icon is only shown while locked, this
 *   always represents an unlock action.
 * @param modifier The [Modifier] applied to the button. Defaults to [TabHeaderIconTouchTargetSize]; callers with
 *   less available space, such as the tab grid header, can supply a smaller explicit size.
 */
@Composable
fun LockToggleIcon(
    tab: TabsTrayItem.Tab,
    onToggleLockClick: (TabsTrayItem.Tab) -> Unit,
    modifier: Modifier = Modifier.size(TabHeaderIconTouchTargetSize),
) {
    IconButton(
        onClick = { onToggleLockClick(tab) },
        contentDescription = stringResource(id = R.string.locked_tab_unlock_button_content_description, tab.title),
        modifier = modifier.testTag(TabsTrayTestTag.TAB_ITEM_TOGGLE_LOCK),
    ) {
        Icon(
            painter = painterResource(id = iconsR.drawable.mozac_ic_lock_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun generateTabGroupMenuItems(
    includeCloseOption: Boolean = false,
    includeUngroupOption: Boolean = false,
    locked: Boolean = false,
    editTabGroup: () -> Unit,
    closeTabGroup: () -> Unit,
    shareTabGroup: () -> Unit,
    deleteTabGroup: () -> Unit,
    ungroupTabGroup: () -> Unit,
    toggleLockTabGroup: () -> Unit,
): List<MenuItem> {
    val editItem =
        MenuItem.IconItem(
            text = Text.Resource(R.string.tab_group_three_dot_menu_edit),
            drawableRes = iconsR.drawable.mozac_ic_edit_24,
            testTag = TabsTrayTestTag.EDIT_TAB_GROUP,
            onClick = editTabGroup,
        )
    val closeItem =
        MenuItem.IconItem(
            text = Text.Resource(R.string.tab_group_three_dot_menu_close),
            drawableRes = iconsR.drawable.mozac_ic_tab_group_close_24,
            testTag = TabsTrayTestTag.CLOSE_TAB_GROUP,
            onClick = closeTabGroup,
        )
    val ungroupItem =
        MenuItem.IconItem(
            text = Text.Resource(R.string.tab_group_three_dot_menu_ungroup),
            drawableRes = iconsR.drawable.mozac_ic_tab_ungroup_24,
            testTag = TabsTrayTestTag.UNGROUP_TAB_GROUP,
            onClick = ungroupTabGroup,
        )
    val shareItem =
        MenuItem.IconItem(
            text = Text.Resource(R.string.tab_group_three_dot_menu_share),
            drawableRes = iconsR.drawable.mozac_ic_share_android_24,
            testTag = TabsTrayTestTag.SHARE_TAB_GROUP,
            onClick = shareTabGroup,
        )
    val deleteItem =
        MenuItem.IconItem(
            text = Text.Resource(R.string.tab_group_three_dot_menu_delete),
            drawableRes = iconsR.drawable.mozac_ic_delete_24,
            testTag = TabsTrayTestTag.DELETE_TAB_GROUP,
            onClick = deleteTabGroup,
            level = MenuItem.FixedItem.Level.Critical,
        )
    val lockItem =
        MenuItem.IconItem(
            text =
                Text.Resource(
                    if (locked) {
                        R.string.tab_group_three_dot_menu_unlock
                    } else {
                        R.string.tab_group_three_dot_menu_lock
                    }
                ),
            drawableRes =
                if (locked) {
                    iconsR.drawable.mozac_ic_lock_slash_multicolor_24
                } else {
                    iconsR.drawable.mozac_ic_lock_24
                },
            testTag = TabsTrayTestTag.TOGGLE_LOCK_TAB_GROUP,
            onClick = toggleLockTabGroup,
        )
    return buildList {
        add(editItem)
        // A locked group cannot be closed, ungrouped, deleted, or shared until it is unlocked, so those options
        // are hidden entirely rather than shown disabled. The lock/unlock toggle itself is always available.
        if (!locked) {
            if (includeCloseOption) {
                add(closeItem)
            }
            add(shareItem)
            if (includeUngroupOption) {
                add(ungroupItem)
            }
        }
        add(lockItem)
        if (!locked) {
            add(deleteItem)
        }
    }
}

// Long text string for verifying that tab items handle long titles with appropriate truncation.
const val LOREM_IPSUM =
    "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do " +
        "eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis " +
        "nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute " +
        "irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla " +
        "pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia " +
        "deserunt mollit anim id est laborum."

/**
 * Renders a border around a [TabsTrayItem] to signify that it is in focus. When the tab is not in focus, its
 * BorderStroke will be null.
 */
@Composable
@ReadOnlyComposable
fun tabItemConditionalBorder(selectionState: TabsTrayItemSelectionState): BorderStroke? {
    return if (selectionState.isFocused && selectionState.focusEnabled) {
        tabItemBorderFocused()
    } else {
        null
    }
}

/** Renders a border around a [TabsTrayItem] to signify that it is in focus. */
@Composable
@ReadOnlyComposable
fun tabItemBorderFocused(): BorderStroke {
    return BorderStroke(width = FirefoxTheme.layout.border.heaviest, brush = FirefoxTheme.gradients.tabOutline.brush)
}

/**
 * Applies tab list item styling, provided the shape information.
 *
 * @param tabShapeInfo The list item shape and clipping behavior.
 * @param selectionState the selection state of the item in the tabstray.
 */
@Composable
fun Modifier.tabListItemShapeStyling(
    tabShapeInfo: TabListShapeInfo,
    selectionState: TabsTrayItemSelectionState,
): Modifier {
    return this.thenConditional(
            Modifier.clip(tabShapeInfo.borderShape),
            { tabShapeInfo.clipTabToFit },
        )
        .thenConditional(
            modifier =
                Modifier.border(
                    border = tabItemBorderFocused(),
                    shape = tabShapeInfo.borderShape,
                ),
            { (selectionState.isFocused && selectionState.focusEnabled) },
        )
}

/** Returns the container color used by tab grid items. */
@Composable
fun tabGridItemContainerColor(selectionState: TabsTrayItemSelectionState): Color {
    return if (selectionState.isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceBright
    }
}
