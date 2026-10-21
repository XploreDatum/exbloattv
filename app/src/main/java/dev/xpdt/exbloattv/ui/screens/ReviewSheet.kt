package dev.xpdt.exbloattv.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.xpdt.exbloattv.R
import dev.xpdt.exbloattv.ui.components.PrimaryButton
import dev.xpdt.exbloattv.ui.components.SegmentedGroup

/** The one-sheet summary before cleaning: plain counts from the planner, the delete switch, one button. */
@Composable
fun ReviewSheet(
    review: ReviewCounts,
    deleteUserApps: Boolean,
    onDeleteUserApps: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.review_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
            val lines = buildList {
                // The new home screen also switches off the old one; say so, so the count on "All clean" adds up.
                when {
                    review.switchOff > 0 && review.newHomeScreen ->
                        add(pluralStringResource(R.plurals.review_switch_off_plus_home, review.switchOff, review.switchOff))
                    review.switchOff > 0 -> add(pluralStringResource(R.plurals.review_switch_off, review.switchOff, review.switchOff))
                    review.newHomeScreen -> add(stringResource(R.string.review_old_home_off))
                }
                if (review.remove > 0) {
                    add(
                        pluralStringResource(
                            R.plurals.review_remove, review.remove, review.remove,
                            review.removeNames.joinToString(stringResource(R.string.list_separator)),
                        ),
                    )
                }
                if (review.switchOn > 0) add(pluralStringResource(R.plurals.review_switch_on, review.switchOn, review.switchOn))
                if (review.newHomeScreen) add(stringResource(R.string.review_home))
                if (review.restarts) add(stringResource(R.string.review_restart))
                if (review.nothingToDo) add(stringResource(R.string.review_nothing)) else add(stringResource(R.string.review_leave_remote))
            }
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 3.dp)) }
            VSpace(14)
            SegmentedGroup(
                listOf {
                    SettingRow(
                        stringResource(R.string.review_delete_switch),
                        stringResource(R.string.review_delete_desc),
                        deleteUserApps,
                        onCheckedChange = onDeleteUserApps,
                    )
                },
            )
            VSpace(16)
            PrimaryButton(stringResource(R.string.review_go), onClick = onStart, enabled = !review.nothingToDo)
        }
    }
}
