package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.utils.settings.SharedSettings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.InstantCameraViewBase;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.utils.recorder.round.RoundVideoSession;

/** Configures the Camera2 round-video implementation. */
public class RoundVideoSettingsActivity extends BaseFragment {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK = 1;
    private static final int TYPE_VALUE = 2;
    private static final int TYPE_INFO = 3;

    private static final int ROW_GENERAL_HEADER = 0;
    private static final int ROW_ENABLED = 1;
    private static final int ROW_OUTPUT_RESOLUTION = 2;
    private static final int ROW_CAMERA_RESOLUTION = 3;
    private static final int ROW_FRAME_RATE = 4;
    private static final int ROW_MOTION_PRIORITY = 5;
    private static final int ROW_STABILIZATION = 6;
    private static final int ROW_LOW_LIGHT = 7;
    private static final int ROW_GENERAL_INFO = 8;
    private static final int ROW_DIAGNOSTICS_HEADER = 9;
    private static final int ROW_DIAGNOSTICS = 10;
    private static final int ROW_RESET = 11;
    private static final int ROW_DIAGNOSTICS_INFO = 12;
    private static final int ROW_COMPOSITION_HEADER = 13;
    private static final int ROW_COMPOSITION = 14;
    private static final int ROW_COMPOSITION_INFO = 15;
    private static final int ROW_COUNT = BuildConfig.DEBUG_PRIVATE_VERSION ? 16 : 13;

    private RecyclerListView listView;
    private ListAdapter adapter;

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        simpleActionBar.setTitle(LocaleController.getString(R.string.RoundVideoSettings));
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> onRowClicked(position));
        frameLayout.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT
        ));
        return fragmentView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Nullable
    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    private void onRowClicked(int position) {
        if (position == ROW_ENABLED) {
            SharedSettings.roundVideoCamera2Enabled.set(!InstantCameraViewBase.isCamera2Enabled(currentAccount));
            adapter.notifyDataSetChanged();
            return;
        }
        if (position == ROW_DIAGNOSTICS) {
            presentFragment(new RoundVideoDiagnosticsActivity());
            return;
        }
        if (position == ROW_RESET) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle(LocaleController.getString(R.string.RoundVideoResetDefaults));
            builder.setMessage(LocaleController.getString(R.string.RoundVideoResetDefaultsConfirm));
            builder.setPositiveButton(LocaleController.getString(R.string.RoundVideoResetDefaultsAction), (dialog, which) -> {
                SharedSettings.resetRoundVideoSettings();
                adapter.notifyDataSetChanged();
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            AlertDialog dialog = builder.create();
            showDialog(dialog);
            View button = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
            if (button instanceof android.widget.TextView) {
                ((android.widget.TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
            }
            return;
        }
        if (!InstantCameraViewBase.isCamera2Enabled(currentAccount)) {
            return;
        }
        if (position == ROW_OUTPUT_RESOLUTION) {
            showChoice(
                    R.string.RoundVideoOutputResolution,
                    new CharSequence[]{"480p", "360p"},
                    index -> SharedSettings.roundVideoOutputResolution.set(index == 0
                            ? RoundVideoSession.OutputResolution.P480
                            : RoundVideoSession.OutputResolution.P360)
            );
        } else if (position == ROW_CAMERA_RESOLUTION) {
            showChoice(
                    R.string.RoundVideoCameraResolution,
                    new CharSequence[]{
                            LocaleController.getString(R.string.RoundVideoCameraResolutionHigh),
                            LocaleController.getString(R.string.RoundVideoCameraResolutionMedium),
                            LocaleController.getString(R.string.RoundVideoCameraResolutionLow)
                    },
                    index -> SharedSettings.roundVideoCameraResolution.set(
                            RoundVideoSession.CameraResolution.values()[index]
                    )
            );
        } else if (position == ROW_FRAME_RATE) {
            showChoice(
                    R.string.RoundVideoFrameRate,
                    new CharSequence[]{"30 FPS", "60 FPS"},
                    index -> SharedSettings.roundVideoFrameRate.set(index == 0
                            ? RoundVideoSession.FrameRate.FPS_30
                            : RoundVideoSession.FrameRate.FPS_60)
            );
        } else if (position == ROW_MOTION_PRIORITY) {
            showChoice(
                    R.string.RoundVideoMotionPriority,
                    new CharSequence[]{
                            LocaleController.getString(R.string.RoundVideoMotionPriorityOff),
                            LocaleController.getString(R.string.RoundVideoMotionPriorityBalanced),
                            LocaleController.getString(R.string.RoundVideoMotionPriorityStrong)
                    },
                    index -> SharedSettings.roundVideoMotionPriorityLevel.set(
                            RoundVideoSession.MotionPriority.values()[index]
                    )
            );
        } else if (position == ROW_LOW_LIGHT) {
            SharedSettings.roundVideoLowLightBoost.set(!SharedSettings.roundVideoLowLightBoost.get());
            adapter.notifyItemChanged(position);
        } else if (position == ROW_STABILIZATION) {
            SharedSettings.roundVideoStabilization.set(!SharedSettings.roundVideoStabilization.get());
            adapter.notifyItemChanged(position);
        } else if (position == ROW_COMPOSITION) {
            SharedSettings.roundVideoComposition.set(!SharedSettings.roundVideoComposition.get());
            adapter.notifyItemChanged(position);
        }
    }

    private void showChoice(int titleResId, CharSequence[] choices, ChoiceHandler handler) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(titleResId));
        builder.setItems(choices, (dialog, index) -> {
            handler.onChoice(index);
            adapter.notifyDataSetChanged();
        });
        showDialog(builder.create());
    }

    private interface ChoiceHandler {
        void onChoice(int index);
    }

    private static String motionPriorityLabel(RoundVideoSession.MotionPriority value) {
        if (value == RoundVideoSession.MotionPriority.STRONG) {
            return LocaleController.getString(R.string.RoundVideoMotionPriorityStrong);
        } else if (value == RoundVideoSession.MotionPriority.BALANCED) {
            return LocaleController.getString(R.string.RoundVideoMotionPriorityBalanced);
        }
        return LocaleController.getString(R.string.RoundVideoMotionPriorityOff);
    }

    private static String cameraResolutionLabel(RoundVideoSession.CameraResolution resolution) {
        if (resolution == RoundVideoSession.CameraResolution.HIGH) {
            return LocaleController.getString(R.string.RoundVideoCameraResolutionHigh);
        } else if (resolution == RoundVideoSession.CameraResolution.MEDIUM) {
            return LocaleController.getString(R.string.RoundVideoCameraResolutionMedium);
        }
        return LocaleController.getString(R.string.RoundVideoCameraResolutionLow);
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        private ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return ROW_COUNT;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            if (position == ROW_ENABLED || position == ROW_DIAGNOSTICS || position == ROW_RESET) {
                return true;
            }
            if (!InstantCameraViewBase.isCamera2Enabled(currentAccount)) {
                return false;
            }
            return position == ROW_OUTPUT_RESOLUTION
                    || position == ROW_CAMERA_RESOLUTION
                    || position == ROW_FRAME_RATE
                    || position == ROW_MOTION_PRIORITY
                    || position == ROW_STABILIZATION
                    || position == ROW_LOW_LIGHT
                    || position == ROW_COMPOSITION;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_HEADER) {
                view = new HeaderCell(context);
            } else if (viewType == TYPE_CHECK) {
                view = new TextCheckCell(context);
            } else if (viewType == TYPE_VALUE) {
                TextSettingsCell cell = new TextSettingsCell(context);
                cell.setCanDisable(true);
                view = cell;
            } else {
                view = new TextInfoPrivacyCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
            ));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            boolean enabled = InstantCameraViewBase.isCamera2Enabled(currentAccount);
            if (holder.getItemViewType() == TYPE_HEADER) {
                HeaderCell cell = (HeaderCell) holder.itemView;
                cell.setText(position == ROW_GENERAL_HEADER
                        ? LocaleController.getString(R.string.RoundVideoGeneral)
                        : position == ROW_DIAGNOSTICS_HEADER
                        ? LocaleController.getString(R.string.RoundVideoDiagnosticsHeader)
                        : LocaleController.getString(R.string.RoundVideoComposition));
            } else if (holder.getItemViewType() == TYPE_CHECK) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                cell.setEnabled(position == ROW_ENABLED || enabled);
                if (position == ROW_ENABLED) {
                    cell.setTextAndCheck(
                            LocaleController.getString(R.string.RoundVideoUseNewRecorder),
                            enabled,
                            false
                    );
                } else if (position == ROW_LOW_LIGHT) {
                    cell.setTextAndValueAndCheck(
                            LocaleController.getString(R.string.RoundVideoLowLightBoost),
                            LocaleController.getString(R.string.RoundVideoLowLightBoostInfo),
                            SharedSettings.roundVideoLowLightBoost.get(),
                            false,
                            false
                    );
                } else if (position == ROW_STABILIZATION) {
                    cell.setTextAndValueAndCheck(
                            LocaleController.getString(R.string.RoundVideoStabilization),
                            LocaleController.getString(R.string.RoundVideoStabilizationInfo),
                            SharedSettings.roundVideoStabilization.get(),
                            false,
                            true
                    );
                } else {
                    cell.setTextAndCheck(
                            LocaleController.getString(R.string.RoundVideoCompositionEnabled),
                            SharedSettings.roundVideoComposition.get(),
                            false
                    );
                }
            } else if (holder.getItemViewType() == TYPE_VALUE) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setEnabled(enabled || position == ROW_DIAGNOSTICS || position == ROW_RESET);
                cell.setTextColor(Theme.getColor(position == ROW_RESET
                        ? Theme.key_text_RedRegular
                        : Theme.key_windowBackgroundWhiteBlackText));
                if (position == ROW_DIAGNOSTICS) {
                    cell.setText(LocaleController.getString(R.string.RoundVideoDiagnosticsRun), true);
                } else if (position == ROW_RESET) {
                    cell.setText(LocaleController.getString(R.string.RoundVideoResetDefaults), false);
                } else if (position == ROW_MOTION_PRIORITY) {
                    cell.setTextAndValue(
                            LocaleController.getString(R.string.RoundVideoMotionPriority),
                            motionPriorityLabel(SharedSettings.roundVideoMotionPriorityLevel.get()),
                            true
                    );
                } else if (position == ROW_OUTPUT_RESOLUTION) {
                    cell.setTextAndValue(
                            LocaleController.getString(R.string.RoundVideoOutputResolution),
                            SharedSettings.roundVideoOutputResolution.get().getSize() + "p",
                            true
                    );
                } else if (position == ROW_CAMERA_RESOLUTION) {
                    cell.setTextAndValue(
                            LocaleController.getString(R.string.RoundVideoCameraResolution),
                            cameraResolutionLabel(SharedSettings.roundVideoCameraResolution.get()),
                            true
                    );
                } else {
                    cell.setTextAndValue(
                            LocaleController.getString(R.string.RoundVideoFrameRate),
                            SharedSettings.roundVideoFrameRate.get().getValue() + " FPS",
                            true
                    );
                }
            } else {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                cell.setText(position == ROW_GENERAL_INFO
                        ? LocaleController.getString(R.string.RoundVideoGeneralInfo)
                        : position == ROW_DIAGNOSTICS_INFO
                        ? LocaleController.getString(R.string.RoundVideoDiagnosticsInfo)
                        : LocaleController.getString(R.string.RoundVideoCompositionInfo));
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ROW_GENERAL_HEADER
                    || position == ROW_DIAGNOSTICS_HEADER
                    || position == ROW_COMPOSITION_HEADER) {
                return TYPE_HEADER;
            }
            if (position == ROW_ENABLED
                    || position == ROW_LOW_LIGHT
                    || position == ROW_STABILIZATION
                    || position == ROW_COMPOSITION) {
                return TYPE_CHECK;
            }
            if (position == ROW_GENERAL_INFO
                    || position == ROW_DIAGNOSTICS_INFO
                    || position == ROW_COMPOSITION_INFO) {
                return TYPE_INFO;
            }
            return TYPE_VALUE;
        }
    }
}
