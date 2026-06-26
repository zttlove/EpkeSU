package me.weishu.kernelsu.ui.screen.superuser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.AppIconImage
import me.weishu.kernelsu.ui.component.delta.DeltaCard
import me.weishu.kernelsu.ui.component.delta.DeltaColors
import me.weishu.kernelsu.ui.component.delta.DeltaEmptyCard
import me.weishu.kernelsu.ui.component.delta.DeltaScreen
import me.weishu.kernelsu.ui.component.delta.DeltaSearchField
import me.weishu.kernelsu.ui.component.delta.DeltaSectionTitle
import me.weishu.kernelsu.ui.component.delta.DeltaShapes
import me.weishu.kernelsu.ui.component.delta.DeltaSwitch
import me.weishu.kernelsu.ui.component.delta.deltaSp

@Composable
fun SuperUserPagerDelta(
    uiState: SuperUserUiState,
    actions: SuperUserActions,
    bottomInnerPadding: Dp,
) {
    val searchText = uiState.searchStatus.searchText
    val apps = if (searchText.isBlank()) uiState.groupedApps else uiState.searchResults

    DeltaScreen(
        title = stringResource(R.string.superuser),
        icon = Icons.Rounded.Security,
        bottomInnerPadding = bottomInnerPadding,
    ) { contentPadding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp,
                top = 18.dp,
                end = 20.dp,
                bottom = contentPadding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                DeltaGrantModeSelector()
            }
            item {
                DeltaGrantActionBar()
            }
            item {
                DeltaSearchField(
                    searchText = searchText,
                    onSearchTextChange = actions.onSearchTextChange,
                    onClearSearch = actions.onClearSearch,
                )
            }
            item {
                DeltaSectionTitle(text = "Root Permissions")
            }
            if (apps.isEmpty()) {
                item {
                    DeltaEmptyCard(
                        text = if (uiState.hasLoaded) {
                            stringResource(R.string.superuser_empty)
                        } else {
                            stringResource(R.string.refresh_refresh)
                        },
                    )
                }
            } else {
                items(apps, key = { it.uid }) { group ->
                    DeltaGrantRow(
                        group = group,
                        onClick = { actions.onOpenProfile(group) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeltaGrantModeSelector() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(78.dp)
            .clip(DeltaShapes.Control)
            .background(DeltaColors.Surface)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(DeltaShapes.Control)
                .background(DeltaColors.AccentSoft)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.VisibilityOff,
                contentDescription = null,
                tint = DeltaColors.Ink,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(9.dp))
            Column {
                Text(
                    text = "黑名单",
                    color = DeltaColors.Ink,
                    fontSize = deltaSp(16f, maxScale = 1.0f),
                    lineHeight = deltaSp(19f, maxScale = 1.0f),
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = "(DenyList)",
                    color = DeltaColors.Ink,
                    fontSize = deltaSp(16f, maxScale = 1.0f),
                    lineHeight = deltaSp(19f, maxScale = 1.0f),
                    fontWeight = FontWeight.Black,
                )
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Security,
                contentDescription = null,
                tint = DeltaColors.Muted,
                modifier = Modifier.size(23.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "白名单 (SuList)",
                color = DeltaColors.Muted,
                fontSize = deltaSp(17f, maxScale = 1.0f),
                lineHeight = deltaSp(21f, maxScale = 1.0f),
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DeltaGrantActionBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(CircleShape)
            .background(DeltaColors.AccentSoft)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeltaActionSegment(
            icon = Icons.Rounded.Security,
            label = "Grant",
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(30.dp)
                .background(DeltaColors.Accent.copy(alpha = 0.28f)),
        )
        DeltaActionSegment(
            icon = Icons.Rounded.VisibilityOff,
            label = "Hide",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DeltaActionSegment(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxHeight(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = DeltaColors.Ink,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = DeltaColors.Ink,
            fontSize = deltaSp(18f, maxScale = 1.0f),
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DeltaGrantRow(
    group: GroupedApps,
    onClick: () -> Unit,
) {
    val title = group.ownerName ?: group.primary.label
    val summary = if (group.apps.size > 1) {
        stringResource(R.string.group_contains_apps, group.apps.size)
    } else {
        group.primary.packageName
    }
    DeltaCard(
        modifier = Modifier
            .height(94.dp)
            .clickable(onClick = onClick),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconImage(
                packageInfo = group.primary.packageInfo,
                label = group.primary.label,
                modifier = Modifier
                    .size(52.dp)
                    .clip(DeltaShapes.SmallPill),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp, end = 6.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    color = DeltaColors.Ink,
                    fontSize = deltaSp(18f, maxScale = 1.0f),
                    lineHeight = deltaSp(22f, maxScale = 1.0f),
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary,
                    color = DeltaColors.Muted,
                    fontSize = deltaSp(13.5f, maxScale = 1.0f),
                    lineHeight = deltaSp(17f, maxScale = 1.0f),
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DeltaSwitch(
                checked = group.anyAllowSu,
                onCheckedChange = { onClick() },
            )
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = null,
                tint = DeltaColors.Danger,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(28.dp),
            )
        }
    }
}
